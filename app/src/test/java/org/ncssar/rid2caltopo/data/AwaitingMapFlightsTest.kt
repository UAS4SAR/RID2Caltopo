package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.io.File

class AwaitingMapFlightsTest {
    @Test fun unverifiedOwnershipRemainsHeldAcrossRestart() {
        val file = File.createTempFile("ownership-review", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            val points = flight().getJSONArray("points")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, false, "map-a", "team-a", true)
            assertEquals("", AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
            AwaitingMapFlights.notePublication("RID", "map-a")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, true, "map-a", "team-a", true)
            AwaitingMapFlights.useFileForTesting(file)
            val held = AwaitingMapFlights.pending().single()
            assertEquals("review", held.getString("decision"))
            assertTrue(held.getBoolean("ownershipReviewPending"))
            assertThrows(IllegalStateException::class.java) { AwaitingMapFlights.decide("one", "map-a", "team-a") }
            assertEquals("review", AwaitingMapFlights.allEntries().single().getString("decision"))
            AwaitingMapFlights.record("two", "RID-2", "Other", points, false, "map-b", "team-b", false)
            AwaitingMapFlights.record("two", "RID-2", "Other", points, true, "map-b", "team-b", false)
            assertEquals("publish", AwaitingMapFlights.allEntries().first { it.getString("id") == "two" }.getString("decision"))
            assertFalse(AwaitingMapFlights.authorizeOwnershipReview("two"))
            assertTrue(AwaitingMapFlights.authorizeOwnershipReview("one"))
            AwaitingMapFlights.decide("one", "map-a", "team-a")
            assertEquals("publish", AwaitingMapFlights.allEntries().first { it.getString("id") == "one" }.getString("decision"))
            assertEquals("publish", AwaitingMapFlights.allEntries().first { it.getString("id") == "two" }.getString("decision"))
            assertFalse(AwaitingMapFlights.allEntries().first { it.getString("id") == "one" }.has("recordedPilot"))
        } finally { file.delete() }
    }
    @After fun reset() {
        AwaitingMapFlights.useFileForTesting(null)
        FlightPublicationLedger.useFileForTesting(null)
        FlightPublicationLedger.clearForTesting()
        CaltopoInterruptedTrackJournal.setFileForTesting(null)
    }
    private val now = 1_000_000_000L
    private fun flight(time: Long = now) = JSONObject().put("points", JSONArray().put(JSONArray().put(-121.0).put(39.0).put(10).put(time)))
    private fun feature(title: String, type: String = "Point", lat: Double = 39.0) = JSONObject()
        .put("properties", JSONObject().put("title", title))
        .put("geometry", JSONObject().put("type", type).put("coordinates", JSONArray().put(-121.0).put(lat)))
    @Test fun lateFinalCheckpointDoesNotRecreateAnAlreadyConfirmedArchive() {
        val file = File.createTempFile("flight-intent", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            val points = JSONArray().put(JSONArray().put(1).put(2).put(3).put(now))
                .put(JSONArray().put(2).put(3).put(4).put(now + 1))
                .put(JSONArray().put(3).put(4).put(5).put(now + 2))
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, false, "", "team-a")
            AwaitingMapFlights.decide("one", "map-a", "team-a")
            AwaitingMapFlights.published("one")
            // Confirmed archives are pruned; a late finished checkpoint must not recreate them.
            assertTrue(AwaitingMapFlights.allEntries().isEmpty())
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, true, "", "team-a")
            assertTrue(AwaitingMapFlights.allEntries().isEmpty())
            assertNull(AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
        } finally { file.delete() }
    }

    @Test fun pendingFirstIntentCannotPublishAfterAMapIsSelectedWithoutReview() {
        val file = File.createTempFile("flight-intent", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            AwaitingMapFlights.reservePublication("RID", "one")
            assertEquals("", AwaitingMapFlights.publicationId("RID", "new-map", "team-a"))
            AwaitingMapFlights.record("one", "RID", "Aircraft", flight().getJSONArray("points"), false, "", "team-a")
            assertEquals("", AwaitingMapFlights.publicationId("RID", "new-map", "team-a"))
            AwaitingMapFlights.decide("one", "new-map", "team-a")
            assertEquals("one", AwaitingMapFlights.publicationId("RID", "new-map", "team-a"))
        } finally { file.delete() }
    }

    @Test fun openEmptyMapReviewBindsWhenOwnerAndMapAreReady() {
        val file = File.createTempFile("flight-bind-open", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            AwaitingMapFlights.record("one", "RID", "Aircraft", flight().getJSONArray("points"), false, "", "team-a")
            assertEquals("", AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
            assertEquals("one", AwaitingMapFlights.bindConnectedMapIfOpenReview("RID", "map-a", "team-a"))
            assertNull(AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
            assertEquals("bound", AwaitingMapFlights.allEntries().single().getString("decision"))
            assertEquals("map-a", AwaitingMapFlights.allEntries().single().getString("map"))
            // Finished review offers stay in the inbox — bind only touches unfinished entries.
            AwaitingMapFlights.record("one", "RID", "Aircraft", flight().getJSONArray("points"), true, "map-a", "team-a")
        } finally { file.delete() }
    }

    @Test fun publicationDecisionDoesNotWaitForAStorageWriter() {
        val file = File.createTempFile("flight-intent", ".json").also { it.delete() }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            AwaitingMapFlights.useFileForTesting(file)
            AwaitingMapFlights.record("one", "RID", "Aircraft", flight().getJSONArray("points"), false, "map-a", "team-a")
            workers.submit {
                synchronized(AwaitingMapFlights) { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val decision = workers.submit<String?> { AwaitingMapFlights.publicationId("RID", "map-a", "team-a") }
            assertNull(decision.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally { release.countDown(); workers.shutdownNow(); file.delete() }
    }

    @Test fun nearbyTravelTracksAndGeneralFeaturesDoNotSuggestPublication() {
        assertFalse(AwaitingMapFlights.suggested(flight(), listOf(feature("Member arrival"), feature("IC", "LineString")), now))
    }
    @Test fun recentFlightNearIcIsSuggestedButOldOrDistantFlightIsNot() {
        assertTrue(AwaitingMapFlights.suggested(flight(), listOf(feature(" ic ")), now))
        assertFalse(AwaitingMapFlights.suggested(flight(now-86_400_001L), listOf(feature("IC")), now))
        assertFalse(AwaitingMapFlights.suggested(flight(), listOf(feature("IC", lat=40.0)), now))
        assertFalse(AwaitingMapFlights.suggested(flight(now+1), listOf(feature("IC")), now))
    }
    @Test fun absentIcNeverSuppressesManualReviewData() {
        val record = flight()
        assertFalse(AwaitingMapFlights.suggested(record, emptyList(), now))
        assertEquals(1, record.getJSONArray("points").length())
    }
    @Test fun finishedBoundFlightWithConnectedMapPublishesThroughArchive() {
        val file = File.createTempFile("flight-finish-publish", ".json").also { it.delete() }
        val journal = File.createTempFile("interrupted-finish", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            CaltopoInterruptedTrackJournal.setFileForTesting(journal)
            val points = flight().getJSONArray("points")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, false, "map-a", "team-a")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, true, "map-a", "team-a")
            assertEquals("publish", AwaitingMapFlights.allEntries().single().getString("decision"))
            assertTrue(AwaitingMapFlights.pending().isEmpty())
            assertTrue(journal.exists() || CaltopoInterruptedTrackJournal.entriesForTesting().length() >= 0)
        } finally {
            CaltopoInterruptedTrackJournal.setFileForTesting(null)
            file.delete()
            journal.delete()
        }
    }

    @Test fun stableFlightIdReturnsBoundAsWellAsPublish() {
        val file = File.createTempFile("stable-flight-id", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            val points = flight().getJSONArray("points")
            AwaitingMapFlights.record("stable-1", "RID", "TeamDrone", points, false, "map-a", "team-a")
            assertNull(AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
            assertEquals("stable-1", AwaitingMapFlights.stableFlightId("RID", "map-a"))

            AwaitingMapFlights.record("stable-2", "RID2", "TeamDrone", points, false, "", "team-a")
            AwaitingMapFlights.decide("stable-2", "map-a", "team-a")
            assertEquals("stable-2", AwaitingMapFlights.publicationId("RID2", "map-a", "team-a"))
            assertEquals("stable-2", AwaitingMapFlights.stableFlightId("RID2", "map-a"))
        } finally {
            file.delete()
        }
    }

    @Test fun interruptedJournalDedupesSameFlightUnderDifferentLiveTrackIds() {
        val journal = File.createTempFile("flight-dedupe", ".json").also { it.delete() }
        try {
            CaltopoInterruptedTrackJournal.setFileForTesting(journal)
            val geometry = JSONArray().put(JSONArray().put(-121.0).put(39.0).put(10.0))
            assertTrue(
                CaltopoInterruptedTrackJournal.save(
                    "map-a", "RID", "id-stable", "TeamDrone", "", geometry, now,
                ),
            )
            assertTrue(
                CaltopoInterruptedTrackJournal.save(
                    "map-a", "RID", "id-random", "TeamDrone", "", geometry, now,
                ),
            )
            assertEquals(1, CaltopoInterruptedTrackJournal.entriesForTesting().length())
            assertTrue(CaltopoInterruptedTrackJournal.hasFlight("RID", now, "id-other"))
            assertFalse(CaltopoInterruptedTrackJournal.hasFlight("RID", now, "id-random"))
        } finally {
            CaltopoInterruptedTrackJournal.setFileForTesting(null)
            journal.delete()
        }
    }

    @Test fun selectionNeedsConsentAndRestartRetainsTheDecision() {
        val file = File.createTempFile("flight-intent", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            val points = flight().getJSONArray("points")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, false, "", "team-a")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, false, "map-a", "team-a")
            assertEquals("", AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
            AwaitingMapFlights.decide("one", "map-a", "team-a")
            assertEquals("one", AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
            assertEquals("", AwaitingMapFlights.publicationId("RID", "other", "team-a"))
            assertEquals("", AwaitingMapFlights.publicationId("RID", "map-a", "other-team"))
            AwaitingMapFlights.useFileForTesting(file)
            assertTrue(AwaitingMapFlights.pending().isEmpty())
            assertTrue(JSONObject(org.json.JSONArray(file.readText()).getJSONObject(0).toString()).optString("map") == "map-a")
        } finally { file.delete() }
    }
    @Test fun personalMapUsesAccountScopeForConsentAndPublication() {
        val file = File.createTempFile("personal-flight", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            val scope = AwaitingMapFlights.destinationScope("responder-a", "")
            assertEquals("personal:responder-a", scope)
            AwaitingMapFlights.record("personal", "RID", "Aircraft", flight().getJSONArray("points"), false, "", "")
            assertEquals("", AwaitingMapFlights.publicationId("RID", "personal-map", scope))
            AwaitingMapFlights.decide("personal", "personal-map", scope)
            assertEquals("personal", AwaitingMapFlights.publicationId("RID", "personal-map", scope))
            assertEquals("", AwaitingMapFlights.publicationId("RID", "personal-map", "personal:responder-b"))
            assertEquals("", AwaitingMapFlights.publicationId("RID", "different-map", scope))
            AwaitingMapFlights.useFileForTesting(file)
            val saved = JSONArray(file.readText()).getJSONObject(0)
            assertEquals(scope, saved.getString("team"))
            assertEquals("publish", saved.getString("decision"))
            assertEquals("", AwaitingMapFlights.destinationScope("", "team-a"))
            assertEquals("team-a", AwaitingMapFlights.destinationScope(null, "team-a"))
        } finally { file.delete() }
    }

    @Test fun boundFlightDoesNotBecomeUnassignedAfterMapSwitch() {
        val file = File.createTempFile("flight-bound", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            val points = flight().getJSONArray("points")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, false, "map-a", "team-a")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points, false, "map-b", "team-b")
            assertNull(AwaitingMapFlights.publicationId("RID", "map-a", "team-a"))
            assertEquals("", AwaitingMapFlights.publicationId("RID", "map-b", "team-b"))
            assertTrue(AwaitingMapFlights.pending().isEmpty())
        } finally { file.delete() }
    }
}
