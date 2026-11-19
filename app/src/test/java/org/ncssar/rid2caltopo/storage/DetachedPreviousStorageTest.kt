package org.ncssar.rid2caltopo.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field 2026-11-18 (A5): card 4A21-0000 was moved to the S11U and a smaller card (2C5D-1669)
 * went in. The archive folder on the new card was active and writable, but 15 app-private links
 * still pointed at 4A21-0000, so storage never became ready and "Previous SD card missing" stayed
 * even after re-selecting the folder.
 */
class DetachedPreviousStorageTest {
    private val old = "/storage/4A21-0000/Android/data/org.ncssar.rid2caltopo/cache/operational-work/709c66c989b07197/"
    private val none: (String) -> Boolean = { false }

    @After fun reset() = DetachedPreviousStorage.resetForTesting()

    @Test fun onlyThisAppsWorkingCopyOnAnUnmountedCardIsDetachable() {
        assertEquals("4A21-0000", DetachedPreviousStorage.detachableVolume("queue-state", old + "queue-state", none))
        assertEquals("4A21-0000", DetachedPreviousStorage.detachableVolume("unfinished-recordings", old + "unfinished-recordings/", none))
        assertNull("card still mounted", DetachedPreviousStorage.detachableVolume("queue-state", old + "queue-state") { it == "4A21-0000" })
        assertNull("link names another area", DetachedPreviousStorage.detachableVolume("queue-state", old + "recordings", none))
        assertNull("tablet storage", DetachedPreviousStorage.detachableVolume("queue-state",
            "/storage/emulated/0/Android/data/org.ncssar.rid2caltopo/cache/operational-work/709c66c989b07197/queue-state", none))
        assertNull("not a working copy", DetachedPreviousStorage.detachableVolume("queue-state", "/storage/4A21-0000/DroneTrax/queue-state", none))
        assertNull(DetachedPreviousStorage.detachableVolume("queue-state", null, none))
    }

    @Test fun ledgerKeepsNotedWorkPersistsAndForgetDropsOnlyRecords() {
        val dir = Files.createTempDirectory("detached").toFile()
        try {
            val file = File(dir, "detached.tsv")
            val cardFile = File(dir, "card/unfinished-recordings/live.mp4").apply { parentFile!!.mkdirs(); writeText("frames") }
            DetachedPreviousStorage.attach(file)
            DetachedPreviousStorage.detached(DetachedArea("4A21-0000", "queue-state", old + "queue-state"))
            DetachedPreviousStorage.detached(DetachedArea("4A21-0000", "decoded-terrain", old + "decoded-terrain"))
            assertTrue("pure state and caches are rebuilt silently", DetachedPreviousStorage.notes.value.isEmpty())
            val recording = DetachedArea("4A21-0000", "unfinished-recordings", cardFile.parentFile!!.path)
            DetachedPreviousStorage.detached(recording)
            DetachedPreviousStorage.detached(recording)
            DetachedPreviousStorage.detached(DetachedArea("4A21-0000", "missions", old + "missions"))
            assertEquals(listOf(DetachedCardNote("4A21-0000", setOf("unfinished-recordings", "missions"))), DetachedPreviousStorage.notes.value)

            DetachedPreviousStorage.resetForTesting()
            DetachedPreviousStorage.attach(file)
            assertEquals("survives a restart", 2, DetachedPreviousStorage.entries().size)

            DetachedPreviousStorage.recovered(recording)
            assertEquals(setOf("missions"), DetachedPreviousStorage.notes.value.single().areas)
            DetachedPreviousStorage.forget("4a21-0000")
            assertTrue(DetachedPreviousStorage.notes.value.isEmpty())
            assertFalse(file.exists())
            assertEquals("Forget never deletes files", "frames", cardFile.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun noteIsQuietAndPromisesNoDeletion() {
        val text = DetachedPreviousStorageText.note(DetachedCardNote("4A21-0000", setOf("unfinished-recordings", "recording-work", "missions")))
        assertEquals("Data from SD card 4A21-0000 stayed on that card (unfinished recordings, mapping data). " +
            "Reinsert it to copy that data into the current archive folder, or tap Forget. Forget only clears this note; no files are deleted.", text)
        assertFalse(text.contains("missing", ignoreCase = true))
    }

    @Test fun leftoverSessionLogOnASwappedCardMovesToTheActiveArchive() {
        val key = "content://com.android.externalstorage.documents/tree/4A21-0000%3ADroneTrax/document/" +
            "4A21-0000%3ADroneTrax%2Ftracks-17Nov2026%2FLog_17Nov2026-182550-PST-0800.txt"
        assertEquals("pending:tracks-17Nov2026/Log_17Nov2026-182550-PST-0800.txt",
            DetachedPreviousStorage.retargetSessionLogKey(key, "2C5D-1669", none))
        assertNull("card is back", DetachedPreviousStorage.retargetSessionLogKey(key, "2C5D-1669") { true })
        assertNull("active archive is on that card", DetachedPreviousStorage.retargetSessionLogKey(key, "4A21-0000", none))
        assertNull("no active archive", DetachedPreviousStorage.retargetSessionLogKey(key, null, none))
        assertNull(DetachedPreviousStorage.retargetSessionLogKey("pending:tracks-18Nov2026/Log.txt", "2C5D-1669", none))
        assertNull("tablet storage", DetachedPreviousStorage.retargetSessionLogKey(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments/document/primary%3ADocuments%2Ftracks%2FLog.txt", "2C5D-1669", none))
    }

    /** Both migrate paths must retarget a swapped card's links before the old stop. */
    @Test fun everyDanglingLinkCheckTriesTheCardSwapFirst() {
        val path = "src/main/java/org/ncssar/rid2caltopo/storage/OperationalStorage.kt"
        val source = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        val stops = Regex("throw missingPrevious\\(ArchiveDirAvailability\\.previousStorageMissing").findAll(source).toList()
        assertEquals(2, stops.size)
        for (stop in stops) {
            val before = source.substring(maxOf(0, stop.range.first - 200), stop.range.first)
            assertTrue(before, before.contains("if (detachFromRemovedCard(context, area, source)) continue"))
        }
        assertTrue(source.contains("MissingPreviousStorage.clear()"))
    }
}
