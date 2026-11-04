package org.ncssar.rid2caltopo.storage

import android.content.Context
import android.os.Looper
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.PlainFileSafeWriter
import java.io.File

/**
 * Locates clue JPEGs that survived relocation or cleanup in a prior working root, and
 * copies them into the current durable clues directory when found.
 */
object CluePhotoRecovery {
    private const val TAG = "CluePhotoRecovery"

    /** Prior working roots that may still hold clue photos after Archive Dir / cache moves. */
    @JvmStatic
    fun candidateRoots(context: Context, preferredRoot: File): List<File> {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "CluePhotoRecovery must run off Main"
        }
        val roots = linkedSetOf<File>()
        roots.add(preferredRoot)
        roots.add(File(context.filesDir, "clues"))
        for (cacheRoot in TemporaryStorage.roots(context)) {
            File(cacheRoot, "operational-work").listFiles().orEmpty()
                .filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{16}")) }
                .forEach { roots.add(File(it, "clues")) }
            File(cacheRoot, "temporary-operational").listFiles().orEmpty()
                .filter { it.isDirectory }
                .forEach { roots.add(File(it, "clues")) }
        }
        return roots.filter { it.isDirectory }.distinctBy { file ->
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        }
    }

    /** First existing non-empty file matching [filename] under known prior clue roots. */
    @JvmStatic
    fun findPhoto(context: Context, filename: String, preferredRoot: File): File? {
        val name = sanitizeRelativeFilename(filename) ?: return null
        return candidateRoots(context, preferredRoot)
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.length() > 0L }
            // Also try basename under each root (legacy flat layout).
            ?: run {
                val base = name.substringAfterLast('/')
                if (base == name) null
                else candidateRoots(context, preferredRoot)
                    .map { File(it, base) }
                    .firstOrNull { it.isFile && it.length() > 0L }
            }
    }

    /**
     * When the preferred root is missing [filename], copy a recovered photo into it and
     * schedule Archive Dir retain. Returns the durable file, or null when not found.
     */
    @JvmStatic
    fun adoptIfFound(context: Context, filename: String, preferredRoot: File): File? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "CluePhotoRecovery must run off Main"
        }
        val name = sanitizeRelativeFilename(filename) ?: return null
        preferredRoot.mkdirs()
        val destination = File(preferredRoot, name)
        destination.parentFile?.mkdirs()
        if (destination.isFile && destination.length() > 0L) return destination
        val found = findPhoto(context, name, preferredRoot) ?: return null
        if (runCatching { found.canonicalFile == destination.canonicalFile }.getOrDefault(false)) {
            return found
        }
        PlainFileSafeWriter.replace(destination, found.readBytes()) { it.isNotEmpty() }
        OperationalStorage.retainLater(destination)
        CaltopoClient.CTInfo(TAG, "Adopted clue photo into durable root name=$name")
        return destination
    }

    /** Allow `file.jpg` or `tracks-ddMMMyyyy/file.jpg`; reject traversal. */
    private fun sanitizeRelativeFilename(filename: String): String? {
        val name = filename.trim().replace('\\', '/')
        if (name.isEmpty() || name.startsWith("/") || name.contains("..")) return null
        val parts = name.split('/')
        if (parts.size > 2) return null
        if (parts.any { it.isBlank() }) return null
        return name
    }
}
