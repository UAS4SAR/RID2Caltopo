package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File

class TrackArchiveConfirmationTest {
    @After fun reset() {
        AwaitingMapFlights.useFileForTesting(null)
        FlightPublicationLedger.useFileForTesting(null)
        FlightPublicationLedger.clearForTesting()
        CaltopoInterruptedTrackJournal.setFileForTesting(null)
    }

    private val now = 1_000_000_000L
    private fun points() = JSONArray().put(JSONArray().put(-121.0).put(39.0).put(10).put(now))

    @Test fun confirmedWhenPublishedOrFlagOrArchiveId() {
        assertTrue(TrackArchiveConfirmation.isConfirmed("published", archiveConfirmed = false))
        assertTrue(TrackArchiveConfirmation.isConfirmed("review", archiveConfirmed = true))
        assertTrue(TrackArchiveConfirmation.isConfirmed("queued", archiveConfirmed = false, hasArchiveId = true))
        assertFalse(TrackArchiveConfirmation.isConfirmed("review", archiveConfirmed = false))
        assertFalse(TrackArchiveConfirmation.isConfirmed("queued", archiveConfirmed = false))
    }

    @Test fun mayAutoArchiveOnlyWhenNeverConfirmed() {
        assertTrue(TrackArchiveConfirmation.mayAutoArchive(confirmed = false, remotelyDeleted = false))
        assertFalse(TrackArchiveConfirmation.mayAutoArchive(confirmed = true, remotelyDeleted = false))
        assertFalse(TrackArchiveConfirmation.mayAutoArchive(confirmed = false, remotelyDeleted = true))
    }

    @Test fun awaitingInboxExcludesConfirmedAndNonReview() {
        assertTrue(
            TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
                "review", finished = true, archiveConfirmed = false,
            ),
        )
        assertFalse(
            TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
                "review", finished = true, archiveConfirmed = true,
            ),
        )
        assertFalse(
            TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
                "queued", finished = true, archiveConfirmed = false,
            ),
        )
    }

    @Test fun notePublicationBindsEmptyMapReviewBeforeArchive() {
        val file = File.createTempFile("note-bind", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            AwaitingMapFlights.record("one", "RID", "Aircraft", points(), false, "", "personal:acct")
            assertEquals("review", AwaitingMapFlights.allEntries().single().getString("decision"))
            assertEquals("", AwaitingMapFlights.allEntries().single().getString("map"))

            AwaitingMapFlights.notePublication("RID", "map-a")
            val bound = AwaitingMapFlights.allEntries().single()
            assertEquals("bound", bound.getString("decision"))
            assertEquals("map-a", bound.getString("map"))
            assertTrue(bound.getBoolean("publicationStarted"))

            AwaitingMapFlights.record("one", "RID", "Aircraft", points(), true, "map-a", "personal:acct")
            // Live-published bound flight is removed at finish (Android parity).
            assertTrue(AwaitingMapFlights.allEntries().isEmpty())
            assertTrue(AwaitingMapFlights.pending().isEmpty())
        } finally {
            file.delete()
        }
    }

    /** Confirm ~9s before end with no post-confirm points → no bound→publish deferred archive. */
    @Test fun confirmThenImmediateEndDoesNotPromoteBoundToDeferredPublish() {
        val file = File.createTempFile("confirm-end", ".json").also { it.delete() }
        val journal = File.createTempFile("confirm-end-journal", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            CaltopoInterruptedTrackJournal.setFileForTesting(journal)
            val flightId = RidFlightId.make("1581F5FJC23A0000001", now)
            AwaitingMapFlights.record(
                flightId, "1581F5FJC23A0000001", "1581F5FJC23A0000001",
                points(), false, "map-a", "team-a",
            )
            assertEquals("bound", AwaitingMapFlights.allEntries().single().getString("decision"))
            assertFalse(AwaitingMapFlights.allEntries().single().optBoolean("publicationStarted"))

            AwaitingMapFlights.markPublicationStarted("1581F5FJC23A0000001")
            AwaitingMapFlights.notePublication("1581F5FJC23A0000001", "map-a", "1sar7Avata2")
            val confirmed = AwaitingMapFlights.allEntries().single()
            assertTrue(confirmed.getBoolean("publicationStarted"))
            assertEquals("1sar7Avata2", confirmed.getString("label"))
            assertEquals(flightId, confirmed.getString("id"))

            AwaitingMapFlights.record(
                flightId, "1581F5FJC23A0000001", "1sar7Avata2",
                points(), true, "map-a", "team-a",
            )
            // Bound + publicationStarted → removed; live finish owns the single archive.
            assertTrue(AwaitingMapFlights.allEntries().isEmpty())
            assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
        } finally {
            CaltopoInterruptedTrackJournal.setFileForTesting(null)
            file.delete()
            journal.delete()
        }
    }

    @Test fun confirmArchivePrunesAndClearsInbox() {
        val file = File.createTempFile("confirm-prune", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            AwaitingMapFlights.record("one", "RID", "Aircraft", points(), false, "", "personal:acct")
            AwaitingMapFlights.record("one", "RID", "Aircraft", points(), true, "", "personal:acct")
            assertEquals(1, AwaitingMapFlights.pending().size)

            assertTrue(
                AwaitingMapFlights.confirmArchive(
                    archiveId = "shape-guid-1",
                    remote = "RID",
                    flightId = "one",
                    folderTitle = "Drone Tracks 01Nov",
                ),
            )
            assertTrue(AwaitingMapFlights.allEntries().isEmpty())
            assertTrue(AwaitingMapFlights.pending().isEmpty())

            AwaitingMapFlights.useFileForTesting(file)
            assertTrue(AwaitingMapFlights.allEntries().isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test fun pruneConfirmedLeavesUnpublishedReviewAlone() {
        val file = File.createTempFile("prune-keep", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            FlightPublicationLedger.clearForTesting()
            AwaitingMapFlights.record("prune-keep-one", "RID", "Aircraft", points(), false, "", "old-team")
            AwaitingMapFlights.record("prune-keep-one", "RID", "Aircraft", points(), true, "", "old-team")
            AwaitingMapFlights.pruneConfirmedArchives()
            assertEquals(1, AwaitingMapFlights.allEntries().size)
            assertEquals("review", AwaitingMapFlights.allEntries().single().getString("decision"))
            assertEquals(1, AwaitingMapFlights.pending().size)
        } finally {
            file.delete()
        }
    }

    @Test fun legacyPublishedDecisionIsPrunedAsConfirmed() {
        val file = File.createTempFile("legacy-published", ".json").also { it.delete() }
        try {
            AwaitingMapFlights.useFileForTesting(file)
            AwaitingMapFlights.record("one", "RID", "Aircraft", points(), false, "map-a", "team-a")
            file.writeText(
                JSONArray().put(
                    JSONObject()
                        .put("id", "one")
                        .put("remote", "RID")
                        .put("label", "Aircraft")
                        .put("map", "map-a")
                        .put("team", "team-a")
                        .put("decision", "published")
                        .put("finished", true)
                        .put("points", points()),
                ).toString(),
            )
            AwaitingMapFlights.useFileForTesting(file)
            AwaitingMapFlights.pruneConfirmedArchives()
            assertTrue(AwaitingMapFlights.allEntries().isEmpty())
        } finally {
            file.delete()
        }
    }
}
