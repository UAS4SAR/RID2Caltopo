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
        CluePhotoUploadPolicy.testingFailureLogSink = null
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
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" ->
                    complete(op, 404, false, "absent")
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
                "GET /api/v1/map/MAP01/MapMediaObject/$linkId",
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
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" ->
                    complete(op, 404, false, "absent")
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
        assertTrue(calls.any { it == "GET /api/v1/map/MAP01/MapMediaObject/$linkId" })
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

    @Test
    fun existingBackendMediaCreate400SkipsRecreateAndLinks() {
        val markerId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        val calls = CopyOnWriteArrayList<String>()
        val readyMedia = """{"status":"ok","result":{"id":"$mediaId","properties":{"creator":"TEAM01","mediaIsReady":true},"metadata":{"filesize":12}}}"""

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$mediaId" -> {
                    // First probe: absent. After create 400, recovery GET: ready.
                    val priorPosts = calls.count {
                        it == "POST /api/v1/media/$mediaId"
                    }
                    if (priorPosts == 0) {
                        complete(op, 400, false, "Personal map request failed.")
                    } else {
                        complete(op, 200, true, readyMedia)
                    }
                }
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId" -> {
                    op.serverResponseBody = """{"message":"Cannot update existing backendMedia"}"""
                    complete(op, 400, false, """{"message":"Cannot update existing backendMedia"}""")
                }
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" ->
                    complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "stuck clue", "photo", "folder", 1L, byteArrayOf(1, 2, 3), null,
        )
        outer.get(5, TimeUnit.SECONDS)
        assertTrue("publish should treat existing-media 400 as success path", outer.success())
        assertFalse(calls.any { it == "POST /api/v1/media/$mediaId/data" })
        assertTrue(calls.any { it == "POST /api/v1/map/MAP01/MapMediaObject/$linkId" })
    }

    @Test
    fun existingBackendMediaData400ContinuesWhenReady() {
        val markerId = "b2c3d4e5-f6a7-8901-bcde-f12345678901"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        val calls = CopyOnWriteArrayList<String>()
        val notReady = """{"status":"ok","result":{"id":"$mediaId","properties":{"creator":"TEAM01","mediaIsReady":false},"metadata":{"filesize":0}}}"""
        val ready = """{"status":"ok","result":{"id":"$mediaId","properties":{"creator":"TEAM01","mediaIsReady":true},"metadata":{"filesize":12}}}"""
        var mediaGets = 0

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$mediaId" -> {
                    mediaGets += 1
                    complete(op, 200, true, if (mediaGets == 1) notReady else ready)
                }
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId/data" -> {
                    assertTrue(op.callTimeoutSeconds >= 45)
                    op.serverResponseBody = """{"message":"Cannot update existing backendMedia"}"""
                    complete(op, 400, false, """{"message":"Cannot update existing backendMedia"}""")
                }
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" ->
                    complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "stuck clue", "photo", "folder", 1L, ByteArray(900_000) { 1 }, null,
        )
        outer.get(5, TimeUnit.SECONDS)
        assertTrue(outer.success())
        assertTrue(calls.any { it == "POST /api/v1/map/MAP01/MapMediaObject/$linkId" })
    }

    /** Scenario C: media POST timed out after landing → retry 400-existing + GET 404 → new media id. */
    @Test
    fun timedOutMediaCreateThen400ExistingPlus404ForcesNewMediaId() {
        val markerId = "3dc2165a-0000-4000-8000-000000000001"
        val badMediaId = "bad-media-id-0001"
        val freshMediaId = "fresh-media-id-0002"
        val freshLinkId = "fresh-link-id-0002"
        val calls = CopyOnWriteArrayList<String>()

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$badMediaId" -> complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$badMediaId" -> {
                    op.serverResponseBody = """{"message":"Cannot update existing backendMedia"}"""
                    complete(op, 400, false, """{"message":"Cannot update existing backendMedia"}""")
                }
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val stuck = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "C", "clue", "folder", 1L, byteArrayOf(1, 2, 3), badMediaId, badMediaId, null,
        )
        stuck.get(5, TimeUnit.SECONDS)
        assertFalse(stuck.success())
        assertEquals(409, stuck.responseCode)
        assertTrue(
            CluePhotoUploadPolicy.isAmbiguousMediaState(stuck.responseCode, stuck.response),
        )

        // Heal with a NEW media id linked to the EXISTING marker.
        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$freshMediaId" -> complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$freshMediaId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$freshMediaId/data" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$freshLinkId" ->
                    complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$freshLinkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp $key")
            }
        }
        val healed = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "C", "clue", "folder", 1L, byteArrayOf(1, 2, 3), freshMediaId, freshLinkId, null,
        )
        healed.get(5, TimeUnit.SECONDS)
        assertTrue(healed.success())
        assertTrue(calls.any { it == "POST /api/v1/media/$freshMediaId" })
        assertTrue(calls.any { it == "POST /api/v1/map/MAP01/MapMediaObject/$freshLinkId" })
        assertFalse(calls.any { it.contains(badMediaId) && it.startsWith("POST /api/v1/media/") && it.endsWith("/data") })
    }

    @Test
    fun cluePublishOmitsBlankFolderId() {
        val markerId = "folder-defer-0001-0000-0000-000000000001"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        var markerPayload = ""

        CaltopoSession.setPhotoExchangeForTesting { op ->
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> {
                    markerPayload = op.payload?.toString().orEmpty()
                    complete(op, 200, true, okBody)
                }
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$mediaId" -> complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId/data" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" ->
                    complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp ${op.method} ${op.url}")
            }
        }

        val outer = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "pre-flight", "clue", "", 1L, byteArrayOf(1, 2, 3), null,
        )
        outer.get(5, TimeUnit.SECONDS)
        assertTrue(outer.success())
        assertFalse(markerPayload.contains("\"folderId\""))
    }

    @Test
    fun reconcileSucceedsWhenAllPartsPresentWithoutRepost() {
        val markerId = "c3d4e5f6-a7b8-9012-cdef-123456789012"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        val calls = CopyOnWriteArrayList<String>()
        val readyMedia = """{"status":"ok","result":{"id":"$mediaId","properties":{"creator":"TEAM01","mediaIsReady":true},"metadata":{"filesize":12}}}"""

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$mediaId" -> complete(op, 200, true, readyMedia)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.ReconcileStoredPhoto("MAP01", "TEAM01", markerId, linkId, null)
        outer.get(5, TimeUnit.SECONDS)
        assertTrue(outer.success())
        assertEquals(200, outer.responseCode)
        assertFalse(calls.any { it.startsWith("POST ") })
    }

    /** Field regression: media create taking ~20s must succeed on attempt 1 (v2.5.4 had 60s/request). */
    @Test
    fun mediaPostTaking20SecondsSucceedsOnFirstAttempt() {
        val markerId = "d4e5f6a7-b8c9-0123-def0-234567890123"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        val timeouts = CopyOnWriteArrayList<Pair<String, Int>>()

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            timeouts.add(key to op.callTimeoutSeconds)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/media/$mediaId" -> complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId" -> {
                    Thread.sleep(20_000L)
                    complete(op, 200, true, okBody)
                }
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/media/$mediaId/data" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" ->
                    complete(op, 404, false, "absent")
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> complete(op, 200, true, okBody)
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val started = System.currentTimeMillis()
        val outer = CaltopoSession.PublishStoredPhoto(
            "MAP01", "TEAM01", markerId, 47.0, -122.0,
            "slow-media", "clue", "folder", 1L, byteArrayOf(1, 2, 3), null,
        )
        outer.get(45, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("publish should succeed after 20s media-post", outer.success())
        assertTrue("elapsed=$elapsed", elapsed >= 20_000L)
        val mediaPost = timeouts.firstOrNull { it.first == "POST /api/v1/media/$mediaId" }
        assertEquals(CluePhotoUploadPolicy.JSON_TIMEOUT_SECONDS, mediaPost?.second)
        assertTrue((mediaPost?.second ?: 0) >= 20)
    }

    @Test
    fun clueStepFailureLogIncludesStepName() {
        val markerId = "e5f6a7b8-c9d0-1234-ef01-345678901234"
        val mediaId = UUID.nameUUIDFromBytes("$markerId:media".toByteArray(Charsets.UTF_8)).toString()
        val logs = CopyOnWriteArrayList<String>()
        CluePhotoUploadPolicy.testingFailureLogSink = { logs.add(it) }
        try {
            CaltopoSession.setPhotoExchangeForTesting { op ->
                when {
                    op.method == CaltopoSession.CtsMethod_t.POST &&
                        op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                    op.method == CaltopoSession.CtsMethod_t.GET &&
                        op.url == "/api/v1/media/$mediaId" -> complete(op, 404, false, "absent")
                    op.method == CaltopoSession.CtsMethod_t.POST &&
                        op.url == "/api/v1/media/$mediaId" ->
                        complete(op, 0, false, "CalTopo server unavailable (timed out)")
                    else -> error("Unexpected PhotoOp ${op.method} ${op.url}")
                }
            }
            val outer = CaltopoSession.PublishStoredPhoto(
                "MAP01", "TEAM01", markerId, 47.0, -122.0,
                "fail-log", "clue", "folder", 1L, byteArrayOf(1), null,
            )
            outer.get(5, TimeUnit.SECONDS)
            assertFalse(outer.success())
            assertTrue("logs=$logs", logs.any { it.contains("step=media-post") })
            assertTrue(logs.any { it.contains("method=POST") })
            assertTrue(logs.any { it.contains("timeoutSeconds=60") })
        } finally {
            CluePhotoUploadPolicy.testingFailureLogSink = null
        }
    }

    private fun complete(op: CaltopoOp, code: Int, success: Boolean, body: String) {
        op.responseCode = code
        op.response = body
        if (op.serverResponseBody == null) op.serverResponseBody = body
        if (success) {
            op.responseJson = JSONObject(body).optJSONObject("result") ?: JSONObject()
        }
        op.setOperationIsDone(success)
    }
}
