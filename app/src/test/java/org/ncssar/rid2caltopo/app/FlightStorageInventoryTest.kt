package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.storage.ArchiveTree
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class FlightStorageInventoryTest {
    private class MetadataTree(val listings: Map<String, List<ArchiveTree.Entry>>) : ArchiveTree {
        val queried = mutableListOf<String>()
        override fun list(path: String): List<ArchiveTree.Entry> {
            queried += path
            return listings[path] ?: throw IOException("Listing unavailable: $path")
        }
        override fun exists(path: String): Boolean = error("No per-file metadata requests")
        override fun read(path: String): InputStream = error("No file content reads or verification")
        override fun write(path: String, input: InputStream): Unit = error("No writes")
        override fun mkdirs(path: String): Unit = error("No writes")
        override fun rename(from: String, to: String): Boolean = error("No writes")
        override fun delete(path: String): Unit = error("No deletions")
    }
    private fun dir(name: String) = ArchiveTree.Entry(name, true, 0, 100)
    private fun file(name: String, bytes: Long, mime: String? = null) = ArchiveTree.Entry(name, false, bytes, 100, mime)

    @Test fun thousandsOfFlightFilesNeedOnlyOneListingPerDirectory() {
        val day = "tracks-28Oct2026"
        val tree = MetadataTree(mapOf("" to listOf(dir(day), dir("cache"), dir("application-logs")),
            day to List(10_000) { file("video-$it.mp4", 5_000_000L) }))
        val result = readFlightArchiveInventory(tree)
        assertEquals(50_000_000_000L, result.single().bytes)
        assertEquals(listOf("", day), tree.queried)
        assertEquals(10_000, result.single().fileSizes.size)
    }

    @Test fun nestedMetadataPreservesDayTotalsAndFileTypesWithoutOpeningVideo() {
        val day = "tracks-28Oct2026"
        val tree = MetadataTree(mapOf("" to listOf(dir(day)),
            day to listOf(dir("drone"), file("log", 20, "text/plain")),
            "$day/drone" to listOf(file("capture", 4_500_000_000L, "video/mp4"), file("track.kmz", 50))))
        val inventory = readFlightArchiveInventory(tree).single()
        val summary = summarizeArchiveEntries(inventory.entries)
        assertEquals(4_500_000_070L, inventory.bytes)
        assertEquals(inventory.bytes, summary.totalBytes)
        assertEquals(1, summary.videoCount)
        assertEquals(1, summary.logFileCount)
        assertEquals(1, summary.kmzCount)
        assertEquals(listOf("", day, "$day/drone"), tree.queried)
    }

    @Test(expected = IOException::class) fun unavailableProviderListingCannotBecomeZeroUsage() {
        val tree = MetadataTree(mapOf("" to listOf(dir("tracks-28Oct2026"))))
        readFlightArchiveInventory(tree)
    }

    @Test(expected = IllegalStateException::class) fun unknownFileSizeCannotBecomeZeroUsage() {
        val day = "tracks-28Oct2026"
        val tree = MetadataTree(mapOf("" to listOf(dir(day)), day to listOf(file("recording.mp4", -1))))
        readFlightArchiveInventory(tree)
    }

    @Test fun cancelledScanStopsBeforeRequestingAnotherDirectory() {
        val day = "tracks-28Oct2026"
        val tree = MetadataTree(mapOf("" to listOf(dir(day)), day to listOf(dir("drone"))))
        var cancelled = false
        try {
            readFlightArchiveInventory(tree) {
                if (tree.queried.size == 2) throw CancellationException()
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(listOf("", day), tree.queried)
    }
}
