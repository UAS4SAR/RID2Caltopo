package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WaypointTrackJournalRecoveryGateTest {
    private lateinit var root: File
    private lateinit var awaitingFile: File
    private lateinit var ledgerFile: File

    @Before
    fun setUp() {
        root = File.createTempFile("waypoint-journals-gate", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        awaitingFile = File.createTempFile("awaiting-map-gate", ".json").also { it.writeText("[]") }
        ledgerFile = File.createTempFile("ledger-gate", ".json").also { it.writeText("{}") }
        WaypointTrackJournal.setRootForTesting(root)
        AwaitingMapFlights.useFileForTesting(awaitingFile)
        FlightPublicationLedger.useFileForTesting(ledgerFile)
        WaypointTrackJournal.setRecoverBetweenRecordStepsHook(null)
        WaypointTrackJournal.setRecoverBeforeDeleteHook(null)
    }

    @After
    fun tearDown() {
        WaypointTrackJournal.setRecoverBetweenRecordStepsHook(null)
        WaypointTrackJournal.setRecoverBeforeDeleteHook(null)
        WaypointTrackJournal.setRootForTesting(null)
        AwaitingMapFlights.useFileForTesting(null)
        FlightPublicationLedger.useFileForTesting(null)
        root.deleteRecursively()
        awaitingFile.delete()
        ledgerFile.delete()
    }

    @Test
    fun declinedJournalIsRetiredWithoutAwaitingMap() {
        val flightId = "flight-declined"
        WaypointTrackJournal.ensureOpen(flightId, "RID-1", "label", "", "team")
        WaypointTrackJournal.appendPoint(flightId, 39.1, -121.1, 100.0, 1_000L, 1_001L, "rid", true)
        WaypointTrackJournal.drainForTesting()
        FlightPublicationLedger.remember(flightId, FlightPublicationLedger.Disposition.DECLINED)

        WaypointTrackJournal.recoverUnfinished()

        assertTrue(AwaitingMapFlights.pending().isEmpty())
        assertFalse(File(root, "$flightId.ndjson").exists())
    }

    @Test
    fun publishedLedgerBlocksRepromotion() {
        val flightId = "flight-published"
        WaypointTrackJournal.ensureOpen(flightId, "RID-2", "label2", "map", "team")
        WaypointTrackJournal.appendPoint(flightId, 39.2, -121.2, 200.0, 2_000L, 2_001L, "rid", true)
        WaypointTrackJournal.drainForTesting()
        FlightPublicationLedger.remember(flightId, FlightPublicationLedger.Disposition.PUBLISHED)

        WaypointTrackJournal.recoverUnfinished()

        assertTrue(AwaitingMapFlights.pending().isEmpty())
        assertFalse(File(root, "$flightId.ndjson").exists())
    }

    @Test
    fun finishedReviewIsNotRepromoted() {
        val flightId = "flight-already-recovered"
        WaypointTrackJournal.ensureOpen(flightId, "RID-3", "label3", "", "team")
        WaypointTrackJournal.appendPoint(flightId, 1.0, 2.0, 3.0, 10L, 11L, "rid", true)
        WaypointTrackJournal.drainForTesting()
        WaypointTrackJournal.recoverUnfinished()
        assertEquals(1, AwaitingMapFlights.pending().size)
        assertFalse(File(root, "$flightId.ndjson").exists())

        // Simulate a resurrected journal for the same finished review offer.
        WaypointTrackJournal.ensureOpen(flightId, "RID-3", "label3", "", "team")
        // ensureOpen refuses ledger-blocked ids; clear ledger and plant a raw journal.
        FlightPublicationLedger.clearForTesting()
        val journal = File(root, "$flightId.ndjson")
        journal.writeText(
            """
            {"type":"header","id":"$flightId","remote":"RID-3","label":"label3","map":"","team":"team","startedAtMs":10}
            {"type":"point","lat":1.0,"lng":2.0,"ele":3.0,"t":10,"recv":11,"src":"rid","droneClock":true}
            """.trimIndent() + "\n",
        )

        WaypointTrackJournal.recoverUnfinished()
        assertEquals(1, AwaitingMapFlights.pending().size)
        assertFalse(journal.exists())
    }

    @Test
    fun concurrentRecoverIsSerializedAndIdempotent() {
        val flightId = "flight-concurrent"
        WaypointTrackJournal.ensureOpen(flightId, "RID-4", "label4", "", "team")
        WaypointTrackJournal.appendPoint(flightId, 39.0, -121.0, 50.0, 40L, 41L, "rid", true)
        WaypointTrackJournal.drainForTesting()

        val started = CountDownLatch(2)
        val errors = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) {
            pool.execute {
                started.countDown()
                started.await(2, TimeUnit.SECONDS)
                try {
                    WaypointTrackJournal.recoverUnfinished()
                } catch (_: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
        assertEquals(1, AwaitingMapFlights.pending().size)
        assertFalse(File(root, "$flightId.ndjson").exists())
    }

    @Test
    fun ensureOpenReplacesFinishedJournalForSameId() {
        val flightId = "flight-reuse"
        WaypointTrackJournal.ensureOpen(flightId, "RID-5", "a", "", "team", 100L)
        WaypointTrackJournal.appendPoint(flightId, 1.0, 2.0, 3.0, 100L, 101L, "rid", true)
        WaypointTrackJournal.markFinishedAndRemove(flightId)
        WaypointTrackJournal.drainForTesting()
        assertFalse(File(root, "$flightId.ndjson").exists())

        WaypointTrackJournal.ensureOpen(flightId, "RID-5", "b", "", "team", 200L)
        WaypointTrackJournal.appendPoint(flightId, 4.0, 5.0, 6.0, 200L, 201L, "rid", true)
        WaypointTrackJournal.drainForTesting()
        val recovered = WaypointTrackJournal.readJournal(File(root, "$flightId.ndjson"))!!
        assertEquals(1, recovered.points.length())
        assertEquals(200L, recovered.firstTimestampMs)
        assertFalse(recovered.finished)
    }
}
