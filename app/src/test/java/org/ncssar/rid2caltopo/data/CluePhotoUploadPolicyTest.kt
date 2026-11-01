package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun existingBackendMediaMessageIsClassified() {
        assertTrue(
            CluePhotoUploadPolicy.isExistingBackendMediaError(
                400,
                """{"message":"Cannot update existing backendMedia"}""",
            ),
        )
        assertFalse(CluePhotoUploadPolicy.isExistingBackendMediaError(400, "Personal map request failed."))
        assertFalse(CluePhotoUploadPolicy.isExistingBackendMediaError(404, """{"message":"Cannot update existing backendMedia"}"""))
    }

    @Test
    fun plainUploadErrorIsOperatorFacing() {
        assertEquals(
            "Photo already on CalTopo; finishing link",
            CluePhotoUploadPolicy.plainUploadError("""{"message":"Cannot update existing backendMedia"}"""),
        )
        assertEquals("Waiting to upload", CluePhotoUploadPolicy.plainUploadError(""))
    }
}
