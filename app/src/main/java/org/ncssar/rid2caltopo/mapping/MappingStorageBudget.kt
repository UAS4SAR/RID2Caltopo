package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.os.Looper
import android.os.storage.StorageManager
import java.io.File
import java.util.Locale

/**
 * Free-space helpers for mapping IO workers only.
 * Callers must not invoke these from the main thread; StatFs/usableSpace are filesystem IO.
 */
internal object MappingStorageBudget {
    fun gb(bytes: Long): String = String.format(Locale.US, "%.2f", bytes / 1_000_000_000.0)

    fun message(bucket: String, label: String, needBytes: Long, freeBytes: Long, reserveBytes: Long): String =
        "$bucket ($label): need ${gb(needBytes)} GB, free ${gb(freeBytes)} GB, reserve ${gb(reserveBytes)} GB"

    /** Volume label for operator-facing storage errors. Prefer StorageManager UUID when available. */
    fun label(path: File): String {
        val resolved = path.absoluteFile
        val context = runCatching { org.ncssar.rid2caltopo.app.R2CApplication.getAppCtxt() }.getOrNull()
        if (context != null) {
            val manager = context.getSystemService(StorageManager::class.java)
            val volume = manager?.getStorageVolume(resolved)
                ?: generateSequence(resolved.parentFile) { it.parentFile }
                    .mapNotNull { manager?.getStorageVolume(it) }.firstOrNull()
            if (volume != null) {
                val uuid = volume.uuid
                if (!volume.isPrimary || volume.isRemovable) {
                    val tag = (uuid ?: volumeDirectoryName(volume) ?: resolved.name)
                        .filter { it.isLetterOrDigit() }.take(4).uppercase(Locale.US)
                    if (tag.isNotEmpty()) return "SD $tag"
                }
                if (volume.isPrimary && !volume.isRemovable) return "Tablet"
            }
        }
        return labelFromPath(resolved)
    }

    fun label(context: Context, path: File): String {
        val manager = context.getSystemService(StorageManager::class.java)
        val resolved = path.absoluteFile
        val volume = manager?.getStorageVolume(resolved)
            ?: generateSequence(resolved.parentFile) { it.parentFile }
                .mapNotNull { manager?.getStorageVolume(it) }.firstOrNull()
        if (volume != null) {
            val uuid = volume.uuid
            if (!volume.isPrimary || volume.isRemovable) {
                val tag = (uuid ?: volumeDirectoryName(volume) ?: resolved.name)
                    .filter { it.isLetterOrDigit() }.take(4).uppercase(Locale.US)
                if (tag.isNotEmpty()) return "SD $tag"
            }
            if (volume.isPrimary && !volume.isRemovable) return "Tablet"
        }
        return labelFromPath(resolved)
    }

    /**
     * Resolve to an existing directory on the same volume.
     * Allows a not-yet-created child of an existing parent; never calls usableSpace on a missing path
     * (that returns 0 and was misread as "full").
     */
    fun existingDirectory(path: File): File {
        val resolved = path.absoluteFile
        if (resolved.isDirectory) return resolved
        if (resolved.isFile) {
            val parent = resolved.parentFile
            if (parent != null && parent.isDirectory) return parent
        }
        // Immediate parent only — do not walk to `/` and pretend a deleted scratch still exists.
        val parent = resolved.parentFile
        if (parent != null && parent.isDirectory) return parent
        throw IllegalStateException(
            "Mapping storage path does not exist (programming error); cannot measure free space: ${resolved.path}"
        )
    }

    fun freeBytes(path: File): Long {
        checkOffMain()
        return existingDirectory(path).usableSpace
    }

    fun requireFree(
        path: File,
        needBytes: Long,
        reserveBytes: Long = MappingPhotoStore.FREE_SPACE_RESERVE,
        bucket: String,
    ) {
        checkOffMain()
        val free = freeBytes(path)
        val label = label(path)
        if (free - needBytes < reserveBytes) {
            throw IllegalStateException(message(bucket, label, needBytes, free, reserveBytes))
        }
    }

    fun checkExisting(
        path: File,
        reserveBytes: Long = MappingPhotoStore.FREE_SPACE_RESERVE,
        bucket: String = "Mapping work space",
    ) {
        requireFree(path, needBytes = 0L, reserveBytes = reserveBytes, bucket = bucket)
    }

    private fun labelFromPath(path: File): String {
        val parts = path.invariantSeparatorsPath.split('/')
        val idx = parts.indexOf("storage")
        if (idx >= 0 && idx + 1 < parts.size) {
            val vol = parts[idx + 1]
            if (vol != "emulated" && vol != "self") {
                val tag = vol.filter { it.isLetterOrDigit() }.take(4).uppercase(Locale.US)
                if (tag.isNotEmpty()) return "SD $tag"
            }
        }
        val media = parts.indexOf("media_rw")
        if (media >= 0 && media + 1 < parts.size) {
            val tag = parts[media + 1].filter { it.isLetterOrDigit() }.take(4).uppercase(Locale.US)
            if (tag.isNotEmpty()) return "SD $tag"
        }
        return "Tablet"
    }

    private fun volumeDirectoryName(volume: android.os.storage.StorageVolume): String? =
        runCatching { volume.directory?.name }.getOrNull()

    private fun checkOffMain() {
        val main = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
        check(Looper.myLooper() != main) {
            "Mapping storage free-space checks belong on an IO worker."
        }
    }
}
