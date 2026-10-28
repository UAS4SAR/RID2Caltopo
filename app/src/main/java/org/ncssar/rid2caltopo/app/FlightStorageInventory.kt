package org.ncssar.rid2caltopo.app

import org.ncssar.rid2caltopo.storage.ArchiveTree

internal data class FlightArchiveDayInventory(
    val name: String,
    val modified: Long,
    val entries: List<ArchiveCleanupFileEntry>,
    val fileSizes: Map<String, Long>,
) {
    val bytes: Long get() = fileSizes.values.sum()
}

/** Metadata only: one listing per directory, with no content reads or storage preparation. */
internal fun readFlightArchiveInventory(
    tree: ArchiveTree,
    cancelled: () -> Unit = {},
): List<FlightArchiveDayInventory> {
    cancelled()
    return tree.list("").filter { it.directory && isDatedArchiveDirectoryName(it.name) }.map { day ->
        val sizes = linkedMapOf<String, Long>()
        fun entries(path: String): List<ArchiveCleanupFileEntry> {
            cancelled()
            return tree.list(path).map { entry ->
                cancelled()
                val child = "$path/${entry.name}"
                if (!entry.directory) {
                    check(entry.bytes >= 0) { "Storage provider did not report a valid file size: $child" }
                    sizes[child] = entry.bytes
                }
                ArchiveCleanupFileEntry(entry.name, entry.mimeType, entry.directory,
                    if (entry.directory) 0 else entry.bytes.coerceAtLeast(0),
                    if (entry.directory) entries(child) else emptyList())
            }
        }
        val contents = entries(day.name)
        FlightArchiveDayInventory(day.name, day.modified, contents, sizes)
    }
}
