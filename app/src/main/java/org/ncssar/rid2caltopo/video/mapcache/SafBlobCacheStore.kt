package org.ncssar.rid2caltopo.video.mapcache

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private object SafCacheIndexDatabaseHolder {
    private const val DB_DIR = "map_cache"
    private const val DB_NAME = "saf_cache_index.db"
    private const val OPEN_RETRY_COUNT = 3
    private const val OPEN_RETRY_DELAY_MS = 50L
    private val lock = Any()
    @Volatile
    private var sharedDb: SQLiteDatabase? = null

    fun get(context: Context): SQLiteDatabase {
        sharedDb?.takeIf { it.isOpen }?.let { return it }
        synchronized(lock) {
            sharedDb?.takeIf { it.isOpen }?.let { return it }
            val opened = openDatabaseWithRetry(context.applicationContext)
            ensureSchema(opened)
            sharedDb = opened
            return opened
        }
    }

    private fun openDatabaseWithRetry(context: Context): SQLiteDatabase {
        val dbDir = context.noBackupFilesDir.resolve(DB_DIR)
        if (!dbDir.exists()) dbDir.mkdirs()
        val dbFile = File(dbDir, DB_NAME)
        var lastError: SQLiteDatabaseLockedException? = null
        repeat(OPEN_RETRY_COUNT) { attempt ->
            try {
                return SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            } catch (e: SQLiteDatabaseLockedException) {
                lastError = e
                if (attempt == OPEN_RETRY_COUNT - 1) return@repeat
                Thread.sleep(OPEN_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Failed to open SAF cache index database.")
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS saf_entries (
                namespace TEXT NOT NULL,
                cache_key TEXT NOT NULL,
                file_uri TEXT NOT NULL,
                file_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                accessed_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                PRIMARY KEY (namespace, cache_key)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_saf_entries_ns_accessed ON saf_entries(namespace, accessed_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_saf_entries_ns_expires ON saf_entries(namespace, expires_at)")
    }
}

internal class SafBlobCacheStore(
    context: Context,
    private val rootDir: DocumentFile,
    private val namespace: String,
    private val maxBytes: Long,
    private val defaultTtlMs: Long
) : BlobCacheStore {
    private enum class ReadableLayoutKind {
        NONE,
        TILE,
        DEM
    }

    private val appContext = context.applicationContext
    private val storageNamespace = "$namespace@${digestHex(rootDir.uri.toString()).take(16)}"
    private val readableLayoutKind = when {
        namespace.startsWith("tile_cache_v") -> ReadableLayoutKind.TILE
        namespace.startsWith("dem_point_v") -> ReadableLayoutKind.DEM
        else -> ReadableLayoutKind.NONE
    }
    private val dbLock = Any()
    private val prewarmLock = Any()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val staleServedCount = AtomicLong(0)
    private val bytesUsedAtomic = AtomicLong(0L)
    private var namespaceFileIndex: MutableMap<String, DocumentFile>? = null
    @Volatile
    private var prewarmed = false

    private val db: SQLiteDatabase by lazy { SafCacheIndexDatabaseHolder.get(appContext) }

    override fun defaultExpiry(nowMs: Long): Long = nowMs + defaultTtlMs

    override fun put(cacheKey: String, bytes: ByteArray, expiresAtMs: Long) {
        val nsDir = getOrCreateNamespaceDir() ?: return
        val relativePath = keyToRelativePath(cacheKey)
        val target = ensureWritableFile(nsDir, relativePath) ?: return
        if (!writeBytes(Uri.parse(target.uri.toString()), bytes)) {
            MapCacheDebug.log("saf put-failed ns=$namespace key=$cacheKey uri=${target.uri}")
            return
        }

        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("namespace", storageNamespace)
            put("cache_key", cacheKey)
            put("file_uri", target.uri.toString())
            put("file_name", relativePath)
            put("size_bytes", bytes.size)
            put("created_at", now)
            put("accessed_at", now)
            put("expires_at", expiresAtMs)
        }
        synchronized(dbLock) {
            db.replaceOrThrow("saf_entries", null, cv)
            namespaceFileIndex?.set(relativePath, target)
            bytesUsedAtomic.set(bytesUsedLocked())
        }
        MapCacheDebug.log("saf put ns=$namespace key=$cacheKey bytes=${bytes.size} uri=${target.uri}")
    }

    override fun get(cacheKey: String, countHitMiss: Boolean): CachedBlob? {
        val row = synchronized(dbLock) {
            queryRow(cacheKey) ?: recoverRowFromFileLocked(cacheKey)
        }
        if (row == null) {
            if (countHitMiss) missCount.incrementAndGet()
            if (countHitMiss) {
                MapCacheDebug.log("saf miss ns=$namespace key=$cacheKey")
            }
            return null
        }

        val bytes = readBytes(Uri.parse(row.fileUri))
        if (bytes == null) {
            synchronized(dbLock) {
                deleteRow(cacheKey)
            }
            if (countHitMiss) missCount.incrementAndGet()
            if (countHitMiss) {
                MapCacheDebug.log("saf miss(ns-row-blob-missing) ns=$namespace key=$cacheKey uri=${row.fileUri}")
            }
            return null
        }

        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            db.execSQL(
                "UPDATE saf_entries SET accessed_at = ? WHERE namespace = ? AND cache_key = ?",
                arrayOf<Any>(now, storageNamespace, cacheKey)
            )
        }
        if (countHitMiss) hitCount.incrementAndGet()
        if (countHitMiss) {
            MapCacheDebug.log(
                "saf hit ns=$namespace key=$cacheKey bytes=${bytes.size} stale=${row.expiresAt < now} uri=${row.fileUri}"
            )
        }
        return CachedBlob(bytes = bytes, expiresAt = row.expiresAt, stale = row.expiresAt < now)
    }

    override fun getExpiration(cacheKey: String): Long? {
        synchronized(dbLock) {
            return (queryRow(cacheKey) ?: recoverRowFromFileLocked(cacheKey))?.expiresAt
        }
    }

    override fun exists(cacheKey: String): Boolean {
        // Offline-map preparation calls this thousands of times. Reading every
        // cached tile body just to prove it exists makes removable SAF storage
        // the bottleneck and needlessly updates the LRU timestamp for a probe.
        return synchronized(dbLock) {
            val indexed = queryRow(cacheKey)
            if (indexed != null && namespaceFileIndex?.containsKey(indexed.fileName) == false) {
                // A completed prewarm is a filesystem snapshot. Do not let a
                // stale database row make package/readiness checks skip a tile
                // that is no longer present on removable storage.
                deleteRow(cacheKey)
                null
            } else {
                indexed ?: recoverRowFromFileLocked(cacheKey)
            }
        } != null
    }

    override fun remove(cacheKey: String): Boolean {
        val (row, deletedRows) = synchronized(dbLock) {
            val existing = queryRow(cacheKey)
            val deleted = db.delete(
                "saf_entries",
                "namespace = ? AND cache_key = ?",
                arrayOf(storageNamespace, cacheKey)
            )
            existing to deleted
        }
        if (row != null) {
            try {
                DocumentFile.fromSingleUri(appContext, Uri.parse(row.fileUri))?.delete()
            } catch (e: Exception) {
                MapCacheDebug.log("saf remove delete-failed ns=$namespace key=$cacheKey uri=${row.fileUri} err=${e.javaClass.simpleName}")
            }
        }
        synchronized(dbLock) {
            namespaceFileIndex?.remove(row?.fileName ?: keyToRelativePath(cacheKey))
            namespaceFileIndex?.remove(legacyFileNameForKey(cacheKey))
            if (deletedRows > 0) bytesUsedAtomic.set(bytesUsedLocked())
        }
        return deletedRows > 0
    }

    override fun clear() {
        val nsDir = getNamespaceDir()
        synchronized(dbLock) {
            db.delete("saf_entries", "namespace = ?", arrayOf(storageNamespace))
            namespaceFileIndex?.clear()
            bytesUsedAtomic.set(0L)
        }
        nsDir?.listFiles()?.forEach { deleteRecursively(it) }
    }

    // Lock-free: all fields are AtomicLong; bytesUsedAtomic is kept current by put/remove/clear/evict.
    override fun snapshot(): CacheStatsSnapshot = CacheStatsSnapshot(
        hits = hitCount.get(),
        misses = missCount.get(),
        bytesUsed = bytesUsedAtomic.get(),
        evictions = evictionCount.get(),
        staleServed = staleServedCount.get()
    )

    override fun markStaleServed() {
        staleServedCount.incrementAndGet()
    }

    override fun usageBytes(): Long = synchronized(dbLock) {
        bytesUsedLocked().also(bytesUsedAtomic::set)
    }

    override fun prewarm() {
        if (prewarmed) return
        synchronized(prewarmLock) {
            if (prewarmed) return
            val nsDir = getNamespaceDir()
            if (nsDir == null) {
                MapCacheDebug.log("saf prewarm ns=$namespace skipped(no namespace dir)")
                prewarmed = true
                return
            }
            val fileIndex = buildNamespaceFileIndex(nsDir)
            val indexedFiles = fileIndex.mapNotNull { (relativePath, file) ->
                relativePathToCacheKey(relativePath)?.let { cacheKey ->
                    IndexedFile(
                        cacheKey = cacheKey,
                        relativePath = relativePath,
                        uri = file.uri.toString(),
                        sizeBytes = file.length().coerceAtLeast(0L)
                    )
                }
            }
            synchronized(dbLock) {
                namespaceFileIndex = fileIndex
                maybeRebuildIndexLocked(indexedFiles)
                bytesUsedAtomic.set(bytesUsedLocked())
                MapCacheDebug.log("saf prewarm ns=$namespace ready files=${namespaceFileIndex?.size ?: 0}")
            }
            prewarmed = true
        }
    }

    override fun runMaintenance(
        maxEntryAgeCutoffMs: Long,
        trimToBytes: Long,
        shouldContinue: () -> Boolean
    ): CacheMaintenanceResult {
        var agedOutEntries = 0
        var trimEvictedEntries = 0
        var bytesFreed = 0L

        while (shouldContinue()) {
            val victims: List<Triple<String, String, Long>> = synchronized(dbLock) {
                val cursor = db.query(
                    "saf_entries",
                    arrayOf("cache_key", "file_uri", "size_bytes"),
                    "namespace = ? AND created_at < ?",
                    arrayOf(storageNamespace, maxEntryAgeCutoffMs.toString()),
                    null,
                    null,
                    "created_at ASC",
                    "128"
                )
                cursor.use {
                    val out = ArrayList<Triple<String, String, Long>>(128)
                    while (it.moveToNext()) {
                        out += Triple(it.getString(0), it.getString(1), it.getLong(2))
                    }
                    out
                }
            }
            if (victims.isEmpty()) break
            for ((key, uri, size) in victims) {
                if (!shouldContinue()) break
                try {
                    DocumentFile.fromSingleUri(appContext, Uri.parse(uri))?.delete()
                } catch (_: Exception) {
                }
                val rows = synchronized(dbLock) {
                    namespaceFileIndex?.remove(keyToRelativePath(key))
                    namespaceFileIndex?.remove(legacyFileNameForKey(key))
                    db.delete(
                        "saf_entries",
                        "namespace = ? AND cache_key = ?",
                        arrayOf(storageNamespace, key)
                    )
                }
                if (rows > 0) {
                    agedOutEntries += 1
                    bytesFreed += size
                }
            }
        }

        val trimTarget = trimToBytes.coerceAtLeast(0L)
        while (shouldContinue()) {
            val victims: List<Triple<String, String, Long>> = synchronized(dbLock) {
                val current = bytesUsedLocked()
                bytesUsedAtomic.set(current)
                if (current <= trimTarget) return@synchronized emptyList()
                val cursor = db.query(
                    "saf_entries",
                    arrayOf("cache_key", "file_uri", "size_bytes"),
                    "namespace = ?",
                    arrayOf(storageNamespace),
                    null,
                    null,
                    "accessed_at ASC",
                    "128"
                )
                cursor.use {
                    val out = ArrayList<Triple<String, String, Long>>(128)
                    while (it.moveToNext()) {
                        out += Triple(it.getString(0), it.getString(1), it.getLong(2))
                    }
                    out
                }
            }
            if (victims.isEmpty()) break
            for ((key, uri, size) in victims) {
                if (!shouldContinue()) break
                try {
                    DocumentFile.fromSingleUri(appContext, Uri.parse(uri))?.delete()
                } catch (_: Exception) {
                }
                val rows = synchronized(dbLock) {
                    namespaceFileIndex?.remove(keyToRelativePath(key))
                    namespaceFileIndex?.remove(legacyFileNameForKey(key))
                    db.delete(
                        "saf_entries",
                        "namespace = ? AND cache_key = ?",
                        arrayOf(storageNamespace, key)
                    )
                }
                if (rows > 0) {
                    trimEvictedEntries += 1
                    bytesFreed += size
                    evictionCount.incrementAndGet()
                }
            }
        }

        val remaining = synchronized(dbLock) {
            bytesUsedLocked().also { bytesUsedAtomic.set(it) }
        }
        return CacheMaintenanceResult(
            agedOutEntries = agedOutEntries,
            trimEvictedEntries = trimEvictedEntries,
            bytesFreed = bytesFreed,
            bytesRemaining = remaining
        )
    }

    private fun bytesUsedLocked(): Long {
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM saf_entries WHERE namespace = ?",
            arrayOf(storageNamespace)
        )
        cursor.use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(0)
        }
    }

    private fun queryRow(cacheKey: String): Row? {
        val c = db.query(
            "saf_entries",
            arrayOf("file_uri", "file_name", "expires_at"),
            "namespace = ? AND cache_key = ?",
            arrayOf(storageNamespace, cacheKey),
            null,
            null,
            null
        )
        c.use {
            if (!it.moveToFirst()) return null
            return Row(
                fileUri = it.getString(0),
                fileName = it.getString(1),
                expiresAt = it.getLong(2)
            )
        }
    }

    private fun queryRowsLocked(): List<Row> {
        val c = db.query(
            "saf_entries",
            arrayOf("file_uri", "file_name", "expires_at"),
            "namespace = ?",
            arrayOf(storageNamespace),
            null,
            null,
            null
        )
        c.use {
            val rows = ArrayList<Row>(it.count.coerceAtLeast(0))
            while (it.moveToNext()) {
                rows += Row(
                    fileUri = it.getString(0),
                    fileName = it.getString(1),
                    expiresAt = it.getLong(2)
                )
            }
            return rows
        }
    }

    private fun deleteRow(cacheKey: String) {
        db.delete("saf_entries", "namespace = ? AND cache_key = ?", arrayOf(storageNamespace, cacheKey))
    }

    private fun recoverRowFromFileLocked(cacheKey: String): Row? {
        val nsDir = getNamespaceDir() ?: return null
        val relativePath = keyToRelativePath(cacheKey)
        val legacyPath = legacyFileNameForKey(cacheKey)
        val fileIndex = namespaceFileIndex
        val located = if (fileIndex != null) {
            val indexed = fileIndex[relativePath]?.takeIf { it.isFile }
            val legacy = fileIndex[legacyPath]?.takeIf { it.isFile }
            val resolvedFile = if (indexed == null && legacy != null && relativePath != legacyPath) {
                migrateLegacyFileLocked(nsDir, legacyPath, relativePath, legacy, fileIndex)
            } else {
                indexed ?: legacy
            } ?: return null
            val resolvedName = if (resolvedFile === legacy && indexed == null) legacyPath else relativePath
            LocatedFile(resolvedFile, resolvedName)
        } else {
            findCacheFile(nsDir, relativePath) ?: findCacheFile(nsDir, legacyPath) ?: return null
        }
        val now = System.currentTimeMillis()
        val expiresAt = defaultExpiry(now)
        val size = located.file.length().coerceAtLeast(0L)
        val uriString = located.file.uri.toString()
        val cv = ContentValues().apply {
            put("namespace", storageNamespace)
            put("cache_key", cacheKey)
            put("file_uri", uriString)
            put("file_name", located.fileName)
            put("size_bytes", size)
            put("created_at", now)
            put("accessed_at", now)
            put("expires_at", expiresAt)
        }
        db.replaceOrThrow("saf_entries", null, cv)
        namespaceFileIndex?.set(located.fileName, located.file)
        MapCacheDebug.log(
            "saf reindex ns=$namespace key=$cacheKey file=${located.fileName} size=$size uri=$uriString"
        )
        return Row(
            fileUri = uriString,
            fileName = located.fileName,
            expiresAt = expiresAt
        )
    }

    private fun migrateLegacyFileLocked(
        nsDir: DocumentFile,
        legacyPath: String,
        relativePath: String,
        legacyFile: DocumentFile,
        fileIndex: MutableMap<String, DocumentFile>
    ): DocumentFile? {
        val bytes = readBytes(legacyFile.uri) ?: return legacyFile
        val migratedFile = ensureWritableFile(nsDir, relativePath) ?: return legacyFile
        if (!writeBytes(migratedFile.uri, bytes)) return legacyFile
        try {
            legacyFile.delete()
        } catch (_: Exception) {
        }
        fileIndex.remove(legacyPath)
        fileIndex[relativePath] = migratedFile
        MapCacheDebug.log("saf migrate ns=$namespace from=$legacyPath to=$relativePath uri=${migratedFile.uri}")
        return migratedFile
    }

    private fun ensureNamespaceFileIndexLocked(nsDir: DocumentFile): MutableMap<String, DocumentFile> {
        namespaceFileIndex?.let { return it }
        val index = buildNamespaceFileIndex(nsDir)
        namespaceFileIndex = index
        return index
    }

    private fun buildNamespaceFileIndex(nsDir: DocumentFile): MutableMap<String, DocumentFile> {
        val startNs = System.nanoTime()
        val index = HashMap<String, DocumentFile>()
        try {
            indexNamespaceFiles(nsDir, "", index)
        } catch (e: Exception) {
            MapCacheDebug.log("saf index-build list-failed ns=$namespace err=${e.javaClass.simpleName}")
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        MapCacheDebug.log(
            "saf index-build ns=$namespace files=${index.size} elapsedMs=${"%.1f".format(Locale.US, elapsedMs)}"
        )
        return index
    }

    private fun findCacheFile(nsDir: DocumentFile, relativePath: String): LocatedFile? {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var current = nsDir
        for (segment in segments.dropLast(1)) {
            current = try {
                current.findFile(segment)
            } catch (e: Exception) {
                MapCacheDebug.log("saf findExactDir failed ns=$namespace dir=$segment err=${e.javaClass.simpleName}")
                null
            } ?: return null
            if (!current.isDirectory) return null
        }
        val leaf = try {
            current.findFile(segments.last())
        } catch (e: Exception) {
            MapCacheDebug.log("saf findExactFile failed ns=$namespace file=$relativePath err=${e.javaClass.simpleName}")
            null
        } ?: return null
        return if (leaf.isFile) LocatedFile(leaf, relativePath) else null
    }

    private fun maybeRebuildIndexLocked(indexedFiles: List<IndexedFile>) {
        if (readableLayoutKind == ReadableLayoutKind.NONE) return
        val readableFiles = indexedFiles.size
        if (readableFiles <= 0) return

        val dbRows = queryRowsLocked()
        val missingIndex = dbRows.isEmpty()
        val inconsistentIndex = !missingIndex && isIndexInconsistent(indexedFiles, dbRows)
        if (!missingIndex && !inconsistentIndex) return

        rebuildIndexFromFilesystemLocked(
            indexedFiles = indexedFiles,
            dbRowCount = dbRows.size,
            readableFileCount = readableFiles,
            reason = if (missingIndex) "missing" else "inconsistent"
        )
    }

    private fun isIndexInconsistent(
        indexedFiles: List<IndexedFile>,
        dbRows: List<Row>
    ): Boolean {
        val readableDbRows = dbRows.filter { relativePathToCacheKey(it.fileName) != null }
        if (readableDbRows.size != indexedFiles.size) return true
        val filesByPath = indexedFiles.associateBy { it.relativePath }
        for (row in readableDbRows) {
            val file = filesByPath[row.fileName] ?: return true
            if (file.uri != row.fileUri) return true
        }
        return false
    }

    private fun rebuildIndexFromFilesystemLocked(
        indexedFiles: List<IndexedFile>,
        dbRowCount: Int,
        readableFileCount: Int,
        reason: String
    ) {
        val startedNs = System.nanoTime()
        val now = System.currentTimeMillis()
        var rebuilt = 0
        var skipped = 0
        db.beginTransaction()
        try {
            db.delete("saf_entries", "namespace = ?", arrayOf(storageNamespace))
            for (file in indexedFiles) {
                val cv = ContentValues().apply {
                    put("namespace", storageNamespace)
                    put("cache_key", file.cacheKey)
                    put("file_uri", file.uri)
                    put("file_name", file.relativePath)
                    put("size_bytes", file.sizeBytes)
                    put("created_at", now)
                    put("accessed_at", now)
                    put("expires_at", defaultExpiry(now))
                }
                db.replaceOrThrow("saf_entries", null, cv)
                rebuilt += 1
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0
        MapCacheDebug.log(
            "saf reindex-full ns=$namespace reason=$reason dbRows=$dbRowCount readableFiles=$readableFileCount rebuilt=$rebuilt skipped=$skipped elapsedMs=${"%.1f".format(Locale.US, elapsedMs)}"
        )
    }

    private fun getNamespaceDir(): DocumentFile? {
        return try {
            rootDir.findFile(namespace)
        } catch (e: Exception) {
            MapCacheDebug.log("saf getNamespaceDir failed ns=$namespace err=${e.javaClass.simpleName}")
            null
        }
    }

    private fun getOrCreateNamespaceDir(): DocumentFile? {
        val existing = getNamespaceDir()
        if (existing != null && existing.isDirectory) return existing
        return try {
            rootDir.createDirectory(namespace)
        } catch (e: Exception) {
            MapCacheDebug.log("saf createNamespaceDir failed ns=$namespace err=${e.javaClass.simpleName}")
            null
        }
    }

    private fun ensureWritableFile(nsDir: DocumentFile, relativePath: String): DocumentFile? {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var parent = nsDir
        for (segment in segments.dropLast(1)) {
            parent = getOrCreateChildDirectory(parent, segment) ?: return null
        }
        val fileName = segments.last()
        val existing = try {
            parent.findFile(fileName)
        } catch (e: Exception) {
            MapCacheDebug.log("saf findFile failed ns=$namespace file=$relativePath err=${e.javaClass.simpleName}")
            null
        }
        if (existing != null) {
            if (!existing.isFile) {
                existing.delete()
            } else {
                return existing
            }
        }
        return try {
            parent.createFile("application/octet-stream", fileName)
        } catch (e: Exception) {
            MapCacheDebug.log("saf createFile failed ns=$namespace file=$relativePath err=${e.javaClass.simpleName}")
            null
        }
    }

    private fun getOrCreateChildDirectory(parent: DocumentFile, dirName: String): DocumentFile? {
        val existing = try {
            parent.findFile(dirName)
        } catch (e: Exception) {
            MapCacheDebug.log("saf findDir failed ns=$namespace dir=$dirName err=${e.javaClass.simpleName}")
            null
        }
        if (existing != null) {
            if (existing.isDirectory) return existing
            existing.delete()
        }
        return try {
            parent.createDirectory(dirName)
        } catch (e: Exception) {
            MapCacheDebug.log("saf createDir failed ns=$namespace dir=$dirName err=${e.javaClass.simpleName}")
            null
        }
    }

    private fun indexNamespaceFiles(
        dir: DocumentFile,
        prefix: String,
        index: MutableMap<String, DocumentFile>
    ) {
        dir.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            if (file.isFile) {
                index[relative] = file
            } else if (file.isDirectory) {
                indexNamespaceFiles(file, relative, index)
            }
        }
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { child -> deleteRecursively(child) }
        }
        file.delete()
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray): Boolean {
        return try {
            appContext.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readBytes(uri: Uri): ByteArray? {
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8 * 1024)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun keyToRelativePath(cacheKey: String): String {
        when (readableLayoutKind) {
            ReadableLayoutKind.TILE -> {
                val parts = cacheKey.split('|')
                if (parts.size == 5) {
                    val version = parts[0]
                    val source = sanitizePathSegment(parts[1])
                    val zoom = sanitizePathSegment(parts[2])
                    val x = sanitizePathSegment(parts[3])
                    val y = sanitizePathSegment(parts[4])
                    if (version.startsWith("v") &&
                        source.isNotEmpty() &&
                        zoom.isNotEmpty() &&
                        x.isNotEmpty() &&
                        y.isNotEmpty()
                    ) {
                        return "$source/$zoom/$x/$y.bin"
                    }
                }
            }
            ReadableLayoutKind.DEM -> {
                val parts = cacheKey.split('|')
                if (parts.size == 4) {
                    val version = parts[0]
                    val qLat = sanitizePathSegment(parts[1])
                    val qLng = sanitizePathSegment(parts[2])
                    val units = sanitizePathSegment(parts[3])
                    if (version.startsWith("v") &&
                        qLat.isNotEmpty() &&
                        qLng.isNotEmpty() &&
                        units.isNotEmpty()
                    ) {
                        return "$qLat/$qLng/$units.bin"
                    }
                }
            }
            ReadableLayoutKind.NONE -> Unit
        }
        val parts = cacheKey.split('|')
        return legacyFileNameForKey(cacheKey)
    }

    private fun relativePathToCacheKey(relativePath: String): String? {
        if (readableLayoutKind == ReadableLayoutKind.NONE || !relativePath.endsWith(".bin")) return null
        val trimmed = relativePath.removeSuffix(".bin")
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        return when (readableLayoutKind) {
            ReadableLayoutKind.TILE -> {
                if (parts.size != 4) return null
                val source = parts[0]
                val zoom = parts[1]
                val x = parts[2]
                val y = parts[3]
                if (!zoom.all(Char::isDigit) || !x.all(Char::isDigit) || !y.all(Char::isDigit)) return null
                "v${namespaceVersion()}|$source|$zoom|$x|$y"
            }
            ReadableLayoutKind.DEM -> {
                if (parts.size != 3) return null
                val qLat = parts[0]
                val qLng = parts[1]
                val units = parts[2]
                if (!isSignedDigits(qLat) || !isSignedDigits(qLng) || units.isEmpty()) return null
                "v${namespaceVersion()}|$qLat|$qLng|$units"
            }
            ReadableLayoutKind.NONE -> null
        }
    }

    private fun namespaceVersion(): String {
        return namespace.substringAfterLast("_v", "")
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: "1"
    }

    private fun legacyFileNameForKey(cacheKey: String): String {
        return "${digestHex(cacheKey)}.bin"
    }

    private fun sanitizePathSegment(value: String): String {
        return buildString(value.length) {
            value.forEach { ch ->
                when {
                    ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' -> append(ch)
                    else -> append('_')
                }
            }
        }.trim('_')
    }

    private fun isSignedDigits(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value[0] == '-') {
            return value.length > 1 && value.substring(1).all(Char::isDigit)
        }
        return value.all(Char::isDigit)
    }

    private fun digestHex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    private data class Row(
        val fileUri: String,
        val fileName: String,
        val expiresAt: Long
    )

    private data class LocatedFile(
        val file: DocumentFile,
        val fileName: String
    )

    private data class IndexedFile(
        val cacheKey: String,
        val relativePath: String,
        val uri: String,
        val sizeBytes: Long
    )
}
