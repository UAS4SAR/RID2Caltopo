package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CluePhotoUploadPolicyTest {
    @Test
    fun photoTimeoutScalesWithSizeAndCaps() {
        assertEquals(45, CluePhotoUploadPolicy.photoUploadTimeoutSeconds(0))
        // Any non-empty payload under 1 MB gets base + one MB allowance (field 853 KB case).
        assertEquals(75, CluePhotoUploadPolicy.photoUploadTimeoutSeconds(100_000))
        assertEquals(75, CluePhotoUploadPolicy.photoUploadTimeoutSeconds(853 * 1024))
        assertEquals(75, CluePhotoUploadPolicy.photoUploadTimeoutSeconds(1024 * 1024))
        assertEquals(105, CluePhotoUploadPolicy.photoUploadTimeoutSeconds(2 * 1024 * 1024))
        assertEquals(180, CluePhotoUploadPolicy.photoUploadTimeoutSeconds(20 * 1024 * 1024))
    }

    @Test
    fun sendBudgetCoversJsonStepsPlusPhotoTimeout() {
        val overhead = CluePhotoUploadPolicy.JSON_TIMEOUT_SECONDS * 5
        assertEquals(overhead, CluePhotoUploadPolicy.ATTEMPT_BUDGET_OVERHEAD_SECONDS)
        assertEquals(45 + overhead, CluePhotoUploadPolicy.attemptBudgetSeconds(0))
        assertEquals(75 + overhead, CluePhotoUploadPolicy.attemptBudgetSeconds(100_000))
        assertEquals(45 + overhead, CluePhotoUploadPolicy.submitDeadlineSeconds(0))
        assertEquals(75 + overhead, CluePhotoUploadPolicy.submitDeadlineSeconds(100_000))
        assertEquals(
            CluePhotoUploadPolicy.attemptBudgetSeconds(0),
            CluePhotoUploadPolicy.submitDeadlineSeconds(0),
        )
        assertTrue(
            CluePhotoUploadPolicy.attemptBudgetSeconds(100_000)
                >= CluePhotoUploadPolicy.photoUploadTimeoutSeconds(100_000) + overhead,
        )
    }

    @Test
    fun existingBackendMediaMessageIsClassified() {
        assertTrue(
            CluePhotoUploadPolicy.isExistingBackendMediaError(
                400,
                """{"message":"Cannot update existing backendMedia"}""",
            ),
        )
        assertFalse(CluePhotoUploadPolicy.isExistingBackendMediaError(400, "Personal map request failed."))
        assertFalse(CluePhotoUploadPolicy.isExistingBackendMediaError(404, """{"message":"Cannot update existing backendMedia"}"""))
        assertTrue(
            CluePhotoUploadPolicy.isAmbiguousMediaState(
                409,
                "Cannot update existing backendMedia; media state ambiguous",
            ),
        )
    }

    @Test
    fun plainUploadErrorIsOperatorFacing() {
        assertEquals(
            "Photo upload incomplete; replacing photo id",
            CluePhotoUploadPolicy.plainUploadError("""{"message":"Cannot update existing backendMedia"}"""),
        )
        assertEquals("Waiting to upload", CluePhotoUploadPolicy.plainUploadError(""))
        assertEquals("Needs attention", CluePhotoUploadPolicy.plainUploadError("Needs attention"))
        assertEquals("Photo missing", CluePhotoUploadPolicy.plainUploadError("Photo missing"))
        assertEquals("Photo missing", CluePhotoUploadPolicy.plainUploadError("missing-photo"))
    }

    @Test
    fun missingPhotoStopsAutoRetryAndNotesLostPhotoOnce() {
        assertTrue(CluePhotoUploadPolicy.stopsAutoRetry("Photo missing"))
        assertTrue(CluePhotoUploadPolicy.stopsAutoRetry("Needs attention"))
        assertTrue(CluePhotoUploadPolicy.stopsAutoRetry("missing-photo"))
        assertFalse(CluePhotoUploadPolicy.stopsAutoRetry("Upload timed out; will retry"))
        val noted = CluePhotoUploadPolicy.descriptionWithLostPhotoNote("Found beside trail")
        assertTrue(CluePhotoUploadPolicy.descriptionNotesLostPhoto(noted))
        assertEquals(noted, CluePhotoUploadPolicy.descriptionWithLostPhotoNote(noted))
    }

    @Test
    fun markerTimeIncludesLocalZoneAndUTC() {
        val text = CluePhotoUploadPolicy.formatClueMarkerTime(
            1_730_401_140_000L,
            ZoneId.of("America/Los_Angeles"),
        )
        assertTrue(text.contains("UTC"))
        assertTrue(text.contains("(") && text.contains(")"))
    }

    @Test
    fun clueJsonTimeoutMatchesV254() {
        assertEquals(60, CluePhotoUploadPolicy.JSON_TIMEOUT_SECONDS)
    }

    @Test
    fun clueStepFailureMessageIncludesStepAndScrubsIds() {
        val line = CluePhotoUploadPolicy.clueStepFailureMessage(
            "media-post",
            "POST",
            "/api/v1/media/19fe1364-dc45-4275-a6ae-7fcfef8aef78?signature=secret",
            15_012L,
            60,
        )
        assertTrue(line.contains("step=media-post"))
        assertTrue(line.contains("method=POST"))
        assertTrue(line.contains("path=/api/v1/media/…"))
        assertFalse(line.contains("19fe1364"))
        assertFalse(line.contains("signature"))
        assertTrue(line.contains("elapsedMs=15012"))
        assertTrue(line.contains("timeoutSeconds=60"))
    }
}
