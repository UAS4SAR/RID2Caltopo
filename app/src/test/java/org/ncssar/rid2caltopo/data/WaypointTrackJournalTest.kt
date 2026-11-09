package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun legacyJournalIsHeldAndDoesNotInventAPilotOrAutoPublish() {
        val flightId = "flight-legacy"
        WaypointTrackJournal.ensureOpen(flightId, "RID-L", "Legacy", "map-a", "team-a", 1_000L)
        WaypointTrackJournal.appendPoint(flightId, 39.15, -121.13, 10.0, 1_000L, 1_001L, "rid", true)
        WaypointTrackJournal.drainForTesting()
        WaypointTrackJournal.recoverUnfinished()
        val entry = AwaitingMapFlights.allEntries().single()
        assertEquals("review", entry.getString("decision"))
        assertTrue(entry.getBoolean("ownershipReviewPending"))
        assertFalse(entry.has("recordedPilot"))
        assertFalse(entry.optBoolean("ownershipContextKnown"))
        assertEquals("review", entry.getString("decision"))
    }

    @Test
    fun pendingMineSurvivesRestartAndVerifiedMineCanPublish() {
        val pending = "flight-pending"
        WaypointTrackJournal.ensureOpen(pending, "RID-P", "Pending", "map-a", "team-a", 2_000L)
        WaypointTrackJournal.appendPoint(pending, 39.15, -121.13, 10.0, 2_000L, 2_001L, "rid", true)
        WaypointTrackJournal.noteContext(pending, FlightOwnershipContext.capture(
            true, false, false, FlightOwnershipContext.PENDING, "1SAR7", "NCSSAR", "Mini", "mid"))
        WaypointTrackJournal.drainForTesting()
        WaypointTrackJournal.recoverUnfinished()
        val held = AwaitingMapFlights.allEntries().single()
        assertEquals("1SAR7", held.getString("recordedPilot"))
        assertEquals(FlightOwnershipContext.PENDING, held.getString("claim"))
        assertTrue(held.getBoolean("ownershipReviewPending"))
        assertEquals("review", held.getString("decision"))

        AwaitingMapFlights.useFileForTesting(awaitingFile)
        val verified = "flight-verified"
        WaypointTrackJournal.ensureOpen(verified, "RID-V", "Verified", "map-a", "team-a", 3_000L)
        WaypointTrackJournal.appendPoint(verified, 39.16, -121.14, 11.0, 3_000L, 3_001L, "rid", true)
        WaypointTrackJournal.noteContext(verified, FlightOwnershipContext.capture(
            true, false, false, FlightOwnershipContext.VERIFIED, "1SAR8", "NCSSAR", "Mini", "mid"))
        WaypointTrackJournal.drainForTesting()
        WaypointTrackJournal.recoverUnfinished()
        val published = AwaitingMapFlights.allEntries().single { it.getString("id") == verified }
        assertEquals("publish", published.getString("decision"))
        assertFalse(published.optBoolean("ownershipReviewPending"))
        assertEquals("1SAR8", published.getString("recordedPilot"))
    }

    @Test
    fun unconfirmedRecoveryStaysLocalAndDoesNotBlockAnotherFlight() {
        val blocked = "flight-unconfirmed"
        WaypointTrackJournal.ensureOpen(blocked, "RID-U", "Open", "map-a", "team-a", 4_000L)
        WaypointTrackJournal.appendPoint(blocked, 39.1, -121.1, 10.0, 4_000L, 4_001L, "rid", true)
        WaypointTrackJournal.noteContext(blocked, FlightOwnershipContext.capture(
            false, false, false, FlightOwnershipContext.NONE, "Somebody", "NCSSAR", "Mini", "mid"))
        WaypointTrackJournal.drainForTesting()
        WaypointTrackJournal.recoverUnfinished()
        val entry = AwaitingMapFlights.allEntries().single()
        assertTrue(entry.getBoolean("automaticPublicationBlocked"))
        assertFalse(entry.has("recordedPilot"))
        assertThrows(IllegalStateException::class.java) {
            AwaitingMapFlights.decide(blocked, "map-a", "team-a")
        }
        AwaitingMapFlights.record("other", "RID-O", "Other", flightPoints(), false, "map-a", "team-a", false)
        AwaitingMapFlights.record("other", "RID-O", "Other", flightPoints(), true, "map-a", "team-a", false)
        assertEquals("publish", AwaitingMapFlights.allEntries().first { it.getString("id") == "other" }.getString("decision"))
        assertEquals("review", AwaitingMapFlights.allEntries().first { it.getString("id") == blocked }.getString("decision"))
    }

    private fun flightPoints() = org.json.JSONArray().put(org.json.JSONArray().put(-121.0).put(39.0).put(10).put(9_000L))

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
