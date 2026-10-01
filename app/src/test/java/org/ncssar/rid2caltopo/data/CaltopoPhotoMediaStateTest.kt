package org.ncssar.rid2caltopo.data
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class CaltopoPhotoMediaStateTest {
    private fun media(ready: Boolean) = JSONObject().put("id", "11111111-2222-3333-4444-555555555555")
        .put("properties", JSONObject().put("creator", "USER01").put("mediaIsReady", ready))
        .put("metadata", JSONObject().put("filesize", 100))
    @Test fun resumesUnfinishedMediaButNeverReusesAnotherOwnersPhoto() {
        val id = "11111111-2222-3333-4444-555555555555"
        assertTrue(CaltopoPhotoMediaState.isReady(media(true), id, "USER01"))
        assertFalse(CaltopoPhotoMediaState.isReady(media(false), id, "USER01"))
        assertThrows(IllegalArgumentException::class.java) { CaltopoPhotoMediaState.isReady(media(true), id, "USER02") }
        assertThrows(IllegalArgumentException::class.java) { CaltopoPhotoMediaState.isReady(media(true), "other", "USER01") }
    }
}
