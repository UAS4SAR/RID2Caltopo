package org.ncssar.rid2caltopo.storage

import android.content.Context
import android.os.Looper
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Archive Dir owns retained bytes. File-based codecs use a disposable projection.
 * Only workers may prepare/read/write it; Compose observes the small status flow.
 */
internal object OperationalStorage {
    data class Status(val busy: Boolean = false, val ready: Boolean = false, val message: String = "")
    private val state = MutableStateFlow(Status())
    val status = state.asStateFlow()
    private val gate = Any()
    private val queueGate = Any()
    private val recorderGate = Any()
    private val recoveryGate = Any()
    private const val QUEUE_JOURNAL = "application-logs/storage-recovery/queue-state"
    private data class PreparedQueue(val value: Store, val generation: Long)
    @Volatile private var preparedQueue: PreparedQueue? = null
    private const val RECORDER_JOURNAL = "application-logs/storage-recovery/recorder-state"
    private val recorderAreas = setOf("unfinished-recordings", "recording-work")
    private data class PreparedRecorder(val value: Store, val generation: Long)
    @Volatile private var preparedRecorder: PreparedRecorder? = null
    private var recoveredUri: String? = null
    @Volatile private var store: Store? = null
    private val selectionGeneration = java.util.concurrent.atomic.AtomicLong()
    private val startup = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "archive-storage-setup").apply { isDaemon = true } }
    private data class Area(val id: String, val retained: String, val alias: File, val sources: List<File>)
    private data class Store(val uri: String, val work: File, val tree: ArchiveTree, val writer: VerifiedArchiveTree, val areas: List<Area>, val migrated: MutableMap<String, String> = mutableMapOf(), val growingOffsets: MutableMap<String, Long> = mutableMapOf())
    private fun worker() = check(Looper.myLooper() != Looper.getMainLooper()) { "Operational storage must run on a worker" }
    fun start(context: Context) { startup.execute { runCatching { TemporaryStorage.cleanAbandoned(context); prepare(context.applicationContext) } } }
    fun retry(context: Context) = start(context)

    private fun areas(context: Context): List<Area> {
        val external = context.getExternalFilesDirs(null).filterNotNull().map { File(it, "local-mapping") }
        fun area(id: String, target: String, alias: File, extra: List<File> = emptyList()) = Area(id, target, alias, listOf(alias) + extra)
        return listOf(
            area("missions", "cache/local-maps/mapping-missions", File(context.filesDir, "mapping-missions"), external.map { File(it, "mapping-missions") }),
            area("layers", "cache/local-maps/mapping-layers", File(context.filesDir, "mapping-layers"), external.map { File(it, "mapping-layers") }),
            area("processing", "cache/local-maps/mapping-processing", File(context.filesDir, "mapping-processing"), external.map { File(it, "mapping-processing") }),
            area("map-statistics", "cache/local-maps/mapping-storage-stats", File(context.filesDir, "mapping-storage-stats")),
            area("decoded-terrain", "cache/decoded-terrain", File(context.noBackupFilesDir, "dem_blocks_v2")),
            area("legacy-cache", "cache/local-databases", File(context.noBackupFilesDir, "map_cache")),
            area("legacy-fallback", "cache/imported-fallback", File(context.cacheDir, "map_cache_fallback")),
            area("map-archives", "cache/imported-osmdroid", File(context.filesDir, "map-archives"), context.getExternalFilesDirs(null).filterNotNull().map { File(it, "osmdroid") }),
            area("clues", "application-logs/operational/clues", File(context.filesDir, "clues")),
            area("recordings", "application-logs/operational/recordings", File(context.filesDir, "managed-video-session-recordings")),
            area("unfinished-recordings", "application-logs/operational/unfinished-recordings", File(context.filesDir, "mediamtx-recordings")),
            area("recording-work", "application-logs/operational/recording-recovery", File(context.filesDir, "mediamtx-recordings-merged")),
            area("recording-originals", "application-logs/operational/recording-originals", File(context.filesDir, "video-sei-originals")),
            area("support-data", "cache/support-data/land-restrictions", File(context.filesDir, "land-restrictions")),
            area("diagnostic-traces", "application-logs/diagnostic-traces", File(context.filesDir, "anr-traces")),
            area("review-annotations", "application-logs/operational/review-annotations", File(context.filesDir, "review-annotations")),
            area("queue-state", "application-logs/operational/queue-state", File(context.filesDir, "operational-state"))
        )
    }

    fun prepare(context: Context, testArchive: DocumentFile? = null) {
        worker()
        synchronized(gate) {
            val uri = testArchive?.uri?.toString() ?: CaltopoClient.GetArchiveUri()?.toString()
                ?: throw unavailable("Choose or reconnect Archive Dir to use retained operational data.")
            store?.let {
                check(it.uri == uri) { "Restart the app after changing Archive Dir." }
                kotlin.check(state.value.ready) { state.value.message.ifEmpty { "Restart the app to reconnect Archive Dir." } }
                return
            }
            val generation = selectionGeneration.get()
            fun checkSelection() = check(selectionGeneration.get() == generation) { "Archive Dir changed during preparation. Restart the app to use the selected folder." }
            state.value = Status(busy = true, message = "Preparing managed archive storage…")
            try {
                val root = testArchive ?: CaltopoClient.GetArchiveDir() ?: error("Archive Dir is unavailable.")
                check(root.isDirectory && root.canRead() && root.canWrite()) { "Archive Dir is not readable and writable." }
                val tree = DocumentArchiveTree(context, root)
                val writer = VerifiedArchiveTree(tree)
                val selected = areas(context)
                // Stable within a selected archive, but disposable between invocations.
                val name = VerifiedArchiveTree.digest(uri.byteInputStream()).take(16)
                val work = File(TemporaryStorage.workspaceRoot(context, root.uri), "operational-work/$name")
                val value = Store(uri, work, tree, writer, selected)
                val hydrationScratch = TemporaryStorage.directory(context, "archive-hydration", root.uri)
                recoverArchive(value)
                prepareQueue(context, root, uri)
                prepareRecorder(context, root, uri)
                // The queue lane has its own provider cache and writer. Refresh this lane's
                // directory snapshot before bulk work; it never mutates queue contents.
                tree.refresh()
                for (area in selected.filterNot { it.id == "queue-state" || it.id in recorderAreas }) {
                    migrateAreaSources(context, value, area, ::checkSelection)
                }
                // Each bulk source is verified before its old projection is replaced. The
                // independently prepared queue area is never scanned or hydrated again here.
                for (area in selected.filterNot { it.id == "queue-state" || it.id in recorderAreas }) {
                    prepareAreaProjection(context, value, area, hydrationScratch, ::checkSelection)
                }
                importLegacyCaches(context, root, value)
                checkSelection()
                check(root.isDirectory && root.canRead() && root.canWrite()) {
                    "Archive Dir became unavailable during preparation; reconnect its storage."
                }
                store = value
                value.migrated.clear()
                state.value = Status(ready = true, message = "Retained operational data is in Archive Dir.")
            } catch (error: Exception) {
                state.value = Status(message = error.message ?: "Archive storage could not be prepared; original copies were retained.")
                throw error
            }
        }
    }
    private fun recoverArchive(value: Store) = synchronized(recoveryGate) {
        recoveredUri?.let { check(it == value.uri) { "Restart the app after changing Archive Dir." }; return@synchronized }
        for (folder in listOf("cache", "application-logs")) {
            value.tree.mkdirs(folder)
            if (!value.tree.exists("$folder/.nomedia")) value.tree.write("$folder/.nomedia", byteArrayOf().inputStream())
        }
        // Distinct transactions can use the directory concurrently after bootstrap; create its
        // shared parents once before either provider adapter caches their children.
        value.tree.mkdirs(VerifiedArchiveTree.JOURNAL)
        value.tree.mkdirs("application-logs/storage-deletions")
        VerifiedArchiveTree(value.tree).recover()
        recoverDeletions(value)
        recoveredUri = value.uri
    }
    private fun checkQueue(current: PreparedQueue) {
        check(selectionGeneration.get() == current.generation) { "Archive Dir changed; restart the app before saving operational state." }
        check(preparedQueue === current) { "Operational state is no longer available." }
    }
    private fun prepareQueue(context: Context, archive: DocumentFile? = null, selectedUri: String? = null): PreparedQueue {
        worker()
        synchronized(queueGate) {
            // A TreeDocumentFile's document URI can differ from the persisted tree URI.
            // Both lanes must use the same selected URI for identity and projection paths.
            val uri = selectedUri ?: CaltopoClient.GetArchiveUri()?.toString() ?: archive?.uri?.toString()
                ?: error("Choose or reconnect Archive Dir to use retained operational state.")
            preparedQueue?.let { check(it.value.uri == uri) { "Restart the app after changing Archive Dir." }; checkQueue(it); return it }
            val generation = selectionGeneration.get()
            fun checkSelection() = check(selectionGeneration.get() == generation) { "Archive Dir changed during operational-state preparation." }
            val root = archive ?: CaltopoClient.GetArchiveDir() ?: error("Archive Dir is unavailable.")
            check(root.isDirectory && root.canRead() && root.canWrite()) { "Archive Dir is not readable and writable." }
            // This provider adapter and writer belong only to the small queue lane. Sharing the
            // bulk writer would still wait behind a large recording's verification lock.
            val tree = DocumentArchiveTree(context, root)
            val writer = VerifiedArchiveTree(tree, journalDirectory = QUEUE_JOURNAL, cancelled = ::checkSelection)
            val selected = areas(context)
            val name = VerifiedArchiveTree.digest(uri.byteInputStream()).take(16)
            val work = File(TemporaryStorage.workspaceRoot(context, root.uri), "operational-work/$name")
            val all = Store(uri, work, tree, writer, selected)
            recoverArchive(all)
            tree.refresh()
            tree.mkdirs(QUEUE_JOURNAL)
            writer.recover()
            val area = selected.single { it.id == "queue-state" }
            val value = Store(uri, work, tree, writer, listOf(area))
            val scratch = TemporaryStorage.directory(context, "archive-hydration", root.uri)
            migrateAreaSources(context, value, area, ::checkSelection)
            prepareAreaProjection(context, value, area, scratch, ::checkSelection)
            checkSelection()
            check(root.isDirectory && root.canRead() && root.canWrite()) { "Archive Dir became unavailable during operational-state preparation." }
            value.migrated.clear()
            return PreparedQueue(value, generation).also {
                preparedQueue = it
                CaltopoClient.CTDebug("OperationalStorage", "Operational state ready independently of bulk storage")
            }
        }
    }
    private fun checkRecorder(current: PreparedRecorder) {
        check(selectionGeneration.get() == current.generation && preparedRecorder === current) {
            "Archive Dir changed; restart the app before recording."
        }
    }
    /** Only unfinished recording state is needed to accept a new publisher. */
    private fun prepareRecorder(context: Context, archive: DocumentFile? = null, selectedUri: String? = null): PreparedRecorder {
        worker()
        synchronized(recorderGate) {
            val uri = selectedUri ?: CaltopoClient.GetArchiveUri()?.toString() ?: archive?.uri?.toString()
                ?: error("Choose or reconnect Archive Dir before recording.")
            preparedRecorder?.let { check(it.value.uri == uri); checkRecorder(it); return it }
            val generation = selectionGeneration.get()
            fun checkSelection() = check(selectionGeneration.get() == generation) { "Archive Dir changed during recorder preparation." }
            val root = archive ?: CaltopoClient.GetArchiveDir() ?: error("Archive Dir is unavailable.")
            check(root.isDirectory && root.canRead() && root.canWrite()) { "Archive Dir is not readable and writable." }
            val tree = DocumentArchiveTree(context, root)
            val writer = VerifiedArchiveTree(tree, journalDirectory = RECORDER_JOURNAL, cancelled = ::checkSelection)
            val selected = areas(context)
            val name = VerifiedArchiveTree.digest(uri.byteInputStream()).take(16)
            val work = File(TemporaryStorage.workspaceRoot(context, root.uri), "operational-work/$name")
            recoverArchive(Store(uri, work, tree, writer, selected))
            tree.refresh(); tree.mkdirs(RECORDER_JOURNAL); writer.recover()
            val value = Store(uri, work, tree, writer, selected.filter { it.id in recorderAreas })
            val scratch = TemporaryStorage.directory(context, "archive-hydration", root.uri)
            for (area in value.areas) {
                migrateAreaSources(context, value, area, ::checkSelection)
                prepareAreaProjection(context, value, area, scratch, ::checkSelection)
            }
            checkSelection()
            check(root.isDirectory && root.canRead() && root.canWrite()) { "Archive Dir became unavailable during recorder preparation." }
            value.migrated.clear()
            return PreparedRecorder(value, generation).also {
                preparedRecorder = it
                CaltopoClient.CTDebug("OperationalStorage", "Recorder state ready independently of bulk storage")
            }
        }
    }
    fun requireRecorderReady(context: Context) {
        worker()
        preparedRecorder?.let { current ->
            check(current.value.uri == CaltopoClient.GetArchiveUri()?.toString()) { "Restart the app after changing Archive Dir." }
            checkRecorder(current)
            return
        }
        prepareRecorder(context.applicationContext)
    }
    private inline fun onRecordingStore(file: File, action: (Store) -> Unit) {
        worker()
        val recorder = preparedRecorder?.takeIf { mapping(file, it.value) != null }
        val value = recorder?.value ?: store ?: return
        synchronized(if (recorder != null) recorderGate else gate) {
            if (recorder != null) checkRecorder(recorder)
            else check(state.value.ready) { state.value.message.ifEmpty { "Archive Dir is unavailable." } }
            action(value)
            if (recorder != null) checkRecorder(recorder)
        }
    }
    private fun migrateAreaSources(context: Context, value: Store, area: Area, checkSelection: () -> Unit) {
        val work = value.work
        val sourceStarted = android.os.SystemClock.elapsedRealtime()
        checkSelection()
        state.value = Status(busy = true, message = "Moving retained ${area.id} to Archive Dir…")
        for (source in area.sources) {
            val link = Files.isSymbolicLink(source.toPath())
            val projection = if (link) projectedRoot(context, area, source) else null
            if (link && !source.exists() && projection == null) error("Reconnect the storage holding ${area.id}; its original files were retained.")
            if (projection != null && !source.exists() && projection.parent.fileName.toString() != work.name) error("Reconnect the previous Archive Dir before moving ${area.id} to another archive.")
            if (!source.exists()) continue
            val projected = projection != null
            // Uncommitted CPU scratch is disposable. Published checkpoints already
            // have durable copies; legacy checkpoints migrate on the first run.
            if (projected && projection?.parent?.fileName.toString() == work.name && area.id in listOf("processing", "layers")) continue
            copySource(value, area, source, source, replace = projected, ancestry = emptySet(), projection = projection)
        }
        if (area.id == "queue-state") for (name in queueNames) {
            val source = File(context.filesDir, name)
            if (source.isFile) commit(value, source, "${area.retained}/$name", replace = false)
        }
        if (area.id == "review-annotations") for (source in context.cacheDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".review.json") }) {
            commit(value, source, "${area.retained}/${source.name}", replace = false)
        }
        CaltopoClient.CTDebug("OperationalStorage", "Verified sources area=${area.id} elapsedMs=${android.os.SystemClock.elapsedRealtime() - sourceStarted}")
    }
    private fun prepareAreaProjection(context: Context, value: Store, area: Area, hydrationScratch: File, checkSelection: () -> Unit) {
        val work = value.work
        val hydrationStarted = android.os.SystemClock.elapsedRealtime()
        checkSelection()
        val destination = File(work, area.id)
        val reuse = area.id !in listOf("processing", "layers") &&
            destination.isDirectory && Files.isSymbolicLink(area.alias.toPath()) &&
            projectedRoot(context, area, area.alias) == destination.canonicalFile.toPath() &&
            !requiresIsolatedHydration(value, area.retained)
        val staged = if (reuse) destination else File(work.parentFile, ".${area.id}-${UUID.randomUUID()}")
        check(staged.isDirectory || staged.mkdirs())
        hydrate(value, area.retained, staged, reuse, hydrationScratch, checkSelection)
        for ((path, hash) in value.migrated.filterKeys { it.startsWith(area.retained + "/") }) {
            val copied = File(staged, path.removePrefix(area.retained + "/"))
            check(copied.isFile && copied.inputStream().use { VerifiedArchiveTree.digest(it) } == hash) {
                "Archive hydration did not preserve ${area.id}; original copies were retained."
            }
        }
        checkSelection()
        if (!reuse) {
            if (destination.exists()) removeTree(destination)
            destination.parentFile!!.mkdirs()
            check(staged.renameTo(destination)) { "Cannot prepare temporary ${area.id} workspace." }
        }
        for (source in area.sources) {
            if (Files.isSymbolicLink(source.toPath())) Files.delete(source.toPath())
            else if (source.exists()) removeTree(source)
        }
        area.alias.parentFile!!.mkdirs()
        Os.symlink(destination.absolutePath, area.alias.absolutePath)
        if (area.id == "queue-state") for (name in queueNames) File(context.filesDir, name).let { if (it.exists()) check(it.delete()) }
        if (area.id == "review-annotations") context.cacheDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".review.json") }.forEach { check(it.delete()) }
        CaltopoClient.CTDebug("OperationalStorage", "Prepared area=${area.id} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime() - hydrationStarted}")
    }
    private fun unavailable(message: String): IllegalStateException {
        state.value = Status(message = message)
        return IllegalStateException(message)
    }
    private fun importLegacyCaches(context: Context, root: DocumentFile, value: Store) {
        val cache = root.findFile("cache") ?: root.createDirectory("cache") ?: error("Cannot access retained map cache.")
        val names = mapOf("tile_cache_v1.db" to "tile_cache_v1", "icon_cache_v1.db" to "icon_cache_v1", "dem_point_v2.db" to "dem_point_v2")
        for (area in value.areas.filter { it.id in listOf("legacy-cache", "legacy-fallback") }) {
            val working = File(value.work, area.id)
            for ((database, namespace) in names) {
                val source = File(working, database)
                if (!source.isFile) continue
                state.value = Status(busy = true, message = "Moving cached map data into Archive Dir…")
                val destination = org.ncssar.rid2caltopo.video.mapcache.SafBlobCacheStore(context, cache, namespace, Long.MAX_VALUE, 365L * 86400000)
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(source.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                db.use { sql ->
                    sql.rawQuery("SELECT cache_key, blob_data, expires_at FROM entries", null).use { rows ->
                        while (rows.moveToNext()) {
                            val key = rows.getString(0); val bytes = rows.getBlob(1)
                            destination.put(key, bytes, rows.getLong(2))
                            check(destination.get(key, false)?.bytes?.contentEquals(bytes) == true) { "Legacy map-cache migration did not verify; the original database remains in Archive Dir." }
                        }
                    }
                }
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    value.tree.delete("${area.retained}/$database$suffix")
                    value.tree.delete("${area.retained}/$database$suffix.r2c-info")
                    File(working, database + suffix).delete()
                }
            }
        }
        val legacy = value.areas.single { it.id == "map-archives" }
        val workspace = File(value.work, legacy.id)
        for (source in workspace.walkTopDown().filter { it.isFile && it.name == "cache.db" }) {
            val destination = org.ncssar.rid2caltopo.video.mapcache.SafBlobCacheStore(context, cache, "tile_cache_v1", Long.MAX_VALUE, 365L * 86400000)
            android.database.sqlite.SQLiteDatabase.openDatabase(source.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { sql ->
                val columns = sql.rawQuery("PRAGMA table_info(tiles)", null).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(1)) } }
                if (!columns.containsAll(listOf("key", "provider", "tile"))) continue
                val expires = if ("expires" in columns) "expires" else "0"
                sql.rawQuery("SELECT key, provider, tile, $expires FROM tiles", null).use { rows ->
                    while (rows.moveToNext()) {
                        val index = rows.getLong(0)
                        val z = (0..29).firstOrNull { zoom ->
                            val start = zoom.toLong() shl (2 * zoom)
                            index >= start && index - start < (1L shl (2 * zoom))
                        } ?: error("Unrecognized legacy map tile; its database was retained.")
                        val offset = index - (z.toLong() shl (2 * z))
                        val x = offset shr z; val y = offset and ((1L shl z) - 1)
                        val key = "v1|${rows.getString(1)}|$z|$x|$y"
                        val bytes = rows.getBlob(2)
                        destination.put(key, bytes, rows.getLong(3))
                        check(destination.get(key, false)?.bytes?.contentEquals(bytes) == true) { "Legacy map tile could not be verified; its database was retained." }
                    }
                }
            }
            val relative = source.relativeTo(workspace).invariantSeparatorsPath
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                value.tree.delete("${legacy.retained}/$relative$suffix")
                value.tree.delete("${legacy.retained}/$relative$suffix.r2c-info")
                File(source.path + suffix).delete()
            }
        }
    }

    private fun allowed(area: Area, relative: String): Boolean {
        val name = relative.substringAfterLast('/')
        if (name.endsWith(".r2c-info") || name.contains(".r2c-stage-") || name.contains(".r2c-previous-")) return false
        // Rebuildable SQL indexes and transient controller access records are not
        // retained credentials or operational originals.
        if (area.id == "legacy-cache" && name.startsWith("saf_cache_index.db")) return false
        if (name == "mapping-volume.json") return false
        if (area.id == "missions" && relative.contains("/cloud/") &&
            (name.contains("credential", true) || name.contains("session", true) || name.contains("token", true))) return false
        return true
    }
    private fun projectedRoot(context: Context, area: Area, source: File): Path? {
        val target = File(Os.readlink(source.path)).canonicalFile.toPath()
        for (root in TemporaryStorage.roots(context)) {
            val base = File(root, "operational-work").canonicalFile.toPath()
            // Cache removal must not turn a recoverable projection into a missing legacy SD card.
            if (target.startsWith(base) && target.nameCount == base.nameCount + 2 &&
                target.fileName.toString() == area.id && target.parent.fileName.toString().matches(Regex("[a-f0-9]{16}"))) return target
        }
        return null
    }
    private fun copySource(value: Store, area: Area, base: File, source: File, replace: Boolean, ancestry: Set<Path>, projection: Path? = null) {
        val canonical = source.canonicalFile.toPath()
        val permitted = area.sources.map { File(it.parentFile!!.canonicalFile, it.name).toPath() } + listOf(File(value.work, area.id).canonicalFile.toPath()) + listOfNotNull(projection)
        check(permitted.any { canonical.startsWith(it) }) { "Unexpected link in retained ${area.id}; original files were retained." }
        check(canonical !in ancestry) { "Cyclic link in retained ${area.id}; original files were retained." }
        if (source.isDirectory) {
            val children = source.listFiles() ?: error("Cannot read retained ${area.id}; original copies were retained.")
            for (child in children) copySource(value, area, base, child, replace, ancestry + setOf(canonical), projection)
        } else {
            val relative = source.relativeTo(base).invariantSeparatorsPath
            if (!allowed(area, relative)) return
            commit(value, source, "${area.retained}/$relative", replace)
        }
    }
    private fun commit(value: Store, source: File, path: String, replace: Boolean = true) {
        value.writer.put(source, path, replace, modified = source.lastModified())
        if (!state.value.ready) value.migrated[path] = source.inputStream().use { VerifiedArchiveTree.digest(it) }
        if (value.tree.exists("$path.r2c-chunks")) value.tree.delete("$path.r2c-chunks")
        forgetHeader(value, path)
        value.growingOffsets.remove(path)
    }
    private fun requiresIsolatedHydration(value: Store, path: String): Boolean {
        val entries = value.tree.list(path)
        if (entries.any { it.name == ".r2c-current" || it.name.endsWith(".r2c-chunks") }) return true
        return entries.any { it.directory && requiresIsolatedHydration(value, "$path/${it.name}") }
    }
    private fun hydrate(value: Store, path: String, directory: File, reuse: Boolean = false,
                        scratch: File? = null, checkCurrent: () -> Unit = {}) {
        if (value.tree.exists("$path/.r2c-current")) {
            val manifest = value.tree.read("$path/.r2c-current").use { VerifiedArchiveTree.json(it) }
            val revision = UUID.fromString(manifest.getString("revision")).toString()
            val files = manifest.getJSONArray("files")
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val relative = VerifiedArchiveTree.checked(item.getString("path"))
                val target = File(directory, relative)
                target.parentFile!!.mkdirs()
                FileOutputStream(target).use { out -> value.tree.read("$path/.r2c-versions/$revision/$relative").use { it.copyTo(out, 256 * 1024) }; out.fd.sync() }
                check(target.length() == item.getLong("bytes") && target.inputStream().use { VerifiedArchiveTree.digest(it) } == item.getString("sha256")) {
                    "Retained map generation could not be verified."
                }
                target.setLastModified(item.getLong("modified"))
            }
            return
        }
        for (entry in value.tree.list(path)) {
            if (entry.directory && entry.name.endsWith(".r2c-chunks")) {
                val chunks = value.tree.list("$path/${entry.name}").filter { !it.directory && it.name.matches(Regex("[0-9]{16}_[a-f0-9]{64}")) }.sortedBy { it.name }
                val target = File(directory, entry.name.removeSuffix(".r2c-chunks"))
                var offset = 0L
                FileOutputStream(target).use { out ->
                    for (chunk in chunks) {
                        check(chunk.name.substringBefore('_').toLong() == offset) { "Incomplete retained upload checkpoint." }
                        val bytes = value.tree.read("$path/${entry.name}/${chunk.name}").use { VerifiedArchiveTree.bytes(it, 1024 * 1024) }
                        check(VerifiedArchiveTree.digest(bytes.inputStream()) == chunk.name.substringAfter('_'))
                        out.write(bytes); offset += bytes.size
                    }
                    out.fd.sync()
                }
                val header = "$path/${target.name}.r2c-header"
                if (value.tree.exists(header)) {
                    val metadata = value.tree.read(header).use { VerifiedArchiveTree.json(it, 16 * 1024) }
                    val hash = metadata.getString("sha256")
                    check(hash.matches(Regex("[a-f0-9]{64}")))
                    val bytes = value.tree.read("$header-$hash").use { VerifiedArchiveTree.bytes(it, 64 * 1024) }
                    check(bytes.size <= 64 * 1024 && bytes.size <= target.length())
                    check(VerifiedArchiveTree.digest(bytes.inputStream()) == hash) { "Recording header verification failed." }
                    java.io.RandomAccessFile(target, "rw").use { it.write(bytes); it.fd.sync() }
                }
                target.setLastModified(chunks.maxOfOrNull { it.modified } ?: entry.modified)
                continue
            }
            if (entry.name.endsWith(".r2c-info") || entry.name.contains(".r2c-")) continue
            val relative = "$path/${entry.name}"
            if (!entry.directory && value.tree.exists("$relative.r2c-chunks")) continue
            val target = File(directory, entry.name)
            if (entry.directory) { check(target.isDirectory || target.mkdirs()); hydrate(value, relative, target, reuse, scratch, checkCurrent) }
            else {
                if (reuse) {
                    VerifiedProjectionFile.restore(target, value.migrated[relative], requireNotNull(scratch),
                        openRetained = { value.tree.read(relative) },
                        modifiedForHash = { hash ->
                            val meta = if (value.tree.exists("$relative.r2c-info")) value.tree.read("$relative.r2c-info").use { VerifiedArchiveTree.json(it) } else null
                            if (meta?.optString("sha256") == hash) meta.optLong("modified", entry.modified) else entry.modified
                        }, checkCurrent = checkCurrent)
                    continue
                }
                FileOutputStream(target).use { out -> value.tree.read(relative).use { it.copyTo(out, 256 * 1024) }; out.fd.sync() }
                val localHash = target.inputStream().use { VerifiedArchiveTree.digest(it) }
                check(localHash == value.tree.read(relative).use { VerifiedArchiveTree.digest(it) }) { "Temporary workspace verification failed." }
                val meta = if (value.tree.exists("$relative.r2c-info")) value.tree.read("$relative.r2c-info").use { VerifiedArchiveTree.json(it) } else null
                target.setLastModified(if (meta?.optString("sha256") == localHash) meta.optLong("modified", entry.modified) else entry.modified)
            }
        }
    }
    private fun mapping(file: File, value: Store): Pair<Area, String>? {
        val path = file.absoluteFile.toPath().normalize()
        for (area in value.areas) {
            for (base in listOf(area.alias, File(value.work, area.id))) {
                val parent = base.absoluteFile.toPath().normalize()
                if (path.startsWith(parent)) return area to parent.relativize(path).toString().replace(File.separatorChar, '/')
            }
        }
        return null
    }
    fun retain(file: File) {
        preparedRecorder?.takeIf { mapping(file, it.value) != null }?.let {
            onRecordingStore(file) { value ->
                val (area, relative) = requireNotNull(mapping(file, value))
                if (relative.isNotEmpty() && allowed(area, relative)) commit(value, file, "${area.retained}/$relative")
            }
            return
        }
        preparedQueue?.takeIf { mapping(file, it.value) != null }?.let { queue ->
            worker()
            synchronized(queueGate) {
                checkQueue(queue)
                val (area, relative) = requireNotNull(mapping(file, queue.value))
                if (relative.isNotEmpty() && allowed(area, relative)) commit(queue.value, file, "${area.retained}/$relative")
                checkQueue(queue)
            }
            return
        }
        val current = store ?: return
        if (mapping(file, current) == null) return
        worker()
        synchronized(gate) {
            val value = store ?: return
            kotlin.check(state.value.ready) { state.value.message.ifEmpty { "Archive Dir is unavailable." } }
            val (area, relative) = mapping(file, value) ?: return
            // Multi-file map products publish a verified generation, not individual
            // partially updated GLB/metadata/tile files.
            if (area.id in listOf("layers", "processing") && file.name != "work-owner.json") return
            if (relative.isNotEmpty() && allowed(area, relative)) commit(value, file, "${area.retained}/$relative")
            kotlin.check(state.value.ready) { "Archive Dir changed during the save. Retained copies were kept; restart the app." }
        }
    }
    fun retainTree(directory: File, check: () -> Unit = {}) {
        worker()
        preparedQueue?.takeIf { mapping(directory, it.value) != null }?.let {
            synchronized(queueGate) {
                checkQueue(it)
                for (file in directory.walkTopDown().filter { file -> file.isFile }) { check(); retain(file) }
            }
            return
        }
        val current = store ?: return
        kotlin.check(state.value.ready) { state.value.message.ifEmpty { "Archive Dir is unavailable." } }
        if (!directory.exists()) return
        val mapped = mapping(directory, current) ?: return
        if (mapped.first.id in listOf("layers", "processing") && directory.isDirectory) {
            synchronized(gate) {
                val value = requireNotNull(store)
                val (area, relative) = mapped
                require(relative.isNotBlank())
                val path = "${area.retained}/$relative"
                val revision = UUID.randomUUID().toString()
                val prior = if (value.tree.exists("$path/.r2c-current")) value.tree.read("$path/.r2c-current").use { VerifiedArchiveTree.json(it).getString("revision") } else null
                val entries = org.json.JSONArray()
                val root = directory.canonicalFile.toPath()
                for (file in directory.walkTopDown().filter { it.isFile }) {
                    check()
                    require(file.canonicalFile.toPath().startsWith(root)) { "Unexpected link in map products." }
                    val name = file.relativeTo(directory).invariantSeparatorsPath
                    // Raw image/CPU buffers are scratch, not resumable checkpoints.
                    if (area.id == "processing" && file.extension.lowercase() !in setOf("json", "png", "jpg", "jpeg", "tif", "tiff", "kmz", "zip", "glb", "f32", "ply", "txt")) continue
                    value.writer.put(file, "$path/.r2c-versions/$revision/$name", replace = false)
                    entries.put(JSONObject().put("path", name).put("bytes", file.length()).put("modified", file.lastModified())
                        .put("sha256", file.inputStream().use { VerifiedArchiveTree.digest(it, check) }))
                }
                val manifest = JSONObject().put("version", 1).put("revision", revision).put("files", entries).toString().toByteArray()
                val temporary = File.createTempFile("map-generation-", ".json", value.work.parentFile)
                try {
                    FileOutputStream(temporary).use { it.write(manifest); it.fd.sync() }
                    check()
                    value.writer.put(temporary, "$path/.r2c-current")
                } finally { temporary.delete() }
                // Only the previous committed generation is redundant. Interrupted
                // unpublished generations remain visible to recovery management.
                if (prior != null && prior != revision) value.tree.delete("$path/.r2c-versions/${UUID.fromString(prior)}")
                if (prior == null) for (entry in value.tree.list(path).filterNot { it.name.startsWith(".r2c-") }) {
                    value.tree.delete("$path/${entry.name}")
                }
                kotlin.check(state.value.ready) { "Archive Dir changed during map publication. Retained copies were kept; restart the app." }
            }
        } else if (directory.isDirectory) {
            for (child in directory.listFiles() ?: error("Cannot read retained work")) { check(); retainTree(child, check) }
        } else { check(); retain(directory) }
    }
    fun retainChunk(file: File, offset: Long, bytes: ByteArray) {
        onRecordingStore(file) { value ->
            val (area, relative) = mapping(file, value) ?: return@onRecordingStore
            require(bytes.size in 1..(1024 * 1024) && offset >= 0)
            val hash = VerifiedArchiveTree.digest(bytes.inputStream())
            val temporary = File.createTempFile("archive-chunk-", ".part", value.work.parentFile)
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                value.writer.put(temporary, "${area.retained}/$relative.r2c-chunks/${offset.toString().padStart(16, '0')}_$hash", replace = false)
            } finally { temporary.delete() }
        }
    }
    /** Pinned MediaMTX appends fMP4 parts and updates mvhd on close. Keep bounded
     * append checkpoints plus the mutable header outside the disposable workspace. */
    fun retainGrowingRecording(file: File) {
        onRecordingStore(file) { value ->
            if (!file.isFile) return@onRecordingStore
            val (area, relative) = mapping(file, value) ?: return@onRecordingStore
            if (area.id != "unfinished-recordings" || file.extension.lowercase() !in setOf("mp4", "fmp4")) return@onRecordingStore
            val path = "${area.retained}/$relative"
            val chunks = "$path.r2c-chunks"
            var offset = value.growingOffsets.getOrPut(path) {
                var count = 0L
                for (entry in value.tree.list(chunks).sortedBy { it.name }) {
                    check(entry.name.substringBefore('_').toLong() == count)
                    count += entry.bytes
                }
                count
            }
            java.io.RandomAccessFile(file, "r").use { input ->
                val end = input.length()
                check(end >= offset) { "Native recording changed its retained prefix." }
                input.seek(offset)
                while (offset < end) {
                    val bytes = ByteArray(minOf(1024 * 1024L, end - offset).toInt())
                    input.readFully(bytes)
                    retainChunk(file, offset, bytes)
                    offset += bytes.size
                    value.growingOffsets[path] = offset
                }
                if (end > 0) {
                    val header = ByteArray(minOf(64 * 1024L, end).toInt())
                    input.seek(0); input.readFully(header)
                    val verify = ByteArray(header.size)
                    input.seek(0); input.readFully(verify)
                    if (header.contentEquals(verify)) {
                        val hash = VerifiedArchiveTree.digest(header.inputStream())
                        val pointer = "$path.r2c-header"
                        val prior = if (value.tree.exists(pointer)) value.tree.read(pointer).use { VerifiedArchiveTree.json(it, 16 * 1024).getString("sha256") } else null
                        val temp = File.createTempFile("recording-header-", ".bin", value.work.parentFile)
                        try {
                            FileOutputStream(temp).use { it.write(header); it.fd.sync() }
                            value.writer.put(temp, "$pointer-$hash", replace = false)
                            FileOutputStream(temp).use { it.write(JSONObject().put("sha256", hash).toString().toByteArray()); it.fd.sync() }
                            value.writer.put(temp, pointer)
                            if (prior != null && prior != hash) { check(prior.matches(Regex("[a-f0-9]{64}"))); value.tree.delete("$pointer-$prior") }
                        }
                        finally { temp.delete() }
                    }
                }
            }
        }
    }
    fun forgetPending(file: File) {
        val value = store ?: return
        worker()
        synchronized(gate) {
            val (area, relative) = mapping(file, value) ?: return
            require(relative.endsWith(".part"))
            val path = "${area.retained}/$relative"
            value.tree.delete(path); value.tree.delete("$path.r2c-info"); value.tree.delete("$path.r2c-chunks")
        }
    }
    fun requireReady(context: Context) { if (!state.value.ready) prepare(context.applicationContext) }
    fun requireFor(file: File) {
        val context = org.ncssar.rid2caltopo.app.R2CApplication.getAppCtxt() ?: return
        val base = context.applicationInfo.dataDir
        val path = file.absolutePath
        if (path == "$base/files/operational-state/device-marker-cleanup.json") {
            prepareQueue(context)
            return
        }
        if (listOf("mediamtx-recordings", "mediamtx-recordings-merged").any { path == "$base/files/$it" || path.startsWith("$base/files/$it/") }) {
            requireRecorderReady(context)
            return
        }
        val names = listOf("mapping-missions", "mapping-layers", "mapping-processing", "mapping-storage-stats", "map-archives", "clues", "managed-video-session-recordings", "mediamtx-recordings", "mediamtx-recordings-merged", "video-sei-originals", "land-restrictions", "anr-traces", "review-annotations", "operational-state")
        if (names.any { path == "$base/files/$it" || path.startsWith("$base/files/$it/") } ||
            path == "$base/no_backup/dem_blocks_v2" || path == "$base/no_backup/map_cache" ||
            path.startsWith("$base/no_backup/dem_blocks_v2/") || path.startsWith("$base/no_backup/map_cache/")) {
            worker(); requireReady(context)
        }
    }
    fun ready(): Boolean = state.value.ready
    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        worker(); check(org.ncssar.rid2caltopo.BuildConfig.DEBUG)
        startup.submit {}.get()
        synchronized(gate) { synchronized(queueGate) { synchronized(recorderGate) {
            store = null; preparedQueue = null; preparedRecorder = null; recoveredUri = null; state.value = Status()
        } } }
    }
    fun workspace(context: Context): File { requireReady(context); return synchronized(gate) { requireNotNull(store).work } }
    private val queueNames = listOf("awaiting-map-flights.json", "flight-archive-journal.json", "flight-kmz-rewrites.json", "caltopo-interrupted-tracks.json", "device-marker-cleanup.json")
    fun stateFile(context: Context, name: String): File {
        require(name in queueNames)
        // Only marker ownership is released before bulk readiness. Flight/archive recovery
        // consumers can touch other operational areas and retain their original startup barrier.
        if (name == "device-marker-cleanup.json") prepareQueue(context) else requireReady(context)
        return File(context.filesDir, "operational-state/$name")
    }
    @JvmStatic fun archiveChanged(uri: String) {
        selectionGeneration.incrementAndGet()
        store?.let { if (it.uri != uri) state.value = Status(message = "Restart the app after changing Archive Dir.") }
    }
    fun removeIfManaged(file: File): Boolean {
        if (preparedRecorder?.let { mapping(file, it.value) } != null) { remove(file); return true }
        if (preparedQueue?.let { mapping(file, it.value) } != null) { remove(file); return true }
        val value = store
        if (value == null || mapping(file, value) == null) return !file.exists() || file.delete()
        remove(file); return true
    }
    fun clearSupportData(context: Context): Long {
        worker()
        synchronized(gate) {
            val value = store
            check(!state.value.busy) { "Wait for storage preparation to finish." }
            val root = File(context.filesDir, "land-restrictions")
            var removed = 0L
            for (file in root.listFiles().orEmpty()) {
                val bytes = file.length()
                if (value != null) remove(file) else check(file.delete())
                removed += bytes
            }
            return removed
        }
    }
    fun clearRecovery(): Long {
        worker()
        synchronized(gate) {
            val value = store ?: error("Reconnect Archive Dir and finish storage preparation first.")
            kotlin.check(state.value.ready) { "Reconnect Archive Dir and finish storage preparation first." }
            val bulk = clearRecoveryArea(value, VerifiedArchiveTree.JOURNAL, value.areas.filterNot { it.id == "queue-state" || it.id in recorderAreas })
            val queue = synchronized(queueGate) {
                preparedQueue?.let { current ->
                    checkQueue(current)
                    current.value.tree.refresh()
                    clearRecoveryArea(current.value, QUEUE_JOURNAL, current.value.areas)
                } ?: 0L
            }
            val recorder = synchronized(recorderGate) {
                preparedRecorder?.let { current ->
                    checkRecorder(current); current.value.tree.refresh()
                    clearRecoveryArea(current.value, RECORDER_JOURNAL, current.value.areas)
                } ?: 0L
            }
            return bulk + queue + recorder
        }
    }
    private fun clearRecoveryArea(value: Store, journalDirectory: String, areas: List<Area>): Long {
        check(value.tree.list(journalDirectory).none { it.name.endsWith(".json") }) {
            "Restart the app to finish pending recovery before clearing older recovery copies."
        }
        var removed = 0L
        fun bytes(path: String): Long = value.tree.list(path).sumOf { e -> if (e.directory) bytes("$path/${e.name}") else e.bytes }
        fun walk(path: String) {
            for (entry in value.tree.list(path)) {
                val next = "$path/${entry.name}"
                if (entry.name == ".r2c-versions") {
                    val pointer = "$path/.r2c-current"
                    val active = if (value.tree.exists(pointer)) value.tree.read(pointer).use { VerifiedArchiveTree.json(it).getString("revision") } else null
                    for (revision in value.tree.list(next)) if (revision.name != active) { removed += bytes("$next/${revision.name}"); value.tree.delete("$next/${revision.name}") }
                } else if (entry.directory) walk(next)
                else if (entry.name.contains(".r2c-stage-") || entry.name.contains(".r2c-previous-") || entry.name.contains(".r2c-interrupted-")) {
                    removed += entry.bytes; value.tree.delete(next)
                }
            }
        }
        for (area in areas) walk(area.retained)
        for (entry in value.tree.list(journalDirectory).filter { it.name.endsWith(".invalid") }) {
            removed += entry.bytes; value.tree.delete("${journalDirectory}/${entry.name}")
        }
        return removed
    }

    /** Durable deletion intent prevents an interrupted deletion reappearing from old workspace bytes. */
    fun remove(file: File) {
        worker()
        preparedRecorder?.takeIf { mapping(file, it.value) != null }?.let { current ->
            synchronized(recorderGate) { checkRecorder(current); removeOnStore(current.value, file); checkRecorder(current) }
            return
        }
        preparedQueue?.takeIf { mapping(file, it.value) != null }?.let { current ->
            synchronized(queueGate) { checkQueue(current); removeOnStore(current.value, file); checkQueue(current) }
            return
        }
        synchronized(gate) {
            val value = store ?: error("Archive storage is not prepared")
            kotlin.check(state.value.ready) { state.value.message.ifEmpty { "Archive Dir is unavailable." } }
            removeOnStore(value, file)
        }
    }
    private fun removeOnStore(value: Store, file: File) {
        value.writer.recover()
        val (area, relative) = mapping(file, value) ?: error("Unmanaged deletion target")
        check(relative.isNotBlank()) { "Whole-category deletion requires selecting its contents." }
        val target = "${area.retained}/$relative"
        val id = UUID.randomUUID().toString()
        val record = JSONObject().put("path", target).put("area", area.id).put("relative", relative).toString().toByteArray()
        val journal = "application-logs/storage-deletions/$id.json"
        value.tree.write(journal, record.inputStream())
        check(value.tree.read(journal).use { it.readBytes().contentEquals(record) })
        value.tree.delete(target)
        if (value.tree.exists("$target.r2c-chunks")) value.tree.delete("$target.r2c-chunks")
        forgetHeader(value, target)
        value.growingOffsets.remove(target)
        if (value.tree.exists("$target.r2c-info")) value.tree.delete("$target.r2c-info")
        removeTree(file)
        value.tree.delete(journal)
    }
    private fun recoverDeletions(value: Store) {
        val root = "application-logs/storage-deletions"
        for (entry in value.tree.list(root)) {
            val record = value.tree.read("$root/${entry.name}").use { VerifiedArchiveTree.json(it) }
            val area = value.areas.single { it.id == record.getString("area") }
            val relative = VerifiedArchiveTree.checked(record.getString("relative"))
            val target = "${area.retained}/$relative"
            check(target == record.getString("path"))
            value.tree.delete(target)
            if (value.tree.exists("$target.r2c-chunks")) value.tree.delete("$target.r2c-chunks")
            forgetHeader(value, target)
            if (value.tree.exists("$target.r2c-info")) value.tree.delete("$target.r2c-info")
            removeTree(File(value.work, "${area.id}/$relative"))
            value.tree.delete("$root/${entry.name}")
        }
    }
    private fun removeTree(root: File) {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
    private fun forgetHeader(value: Store, path: String) {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/') + ".r2c-header"
        for (entry in value.tree.list(parent).filter { it.name == name || it.name.startsWith("$name-") }) {
            value.tree.delete(if (parent.isEmpty()) entry.name else "$parent/${entry.name}")
        }
    }
}
