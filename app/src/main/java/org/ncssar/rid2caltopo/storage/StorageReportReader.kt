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
