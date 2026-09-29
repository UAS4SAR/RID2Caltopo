package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CaltopoInterruptedTrackJournalTest {
    private lateinit var journalFile: File

    @Before
    fun setUp() {
        journalFile = File.createTempFile("caltopo-interrupted-", ".json")
        journalFile.delete()
        CaltopoInterruptedTrackJournal.setFileForTesting(journalFile)
    }

    @After
    fun tearDown() {
        CaltopoInterruptedTrackJournal.setFileForTesting(null)
        journalFile.delete()
        File(journalFile.parentFile, journalFile.name + ".tmp").delete()
    }

    @Test
    fun saveSurvivesReloadAndRemoveClearsOnlyMatchingTrack() {
        CaltopoInterruptedTrackJournal.save(
            "map-a",
            "RID-A",
            "live-a",
            "A_120000Aug11",
            "",
            JSONArray("[[-121.1,39.1,500.0],[-121.2,39.2,501.0]]"),
        )
        CaltopoInterruptedTrackJournal.save(
            "map-a",
            "RID-B",
            "live-b",
            "B_120100Aug11",
            "https://r2c-tracker.com/s/example",
            JSONArray("[[-121.3,39.3,502.0]]"),
        )

        val persisted = CaltopoInterruptedTrackJournal.entriesForTesting()
        assertEquals(2, persisted.length())
        assertTrue(journalFile.readText().contains("live-a"))
        assertEquals(2, persisted.getJSONObject(0).getJSONArray("points").length())

        CaltopoInterruptedTrackJournal.remove("live-a")

        val remaining = CaltopoInterruptedTrackJournal.entriesForTesting()
        assertEquals(1, remaining.length())
        assertEquals("live-b", remaining.getJSONObject(0).getString("liveTrackId"))
    }

    @Test
    fun relaunchRecoveryConvertsThenDeletesBeforeClearingJournal() {
        CaltopoInterruptedTrackJournal.save(
            "map-a",
            "RID-RECOVERY",
            "live-recovery",
            "200615Aug11",
            "",
            JSONArray("[[-121.132828,39.153080,527.0]]"),
        )
        val fixture = TestR2cRuntimeFactory.create("recovery-test")

        val recovering = CaltopoInterruptedTrackJournal.recover(
            "map-a",
            "archive-folder",
            fixture.runtime,
        )

        assertEquals(setOf("live-recovery"), recovering)
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
    }
    @Test
    fun repeatedRecoveryIsSerializedAndFailureRemainsRetryableOnOriginalMap() {
        CaltopoInterruptedTrackJournal.save("original", "RID", "stable", "Flight", "", JSONArray("[[-121,39,500]]"))
        val fixture = TestR2cRuntimeFactory.create("serialized-recovery")
        val gateway = fixture.calTopoSessionGateway
        gateway.holdRecoveryEdits = true
        CaltopoInterruptedTrackJournal.recover("other-map", "folder", fixture.runtime)
        assertEquals(0, gateway.countOperations("editObject"))
        repeat(3) { CaltopoInterruptedTrackJournal.recover("original", "folder", fixture.runtime) }
        assertEquals(1, gateway.countOperations("editObject"))
        gateway.completeRecoveryEdit(false)
        assertEquals(1, CaltopoInterruptedTrackJournal.entriesForTesting().length())
        CaltopoInterruptedTrackJournal.recover("original", "folder", fixture.runtime)
        assertEquals(2, gateway.countOperations("editObject"))
        gateway.completeRecoveryEdit(true)
        assertEquals(listOf("original", "original", "original"), gateway.recoveryMaps)
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
    }
    @Test
    fun standaloneArchiveWithMissingLiveTrackCompletesWithoutReplay() {
        for (code in listOf(400, 404)) {
            CaltopoInterruptedTrackJournal.save("map", "RID", "flight-$code", "Flight", "", JSONArray("[[-121,39,500]]"))
            val fixture = TestR2cRuntimeFactory.create("standalone-$code")
            fixture.calTopoSessionGateway.recoveryDeleteResponseCode = code
            repeat(2) { CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime) }
            assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
            assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
        }
    }

    @Test
    fun cleanupFailureAndRestartNeverRecreateAnUploadedArchive() {
        CaltopoInterruptedTrackJournal.save("map", "RID", "flight", "Flight", "", JSONArray("[[-121,39,500]]"))
        val fixture = TestR2cRuntimeFactory.create("cleanup")
        val gateway = fixture.calTopoSessionGateway
        gateway.recoveryDeleteResponseCode = 503
        CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime)
        assertTrue(CaltopoInterruptedTrackJournal.entriesForTesting().getJSONObject(0).getBoolean("archiveUploaded"))
        // Simulate restart and a map where the operator has deleted the Shape.
        CaltopoInterruptedTrackJournal.setFileForTesting(journalFile)
        CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime, emptyList())
        assertEquals(1, gateway.countOperations("editObject"))
        assertEquals(2, gateway.countOperations("deleteLiveTrack"))
        gateway.recoveryDeleteResponseCode = 200
        CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime)
        assertEquals(1, gateway.countOperations("editObject"))
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
    }

    @Test
    fun legacyPendingEntryAlreadyOnMapOnlyNeedsCleanup() {
        CaltopoInterruptedTrackJournal.save("map", "RID", "legacy", "Flight", "", JSONArray("[[-121,39,500]]"))
        val fixture = TestR2cRuntimeFactory.create("legacy")
        fixture.calTopoSessionGateway.recoveryDeleteResponseCode = 400
        val shape = JSONObject().put("id", "legacy")
            .put("properties", JSONObject().put("class", "Shape"))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray("[[-121,39,500]]")))
        CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime, listOf(shape))
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
    }

    @Test
    fun selectingOneFlightDoesNotQueueOthersAndCompletedChoiceSurvivesReconnect() {
        val choices = File.createTempFile("flight-choices", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(choices)
            val points = JSONArray("[[-121,39,500,1000],[-121.1,39.1,501,2000]]")
            for (id in listOf("selected", "review", "local")) {
                AwaitingMapFlights.record(id, "RID", id, points, false, "", "team")
                AwaitingMapFlights.record(id, "RID", id, points, true, "", "team")
            }
            AwaitingMapFlights.decide("local", null, "team")
            AwaitingMapFlights.decide("selected", "map", "team")
            val fixture = TestR2cRuntimeFactory.create("selection")
            fixture.calTopoSessionGateway.recoveryDeleteResponseCode = 503
            CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime)
            AwaitingMapFlights.useFileForTesting(choices)
            AwaitingMapFlights.reconcile("map", "team")
            CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime)
            assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
            assertEquals(listOf("review"), AwaitingMapFlights.pending().map { it.getString("id") })
            val saved = JSONArray(choices.readText())
            val selected = (0 until saved.length()).map { saved.getJSONObject(it) }.single { it.getString("id") == "selected" }
            assertEquals("published", selected.getString("decision"))
        } finally {
            AwaitingMapFlights.useFileForTesting(null)
            choices.delete()
        }
    }

    @Test
    fun partialServerArchiveDoesNotDiscardBufferedPoints() {
        CaltopoInterruptedTrackJournal.save("map", "RID", "partial", "Flight", "", JSONArray("[[-121,39,500],[-122,40,501]]"))
        val fixture = TestR2cRuntimeFactory.create("partial")
        val shape = JSONObject().put("id", "partial")
            .put("properties", JSONObject().put("class", "Shape"))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray("[[-121,39,500]]")))
        CaltopoInterruptedTrackJournal.recover("map", "folder", fixture.runtime, listOf(shape))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
    }

}
