package org.ncssar.rid2caltopo.storage

import android.content.Context
import android.os.Looper
import java.io.File
import java.util.UUID
import android.provider.DocumentsContract
import android.net.Uri
import android.os.storage.StorageManager

/** Process-scoped scratch. Old invocations are cleaned before this invocation can create conflicts. */
internal object TemporaryStorage {
    private val invocation = UUID.randomUUID().toString()
    fun roots(context: Context): List<File> = (listOf(context.cacheDir) + context.externalCacheDirs.filterNotNull()).distinctBy { it.absolutePath }
    /** Prefer scratch on the archive's volume; SAF still owns the permanent copies. */
    fun workspaceRoot(context: Context, archive: Uri): File {
        check(Looper.myLooper() != Looper.getMainLooper())
        val volume = if (archive.authority == "com.android.externalstorage.documents")
            runCatching { DocumentsContract.getTreeDocumentId(archive).substringBefore(':') }.getOrNull() else null
        val manager = context.getSystemService(StorageManager::class.java)
        val candidates = roots(context).filter { it.isDirectory || it.mkdirs() }
        return candidates.firstOrNull { folder ->
            val storage = manager?.getStorageVolume(folder)
            volume != null && (storage?.uuid.equals(volume, true) || (volume == "primary" && storage?.isPrimary == true))
        } ?: candidates.firstOrNull() ?: error("No temporary filesystem is available.")
    }
    fun directory(context: Context, kind: String): File {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(kind.matches(Regex("[a-z0-9-]+")))
        return File(context.cacheDir, "temporary-operational/$invocation/$kind").apply { check(isDirectory || mkdirs()) }
    }
    fun directory(context: Context, kind: String, archive: Uri): File {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(kind.matches(Regex("[a-z0-9-]+")))
        return File(workspaceRoot(context, archive), "temporary-operational/$invocation/$kind")
            .apply { check(isDirectory || mkdirs()) }
    }
    fun cleanAbandoned(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper())
        roots(context).flatMap { File(it, "temporary-operational").listFiles().orEmpty().toList() }.filter { it.name != invocation }.forEach {
            check(it.deleteRecursively()) { "Abandoned temporary work could not be removed." }
        }
        // These earlier scratch writers now use this invocation's namespace.
        for (name in listOf("shared_clues", "clue-snapshots", "ma-transfer", "temporary-archive")) {
            File(context.cacheDir, name).let { if (it.exists()) check(it.deleteRecursively()) }
        }
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("R2C_Logs_") || it.name.startsWith("map-package-") || it.name.startsWith("aol-import-") }.forEach { check(it.deleteRecursively()) }
        context.filesDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("mapping-preview-") }.forEach { check(it.deleteRecursively()) }
    }
}
