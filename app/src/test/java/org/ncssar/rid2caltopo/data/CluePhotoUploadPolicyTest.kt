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
    fun sendBudgetStartsAtFirstRequestWithNoFolderAllowance() {
        assertEquals(90, CluePhotoUploadPolicy.attemptBudgetSeconds(0))
        assertEquals(120, CluePhotoUploadPolicy.attemptBudgetSeconds(100_000))
        assertEquals(90, CluePhotoUploadPolicy.submitDeadlineSeconds(0))
        assertEquals(120, CluePhotoUploadPolicy.submitDeadlineSeconds(100_000))
        assertEquals(
            CluePhotoUploadPolicy.attemptBudgetSeconds(0),
            CluePhotoUploadPolicy.submitDeadlineSeconds(0),
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
}
