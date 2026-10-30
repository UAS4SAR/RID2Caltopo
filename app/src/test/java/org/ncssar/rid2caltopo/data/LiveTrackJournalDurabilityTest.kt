package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * LiveTrack must journal every accepted point without waiting for liveTrackId.
 * Recovery promotes the journal into AwaitingMapFlights.
 */
class LiveTrackJournalDurabilityTest {
    private lateinit var root: File
    private lateinit var awaitingFile: File

    @Before
    fun setUp() {
        root = File.createTempFile("live-track-journals", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        awaitingFile = File.createTempFile("awaiting-live", ".json").also { it.writeText("[]") }
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
    fun appendWithoutLiveTrackIdRecoversIntoAwaitingMap() {
        val localFlightId = RidFlightId.make("RIDLOCAL", 1_700_000_000_000L)
        WaypointTrackJournal.ensureOpen(localFlightId, "RIDLOCAL", "Aircraft_120000Oct30", "", "team-a")
        WaypointTrackJournal.appendPoint(localFlightId, 39.15, -121.13, 520.0, 1_000L, 1_001L, "live", true)
        WaypointTrackJournal.appendPoint(localFlightId, 39.16, -121.14, 530.0, 2_000L, 2_001L, "live", true)
        WaypointTrackJournal.drainForTesting()

        assertEquals(1, root.listFiles { _, name -> name.endsWith(".ndjson") }?.size)

        WaypointTrackJournal.recoverUnfinished()

        val entries = AwaitingMapFlights.allEntries()
        assertEquals(1, entries.size)
        assertEquals(localFlightId, entries[0].getString("id"))
        assertTrue(entries[0].getBoolean("finished"))
        assertEquals(2, entries[0].getJSONArray("points").length())
        assertFalse(File(root, "$localFlightId.ndjson").exists())
    }
}
