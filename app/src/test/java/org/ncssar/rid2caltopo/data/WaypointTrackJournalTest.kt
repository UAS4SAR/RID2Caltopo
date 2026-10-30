package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WaypointTrackJournalTest {
    private lateinit var root: File
    private lateinit var awaitingFile: File

    @Before
    fun setUp() {
        root = File.createTempFile("waypoint-journals", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        awaitingFile = File.createTempFile("awaiting-map", ".json").also { it.writeText("[]") }
        WaypointTrackJournal.setRootForTesting(root)
        AwaitingMapFlights.useFileForTesting(awaitingFile)
    }

    @After
    fun tearDown() {
        WaypointTrackJournal.setRootForTesting(null)
        AwaitingMapFlights.useFileForTesting(null)
        root.deleteRecursively()
        awaitingFile.delete()
    }

    @Test
    fun appendJournalSurvivesUncleanExitAndRecoversIntoAwaitingMap() {
        val flightId = "flight-recover-1"
        WaypointTrackJournal.ensureOpen(flightId, "RID-1", "1sar7DjMtrc4td_143651Oct30", "", "team-a")
        WaypointTrackJournal.appendPoint(flightId, 39.15, -121.13, 520.0, 1_000L, 1_001L, "rid", true)
        WaypointTrackJournal.appendPoint(flightId, 39.16, -121.14, 530.0, 2_000L, 2_001L, "rid", true)
        WaypointTrackJournal.drainForTesting()

        val journals = root.listFiles { _, name -> name.endsWith(".ndjson") }
        assertEquals(1, journals?.size)

        WaypointTrackJournal.recoverUnfinished()

        val entries = AwaitingMapFlights.allEntries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(flightId, entry.getString("id"))
        assertEquals("1sar7DjMtrc4td_143651Oct30", entry.getString("label"))
        assertTrue(entry.getBoolean("finished"))
        assertEquals(2, entry.getJSONArray("points").length())
        assertEquals(0, root.listFiles()?.size ?: 0)
    }

    @Test
    fun finishedJournalIsRemovedAndNotRecovered() {
        val flightId = "flight-finished"
        WaypointTrackJournal.ensureOpen(flightId, "RID-2", "label", "", "team")
        WaypointTrackJournal.appendPoint(flightId, 39.0, -121.0, 100.0, 10L, 11L, "rid", true)
        WaypointTrackJournal.markFinishedAndRemove(flightId)
        WaypointTrackJournal.drainForTesting()

        WaypointTrackJournal.recoverUnfinished()
        assertEquals(0, AwaitingMapFlights.allEntries().size)
        assertFalse(File(root, "$flightId.ndjson").exists())
    }

    @Test
    fun readJournalParsesHeaderAndPoints() {
        val flightId = "flight-parse"
        WaypointTrackJournal.ensureOpen(flightId, "RID-3", "Mn4Pr", "map", "team")
        WaypointTrackJournal.appendPoint(flightId, 1.0, 2.0, 3.0, 40L, 41L, "sei", false)
        WaypointTrackJournal.drainForTesting()
        val file = File(root, "$flightId.ndjson")
        val recovered = WaypointTrackJournal.readJournal(file)!!
        assertEquals(flightId, recovered.id)
        assertEquals("RID-3", recovered.remote)
        assertEquals(1, recovered.points.length())
        val point = recovered.points.getJSONArray(0)
        assertEquals("2.000000", point.getString(0))
        assertEquals("1.000000", point.getString(1))
        assertFalse(recovered.finished)
    }
}
