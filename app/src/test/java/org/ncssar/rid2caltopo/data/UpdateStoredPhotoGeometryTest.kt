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
 * Post-publish geometry edit must POST identical Point coords to Marker and MapMediaObject,
 * never re-upload /media/.../data, and defer when the media link is still missing.
 */
class UpdateStoredPhotoGeometryTest {
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
    fun refineAfterPublishPostsIdenticalCoordsToMarkerAndMapMediaObject() {
        val markerId = "19fe1364-dc45-4275-a6ae-7fcfef8aef78"
        val linkId = CaltopoSession.defaultStoredPhotoLinkId(markerId)
        val calls = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<JSONObject>()
        val lat = 47.601
        val lng = -122.332

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> {
                    bodies.add(JSONObject(op.payload.toString()))
                    complete(op, 200, true, okBody)
                }
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> {
                    val existing = """{"status":"ok","result":{"id":"$linkId","type":"Feature","geometry":{"type":"Point","coordinates":[-122.33,47.6]},"properties":{"class":"MapMediaObject","parentId":"Marker:$markerId","backendMediaId":"media-1","title":"keep","marker-symbol":"aperture"}}}"""
                    complete(op, 200, true, existing)
                }
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> {
                    bodies.add(JSONObject(op.payload.toString()))
                    complete(op, 200, true, okBody)
                }
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.UpdateStoredPhotoGeometry(
            "MAP01", "TEAM01", markerId, null, lat, lng,
            "clue", "desc", "folder", 1L, null,
        )
        outer.get(5, TimeUnit.SECONDS)
        assertTrue(outer.success())
        assertEquals(
            listOf(
                "POST /api/v1/map/MAP01/Marker/$markerId",
                "GET /api/v1/map/MAP01/MapMediaObject/$linkId",
                "POST /api/v1/map/MAP01/MapMediaObject/$linkId",
            ),
            calls.toList(),
        )
        assertFalse(calls.any { it.contains("/media/") })
        assertEquals(2, bodies.size)
        val markerCoords = bodies[0].getJSONObject("geometry").getJSONArray("coordinates")
        val linkCoords = bodies[1].getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(lng, markerCoords.getDouble(0), 1e-9)
        assertEquals(lat, markerCoords.getDouble(1), 1e-9)
        assertEquals(markerCoords.getDouble(0), linkCoords.getDouble(0), 1e-9)
        assertEquals(markerCoords.getDouble(1), linkCoords.getDouble(1), 1e-9)
        val linkProps = bodies[1].getJSONObject("properties")
        assertEquals("MapMediaObject", linkProps.getString("class"))
        assertEquals("Marker:$markerId", linkProps.getString("parentId"))
        assertEquals("media-1", linkProps.getString("backendMediaId"))
        assertEquals("keep", linkProps.getString("title"))
    }

    @Test
    fun deferredRefineFailsWhenMediaLinkMissing() {
        val markerId = "61b0ed41-00b5-42bb-9d84-82ccd4e53d82"
        val linkId = CaltopoSession.defaultStoredPhotoLinkId(markerId)
        val calls = CopyOnWriteArrayList<String>()

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> complete(op, 200, true, okBody)
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" ->
                    complete(op, 404, false, "absent")
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.UpdateStoredPhotoGeometry(
            "MAP01", "TEAM01", markerId, null, 47.0, -122.0,
            "clue", "desc", "folder", 1L, null,
        )
        outer.get(5, TimeUnit.SECONDS)
        assertFalse(outer.success())
        assertEquals("link-missing", outer.response)
        assertFalse(calls.any { it.startsWith("POST /api/v1/map/MAP01/MapMediaObject/") })
        assertFalse(calls.any { it.contains("/media/") })
    }

    @Test
    fun reconcileRepairsDriftedPhotoWithoutMediaReupload() {
        val markerId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val linkId = UUID.nameUUIDFromBytes("$markerId:link".toByteArray(Charsets.UTF_8)).toString()
        val calls = CopyOnWriteArrayList<String>()
        var repairedPayload: JSONObject? = null

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> {
                    val body = """{"status":"ok","result":{"id":"$markerId","type":"Feature","geometry":{"type":"Point","coordinates":[-122.332,47.601]},"properties":{"class":"Marker"}}}"""
                    complete(op, 200, true, body)
                }
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> {
                    // Drifted ~11 m south of the Marker.
                    val body = """{"status":"ok","result":{"id":"$linkId","type":"Feature","geometry":{"type":"Point","coordinates":[-122.332,47.6009]},"properties":{"class":"MapMediaObject","parentId":"Marker:$markerId","backendMediaId":"media-x","title":"photo"}}}"""
                    complete(op, 200, true, body)
                }
                op.method == CaltopoSession.CtsMethod_t.POST &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> {
                    repairedPayload = JSONObject(op.payload.toString())
                    complete(op, 200, true, okBody)
                }
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.RepairDriftedStoredPhotoLink("MAP01", "TEAM01", markerId, linkId, null)
        outer.get(5, TimeUnit.SECONDS)
        assertTrue(outer.success())
        assertEquals("repaired", outer.response)
        assertFalse(calls.any { it.contains("/media/") })
        assertEquals(3, calls.size)
        val coords = repairedPayload!!.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(-122.332, coords.getDouble(0), 1e-9)
        assertEquals(47.601, coords.getDouble(1), 1e-9)
        assertEquals("photo", repairedPayload!!.getJSONObject("properties").getString("title"))
        assertEquals("media-x", repairedPayload!!.getJSONObject("properties").getString("backendMediaId"))
    }

    @Test
    fun alignedPhotoSkipsRepairPost() {
        val markerId = "b2c3d4e5-f6a7-8901-bcde-f12345678901"
        val linkId = CaltopoSession.defaultStoredPhotoLinkId(markerId)
        val calls = CopyOnWriteArrayList<String>()

        CaltopoSession.setPhotoExchangeForTesting { op ->
            val key = "${op.method} ${op.url}"
            calls.add(key)
            when {
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/Marker/$markerId" -> {
                    complete(
                        op, 200, true,
                        """{"status":"ok","result":{"geometry":{"type":"Point","coordinates":[-122.0,47.0]},"properties":{}}}""",
                    )
                }
                op.method == CaltopoSession.CtsMethod_t.GET &&
                    op.url == "/api/v1/map/MAP01/MapMediaObject/$linkId" -> {
                    complete(
                        op, 200, true,
                        """{"status":"ok","result":{"geometry":{"type":"Point","coordinates":[-122.0,47.0]},"properties":{"class":"MapMediaObject"}}}""",
                    )
                }
                else -> error("Unexpected PhotoOp $key")
            }
        }

        val outer = CaltopoSession.RepairDriftedStoredPhotoLink("MAP01", "TEAM01", markerId, null, null)
        outer.get(5, TimeUnit.SECONDS)
        assertTrue(outer.success())
        assertEquals("aligned", outer.response)
        assertFalse(calls.any { it.startsWith("POST ") })
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
