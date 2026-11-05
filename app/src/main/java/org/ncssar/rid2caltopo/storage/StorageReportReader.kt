package org.ncssar.rid2caltopo.storage

import android.content.Context
import android.os.Looper
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** One metadata-only inventory covering archive data, private operational roots and scratch. */
internal fun readStorageCategoryReport(context: Context, privateDataIsTemporary: Boolean, cancelled: () -> Unit = {}): StorageCategoryReport {
    check(Looper.myLooper() != Looper.getMainLooper())
    val totals = StorageCategory.entries.associateWith { 0L }.toMutableMap()
    val seen = HashSet<String>()
    var complete = true
    fun fileTree(file: File, category: StorageCategory) {
        cancelled()
        try {
            if (!file.exists()) return
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val identity = attributes.fileKey()?.toString() ?: file.canonicalPath
            if (!seen.add(identity)) return
            if (attributes.isDirectory) {
                for (child in file.listFiles() ?: error("Directory unavailable")) fileTree(child, category)
            } else if (attributes.isRegularFile) totals[category] = totals.getValue(category) + attributes.size()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { complete = false }
    }
    val archive = CaltopoClient.GetArchiveDir()
    if (archive == null) complete = false
    else {
        val tree = DocumentArchiveTree(context, archive)
        fun walk(path: String, recovery: Boolean = false) {
            cancelled()
            for (entry in tree.list(path)) {
                val next = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                if (entry.directory && entry.name == ".r2c-versions") {
                    val pointer = "$path/.r2c-current"
                    val active = if (tree.exists(pointer)) tree.read(pointer).use { VerifiedArchiveTree.json(it).getString("revision") } else null
                    for (revision in tree.list(next)) if (revision.directory) walk("$next/${revision.name}", revision.name != active)
                } else if (entry.directory) walk(next, recovery)
                else {
                    val kind = if (recovery) StorageCategory.RECOVERY else storageCategory(next)
                    totals[kind] = totals.getValue(kind) + entry.bytes
                }
            }
        }
        try { walk("") } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) { complete = false }
    }
    // Archive-side mapping (`mapping/{missions,layers,processing,stats}`, plus the pre-relocation
    // `cache/local-maps/mapping-*`) is categorized by storageCategory() above as projects/layers/
    // checkpoints/metadata. These private aliases/projections use the same categories and are
    // de-duplicated by file identity; mapping is never counted as Cache imagery.
    val mapping = mapOf("mapping-missions" to StorageCategory.PROJECTS, "mapping-layers" to StorageCategory.LAYERS,
        "mapping-processing" to StorageCategory.CHECKPOINTS, "mapping-storage-stats" to StorageCategory.METADATA,
        "clues" to StorageCategory.CLUES, "managed-video-session-recordings" to StorageCategory.RECORDINGS,
        "mediamtx-recordings" to StorageCategory.RECORDINGS, "mediamtx-recordings-merged" to StorageCategory.TEMPORARY,
        "video-sei-originals" to StorageCategory.RECORDINGS, "review-annotations" to StorageCategory.ANNOTATIONS,
        "land-restrictions" to StorageCategory.SUPPORT, "anr-traces" to StorageCategory.LOGS,
        "operational-state" to StorageCategory.METADATA, "map-archives" to StorageCategory.IMAGERY)
    for ((name, category) in mapping) fileTree(File(context.filesDir, name), if (privateDataIsTemporary) StorageCategory.TEMPORARY else category)
    fileTree(File(context.noBackupFilesDir, "dem_blocks_v2"), if (privateDataIsTemporary) StorageCategory.TEMPORARY else StorageCategory.DECODED)
    fileTree(File(context.noBackupFilesDir, "map_cache"), if (privateDataIsTemporary) StorageCategory.TEMPORARY else StorageCategory.METADATA)
    for (volume in context.getExternalFilesDirs(null).filterNotNull()) {
        for ((name, category) in mapping.filterKeys { it.startsWith("mapping-") })
            fileTree(File(volume, "local-mapping/$name"), category)
        fileTree(File(volume, "osmdroid"), StorageCategory.IMAGERY)
    }
    for (root in TemporaryStorage.roots(context)) fileTree(root, StorageCategory.TEMPORARY)
    // Private settings/sign-in/security and SDK installation state are excluded
    // from operator storage management by the owner's explicit preference.
    return StorageCategoryReport(totals, complete)
}

/** Coalesces Manage Storage breakdown readers. Serves the last report inside [TTL_MS]. */
internal object StorageCategoryCache {
    const val TTL_MS = 60_000L
    private val gate = Any()
    private var report: StorageCategoryReport? = null
    private var at = 0L
    private var inflight: java.util.concurrent.CompletableFuture<StorageCategoryReport>? = null

    fun peek(): StorageCategoryReport? = synchronized(gate) {
        val current = report ?: return null
        val age = android.os.SystemClock.elapsedRealtime() - at
        if (age in 0..TTL_MS) current else null
    }

    fun read(context: android.content.Context, force: Boolean, cancelled: () -> Unit = {}): StorageCategoryReport {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        if (!force) peek()?.let {
            org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("StorageCategories", "breakdown reason=cache elapsedMs=0")
            return it
        }
        val created = java.util.concurrent.CompletableFuture<StorageCategoryReport>()
        val leader = synchronized(gate) {
            inflight?.let { return@synchronized it }
            inflight = created
            created
        }
        if (leader !== created) {
            val joined = leader.get(5, java.util.concurrent.TimeUnit.MINUTES)
            org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("StorageCategories", "breakdown reason=cache elapsedMs=0")
            return joined
        }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            if (!force) peek()?.let { created.complete(it); return it }
            val value = readStorageCategoryReport(context, OperationalStorage.ready(), cancelled)
            synchronized(gate) { report = value; at = android.os.SystemClock.elapsedRealtime() }
            val reason = if (force) "stale" else "cold"
            org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug(
                "StorageCategories",
                "breakdown reason=$reason elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}",
            )
            created.complete(value)
            return value
        } catch (error: Throwable) {
            created.completeExceptionally(error)
            throw error
        } finally {
            synchronized(gate) { if (inflight === created) inflight = null }
        }
    }
}
