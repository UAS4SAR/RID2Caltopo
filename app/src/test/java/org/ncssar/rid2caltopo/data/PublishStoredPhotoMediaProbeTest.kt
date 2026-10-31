package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Regression: a quiet media GET 400 ("Personal map request failed.") must mean
 * absent → upload → attach, not PhotoOp rewriting the probe into HTTP 502.
 */
class PublishStoredPhotoMediaProbeTest {
    private val okBody = """{"status":"ok","result":{}}"""

    @Before
    fun setUp() {
        CaltopoPersonalSession.resetRuntimeState()
        CaltopoSession.setStoredPhotoProbeForTesting(null)
        CaltopoSession.setPhotoExchangeForTesting(null)
        CaltopoSession.Init(CaltopoCredentials("TEAM01", "cred-id", "dGVzdA=="), "caltopo.com")
    }

    @After
    fun tearDown() {
        CaltopoSession.setPhotoExchangeForTesting(null)
        CaltopoSession.setStoredPhotoProbeForTesting(null)
        CaltopoPersonalSession.resetRuntimeState()
    }

    @Test
    fun mediaGet400LeadsToUploadThenAttachThenSuccess() {
        val markerId = "19fe1364-dc45-4275-a6ae-7fcfef8aef78"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        val calls = CopyOnWriteArrayList<String>()

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$mediaId" -> {
                    assertTrue(op.quietErrorCodes?.toList()?.containsAll(listOf(400, 404)) == true)
                    // Same CalTopo personal-probe miss the field log showed.
                    complete(op, 400, false, "Personal map request failed.")
                }
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId/data" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp $key")
            }
        }

        // Wait on the photo-worker Future; onComplete posts to the main looper.
        val outer = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "lil dog", "clue", "folder", 1L, byteArrayOf(1, 2, 3), null,
        )
        outer.get(5, TimeUnit.SECONDS)
        assertTrue("publish should succeed after GET 400 probe", outer.success())
        assertEquals(200, outer.responseCode)
        assertEquals(
            listOf(
                "POST /api/v1/map/MAP01/Marker/$markerId",
                "GET /api/v1/media/$mediaId",
                "POST /api/v1/media/$mediaId",
                "POST /api/v1/media/$mediaId/data",
                "POST /api/v1/map/MAP01/MapMediaObject/$linkId",
            ),
            calls.toList(),
        )
    }

    @Test
    fun existingMarkerGetAllowsMediaAttachAfterMarkerPostConflict() {
        val markerId = "61b0ed41-00b5-42bb-9d84-82ccd4e53d82"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        val calls = CopyOnWriteArrayList<String>()

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" ->
                    complete(op, 400, false, "Marker already present")
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" ->
                    complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$mediaId" ->
                    complete(op, 400, false, "Personal map request failed.")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId/data" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "lil dog", "clue", "folder", 1L, byteArrayOf(9), null,
        )
        outer.get(5, TimeUnit.SECONDS)
        assertTrue(outer.success())
        assertTrue(calls[0].startsWith("POST /api/v1/map/MAP01/Marker/"))
        assertTrue(calls[1].startsWith("GET /api/v1/map/MAP01/Marker/"))
        assertTrue(calls.any { it == "POST /api/v1/media/$mediaId/data" })
        assertTrue(calls.last().startsWith("POST /api/v1/map/MAP01/MapMediaObject/"))
    }

    @Test
    fun quietMediaGetMustNotBecomeSuccessOr502() {
        // Mirrors the BgSendRequest quiet-probe outcome after the setOperationIsDone fix.
        val op = CaltopoOp(null)
        op.responseCode = 400
        op.response = "Personal map request failed."
        op.quietErrorCodes = intArrayOf(400, 404)
        op.goodResponse = false
        op.setOperationIsDone(false)
        assertFalse(op.success())
        assertTrue(op.fail())
        assertEquals(400, op.responseCode)
    }

    private fun complete(op: CaltopoOp, code: Int, success: Boolean, body: String) {
        op.responseCode = code
        op.response = body
        if (success) {
            op.responseJson = JSONObject(body).optJSONObject("result") ?: JSONObject()
        }
        op.setOperationIsDone(success)
    }
}
