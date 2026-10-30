package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class WaypointTrackJournalDurabilityTest {
    private lateinit var root: File
    private lateinit var awaitingFile: File

    @Before
    fun setUp() {
        root = File.createTempFile("waypoint-journals-durability", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        awaitingFile = File.createTempFile("awaiting-map-durability", ".json").also { it.writeText("[]") }
        WaypointTrackJournal.setRootForTesting(root)
        AwaitingMapFlights.useFileForTesting(awaitingFile)
        WaypointTrackJournal.setRecoverBetweenRecordStepsHook(null)
        WaypointTrackJournal.setRecoverBeforeDeleteHook(null)
    }

    @After
    fun tearDown() {
        WaypointTrackJournal.setRecoverBetweenRecordStepsHook(null)
        WaypointTrackJournal.setRecoverBeforeDeleteHook(null)
        WaypointTrackJournal.setRootForTesting(null)
        AwaitingMapFlights.useFileForTesting(null)
        root.deleteRecursively()
        awaitingFile.delete()
    }

    @Test
    fun recoverUnfinishedLeavesJournalWhenAwaitingMapFailsMidway() {
        val flightId = "flight-kill-mid"
        WaypointTrackJournal.ensureOpen(flightId, "RID-1", "label", "map-a", "team-a")
        WaypointTrackJournal.appendPoint(flightId, 39.1, -121.1, 100.0, 1_000L, 1_001L, "live", true)
        WaypointTrackJournal.drainForTesting()
        val journal = File(root, "$flightId.ndjson")
        assertTrue(journal.isFile)

        WaypointTrackJournal.setRecoverBetweenRecordStepsHook {
            throw IllegalStateException("simulated kill between open and finish")
        }
        WaypointTrackJournal.recoverUnfinished()

        // Journal retained for retry; open record may already be durable, finish must not have run.
        assertTrue(journal.isFile)
        val entries = AwaitingMapFlights.allEntries()
        if (entries.isNotEmpty()) {
            assertFalse(entries[0].optBoolean("finished"))
        }
    }

    @Test
    fun recoverUnfinishedDeletesJournalOnlyAfterSuccessfulCommit() {
        val flightId = "flight-commit-ok"
        WaypointTrackJournal.ensureOpen(flightId, "RID-2", "label2", "map-b", "team-b")
        WaypointTrackJournal.appendPoint(flightId, 39.2, -121.2, 200.0, 2_000L, 2_001L, "live", true)
        WaypointTrackJournal.drainForTesting()

        val beforeDelete = AtomicBoolean(false)
        WaypointTrackJournal.setRecoverBeforeDeleteHook {
            beforeDelete.set(true)
            assertEquals(1, AwaitingMapFlights.allEntries().size)
            assertTrue(File(root, "$flightId.ndjson").isFile)
        }
        WaypointTrackJournal.recoverUnfinished()

        assertTrue(beforeDelete.get())
        assertFalse(File(root, "$flightId.ndjson").exists())
        assertEquals(1, AwaitingMapFlights.allEntries().size)
        assertTrue(AwaitingMapFlights.allEntries()[0].getBoolean("finished"))
    }

    @Test
    fun recoverBeforeDeleteFailureLeavesJournalForRetry() {
        val flightId = "flight-before-delete"
        WaypointTrackJournal.ensureOpen(flightId, "RID-3", "label3", "", "team")
        WaypointTrackJournal.appendPoint(flightId, 1.0, 2.0, 3.0, 10L, 11L, "live", true)
        WaypointTrackJournal.drainForTesting()

        WaypointTrackJournal.setRecoverBeforeDeleteHook {
            throw IllegalStateException("simulated kill after commit before delete")
        }
        WaypointTrackJournal.recoverUnfinished()
        assertTrue(File(root, "$flightId.ndjson").isFile)
        assertEquals(1, AwaitingMapFlights.allEntries().size)

        WaypointTrackJournal.setRecoverBeforeDeleteHook(null)
        WaypointTrackJournal.recoverUnfinished()
        assertFalse(File(root, "$flightId.ndjson").exists())
    }
}
