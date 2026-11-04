package org.ncssar.rid2caltopo.video

import org.json.JSONArray
import org.ncssar.rid2caltopo.app.isDatedArchiveDirectoryName
import org.ncssar.rid2caltopo.app.todayArchiveDirectoryName
import java.io.File
import java.util.Date

/**
 * Per-flight clue folder layout under the clues root (`files/clues`):
 *
 * ```
 * <flightId>/clues.json
 * <flightId>/<clueId>.jpg
 * <flightId>/<clueId>-thumb.jpg
 * deleted-clue-ids.json        (root-level discard tombstones)
 * ```
 *
 * Legacy `tracks-ddMMMyyyy/` day folders and a flat root `clues.json` are migration sources only.
 */
internal object AndroidClueFlightFolders {
    const val INDEX_FILENAME = "clues.json"

    /** Folder name for a flight id: filesystem-safe and never a legacy `tracks-…` day name. */
    fun folderNameFor(flightId: String): String {
        val trimmed = flightId.trim()
        require(trimmed.isNotEmpty()) { "Flight id is required" }
        var safe = trimmed.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }
            .joinToString("")
        if (safe.all { it == '_' }) safe = "flight$safe"
        if (isDatedArchiveDirectoryName(safe)) safe = "flight-$safe"
        return safe
    }

    /** True for a directory name this layout may hold (not hidden, not a legacy day folder). */
    fun isFlightFolderName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith('.') && !isDatedArchiveDirectoryName(name)

    /** Non-hidden flight directories directly under [root]. */
    fun flightFolders(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && isFlightFolderName(it.name) }.orEmpty()

    /** Legacy day directories directly under [root]. */
    fun legacyDayFolders(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && isDatedArchiveDirectoryName(it.name) }.orEmpty()

    /** Every directory (flight or legacy day) that may carry a `clues.json`. */
    fun indexedFolders(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }.orEmpty()

    /**
     * Archive day (`tracks-ddMMMyyyy`) a flight folder belongs to: the earliest flight start /
     * ownership time of its clues. Null when the index is missing or unreadable.
     */
    fun dayOf(folder: File): String? {
        val index = File(folder, INDEX_FILENAME)
        if (!index.isFile) return null
        var earliest = Long.MAX_VALUE
        try {
            val array = JSONArray(index.readText())
            for (i in 0 until array.length()) {
                val record = array.optJSONObject(i)?.toAndroidClueRecord() ?: continue
                val time = record.binding?.flightStartMs?.takeIf { it > 0L } ?: record.ownershipTimeMs
                if (time > 0L && time < earliest) earliest = time
            }
        } catch (_: Exception) {
            return null
        }
        return if (earliest == Long.MAX_VALUE) null else todayArchiveDirectoryName(Date(earliest))
    }

    /** Flight folder name → owning archive day, for folders whose index is readable. */
    fun flightDays(root: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (folder in flightFolders(root)) dayOf(folder)?.let { out[folder.name] = it }
        return out
    }

    /** First path segment of [file] below [root] when it sits in a flight folder, else null. */
    fun flightFolderNameOf(root: File, file: File): String? {
        val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: return null
        if (relative.startsWith("..") || !relative.contains('/')) return null
        val first = relative.substringBefore('/')
        return first.takeIf { isFlightFolderName(it) }
    }
}
