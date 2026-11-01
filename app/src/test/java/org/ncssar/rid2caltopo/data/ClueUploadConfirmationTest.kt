package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClueUploadConfirmationTest {
    @Test
    fun publishedOrReconciledIsConfirmed() {
        assertTrue(ClueUploadConfirmation.isConfirmed("published", partsReconciled = false))
        assertTrue(ClueUploadConfirmation.isConfirmed("pending", partsReconciled = true))
        assertFalse(ClueUploadConfirmation.isConfirmed("pending", partsReconciled = false))
        assertFalse(ClueUploadConfirmation.isConfirmed("failed", partsReconciled = false))
    }

    @Test
    fun legacySuccessMarkerIdConfirmsNonPending() {
        assertTrue(
            ClueUploadConfirmation.isConfirmed(
                uploadState = "legacy",
                partsReconciled = false,
                hasLegacyCaltopoIds = true,
            ),
        )
        assertFalse(
            ClueUploadConfirmation.isConfirmed(
                uploadState = "pending",
                partsReconciled = false,
                hasLegacyCaltopoIds = true,
            ),
        )
    }

    @Test
    fun onlyUnconfirmedPendingMayAutoUpload() {
        assertTrue(ClueUploadConfirmation.mayAutoUpload("pending", confirmed = false, remotelyDeleted = false))
        assertFalse(ClueUploadConfirmation.mayAutoUpload("pending", confirmed = true, remotelyDeleted = false))
        assertFalse(ClueUploadConfirmation.mayAutoUpload("pending", confirmed = false, remotelyDeleted = true))
        assertFalse(ClueUploadConfirmation.mayAutoUpload("published", confirmed = true, remotelyDeleted = false))
    }

    @Test
    fun confirmedMarkerMissingMeansMapDeletedEvenWhenMediaRemains() {
        // Field bug: media library survival looked like HTTP 409 partial and re-POSTed the marker.
        assertEquals(
            ClueUploadConfirmation.ProbeDecision.MarkRemotelyDeleted,
            ClueUploadConfirmation.decideProbe(
                confirmed = true,
                markerPresent = false,
                mediaPresent = true,
                linkPresent = false,
            ),
        )
    }

    @Test
    fun confirmedAllPresentBackfillsReconciled() {
        assertEquals(
            ClueUploadConfirmation.ProbeDecision.ConfirmReconciled,
            ClueUploadConfirmation.decideProbe(
                confirmed = true,
                markerPresent = true,
                mediaPresent = true,
                linkPresent = true,
            ),
        )
    }

    @Test
    fun unconfirmedAbsentMayAutoUpload() {
        assertEquals(
            ClueUploadConfirmation.ProbeDecision.MayAutoUpload,
            ClueUploadConfirmation.decideProbe(
                confirmed = false,
                markerPresent = false,
                mediaPresent = false,
                linkPresent = false,
            ),
        )
    }

    @Test
    fun unconfirmedPartialMayRepair() {
        assertEquals(
            ClueUploadConfirmation.ProbeDecision.RepairPartial,
            ClueUploadConfirmation.decideProbe(
                confirmed = false,
                markerPresent = true,
                mediaPresent = false,
                linkPresent = false,
            ),
        )
    }
}
