package org.ncssar.rid2caltopo.video.surface

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** What we know about a lidar file that finished downloading: written after the atomic rename. */
internal data class RetainedSourceRecord(val url: String, val bytes: Long, val sha256: String) {
    fun encode(): String = "v1\n$url\n$bytes\n$sha256\n"

    companion object {
        fun decode(text: String?): RetainedSourceRecord? {
            val lines = text?.trimEnd('\n')?.split('\n') ?: return null
            if (lines.size != 4 || lines[0] != "v1") return null
            val bytes = lines[2].toLongOrNull() ?: return null
            if (!SHA256_HEX.matches(lines[3])) return null
            return RetainedSourceRecord(lines[1], bytes, lines[3])
        }
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/**
 * Pure rules for keeping lidar files between AOL attempts. iOS mirrors this in
 * OperationalSurfaceSourceReuse (R2CCore); keep the two in step.
 */
internal object SurfaceSourceReuse {
    const val WORK_PREFIX = "aol-prep-"
    const val PARTIAL_SUFFIX = ".part"
    private const val MAX_SOURCE_BYTES = 1_000_000_000L

    /** Same AOL selection (bounds and exact source list) gives the same key; anything else differs. */
    fun workKey(plan: SurfacePreparationPlan): String {
        val b = plan.bounds
        val canonical = buildString {
            append("v1|")
            append(String.format(Locale.US, "%.7f,%.7f,%.7f,%.7f", b.west, b.south, b.east, b.north))
            plan.sources.sortedBy { it.url }.forEach { append('|').append(it.url).append(',').append(it.bytes) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun workDirectoryName(plan: SurfacePreparationPlan): String = WORK_PREFIX + workKey(plan)

    /**
     * A kept file is reused only if its record matches this source, the file on disk is exactly the
     * size the server declared when it finished downloading, and its SHA-256 still matches.
     */
    fun reusable(source: SurfaceSource, record: RetainedSourceRecord?, fileLength: Long?, computedSha256: String?): Boolean =
        record != null && record.url == source.url &&
            record.bytes in 1..MAX_SOURCE_BYTES && fileLength == record.bytes &&
            computedSha256 != null && computedSha256 == record.sha256

    /** Work folders to delete: every AOL work folder except the one for [keepName] (null deletes all). */
    fun staleWorkDirectories(names: List<String>, keepName: String?): List<String> =
        names.filter { it.startsWith(WORK_PREFIX) && it != keepName }

    /**
     * Deletes AOL work folders (raw lidar, partial files, records) directly under [root], except
     * [keepName]; other cache folders are left alone. Returns how many were deleted. Call off the main thread.
     */
    fun deleteWorkDirectories(root: File, keepName: String? = null): Int {
        val dirs = root.listFiles().orEmpty().filter { it.isDirectory }
        val stale = staleWorkDirectories(dirs.map { it.name }, keepName).toSet()
        return dirs.filter { it.name in stale }.count { it.deleteRecursively() }
    }
}
