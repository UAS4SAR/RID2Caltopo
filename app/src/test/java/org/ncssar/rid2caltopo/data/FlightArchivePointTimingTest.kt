package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightArchivePointTimingTest {
    private val t0 = 1_790_553_873_000L

    private fun coordinates(count: Int): JSONArray {
        val coords = JSONArray()
        for (i in 0 until count) {
            coords.put(JSONArray().put(-121.0 - i * 0.0001).put(39.0 + i * 0.0001).put(100).put(t0 + i * 1_000L))
        }
        return coords
    }

    private fun featureCollection(properties: JSONObject, coords: JSONArray): ByteArray =
        JSONObject()
            .put("type", "FeatureCollection")
            .put(
                "features",
                JSONArray().put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", properties.put("title", "1SAR7_x").put("r2c_prop", JSONObject().put("rid", "RID-1")))
                        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords)),
                ),
            )
            .toString()
            .toByteArray()

    private fun samplePoints(count: Int = 120): List<ClueBindingPoint> =
        (0 until count).map { i ->
            val drone = i % 17 != 0
            ClueBindingPoint(
                timeMs = t0 + i * 1_000L,
                receivedAtMs = if (i % 23 == 0) null else t0 + i * 1_000L + 150 + (i % 7),
                latitude = 39.0 + i * 0.0001,
                longitude = -121.0 - i * 0.0001,
                altitudeMeters = 100.0,
                source = "track",
                droneClock = drone,
            )
        }

    @Test fun compactAndLegacyDecodeToIdenticalBindingPoints() {
        val points = samplePoints()
        val coords = coordinates(points.size)
        val legacyProps = JSONObject()
            .put("r2c_point_received_ms", JSONArray().also { arr ->
                points.forEach { arr.put(it.receivedAtMs ?: JSONObject.NULL) }
            })
            .put("r2c_point_drone_clock", JSONArray().also { arr ->
                points.forEach { arr.put(it.droneClock) }
            })
        val compactProps = JSONObject()
        FlightArchivePointTiming.write(
            compactProps,
            points.map { it.receivedAtMs },
            points.map { it.droneClock },
            points.size,
        )
        assertFalse(compactProps.has(FlightArchivePointTiming.LEGACY_RECEIVED_MS))
        assertFalse(compactProps.has(FlightArchivePointTiming.LEGACY_DRONE_CLOCK))
        assertTrue(compactProps.has(FlightArchivePointTiming.RECV_BASE_MS))
        assertTrue(compactProps.has(FlightArchivePointTiming.NO_DRONE_CLOCK))

        val legacy = FlightArchiveRebuild.decode(featureCollection(legacyProps, coords))!!
        val compact = FlightArchiveRebuild.decode(featureCollection(compactProps, coords))!!
        assertEquals(legacy.points, compact.points)
        assertEquals(points.map { it.receivedAtMs }, compact.points.map { it.receivedAtMs })
        assertEquals(points.map { it.droneClock }, compact.points.map { it.droneClock })

        val capture = t0 + 61_250L
        val legacyBind = ClueBinder.bind("RID-1", "flight-1", capture, "stream-pts", capture + 400, legacy.points, null, false)
        val compactBind = ClueBinder.bind("RID-1", "flight-1", capture, "stream-pts", capture + 400, compact.points, null, false)
        assertEquals(legacyBind, compactBind)
        assertEquals(legacyBind.offsetMs, compactBind.offsetMs)
        assertEquals(legacyBind.waypoint, compactBind.waypoint)
    }

    @Test fun compactEncodingIsSmallerThanLegacyArrays() {
        val points = samplePoints(274)
        val legacy = JSONObject()
            .put("r2c_point_received_ms", JSONArray().also { arr -> points.forEach { arr.put(it.receivedAtMs ?: JSONObject.NULL) } })
            .put("r2c_point_drone_clock", JSONArray().also { arr -> points.forEach { arr.put(it.droneClock) } })
        val compact = JSONObject()
        FlightArchivePointTiming.write(compact, points.map { it.receivedAtMs }, points.map { it.droneClock }, points.size)
        val legacyBytes = legacy.toString().toByteArray().size
        val compactBytes = compact.toString().toByteArray().size
        assertTrue("compact=$compactBytes legacy=$legacyBytes", compactBytes < legacyBytes)
        // Typical long flights drop well over 30% of the timing property payload.
        assertTrue(compactBytes < legacyBytes * 70 / 100)
    }

    @Test fun allTrueDroneClockOmitsSparseListAndAllNullReceiveOmitsOffsets() {
        val props = JSONObject()
        FlightArchivePointTiming.write(props, listOf(null, null), listOf(true, true), 2)
        assertFalse(props.has(FlightArchivePointTiming.RECV_BASE_MS))
        assertFalse(props.has(FlightArchivePointTiming.RECV_OFF_MS))
        assertFalse(props.has(FlightArchivePointTiming.NO_DRONE_CLOCK))
        assertEquals(listOf(null, null), FlightArchivePointTiming.readReceivedAtMs(props, 2))
        assertEquals(listOf(true, true), FlightArchivePointTiming.readDroneClock(props, 2))
        assertNull(FlightArchivePointTiming.readReceivedAtMs(JSONObject(), 1).single())
    }
}
