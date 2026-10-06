package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RidFlightIdTest {
    // Shared vector: apple/Tests/R2CCoreTests/ClueBindingTests.swift flightIDMatchesAndroidForTheSameDroneAndStart
    // asserts the same id for the same input, so iOS and Android archives cross-match by r2c_flight_id.
    @Test fun matchesIosForTheSameDroneAndStart() {
        val expected = "c61d005e-78b2-57bc-b1ab-c3e23d8d182f"
        assertEquals(expected, RidFlightId.make("1581F5FJC23A0012345", 1_791_234_567_890L))
        assertEquals(expected, RidFlightId.make("1581f5fj-c23a 0012345", 1_791_234_567_890L))
        assertNotEquals(expected, RidFlightId.make("1581F5FJC23A0012345", 1_791_234_567_891L))
        assertNotEquals(expected, RidFlightId.make("1581F5FJC23A0012346", 1_791_234_567_890L))
        val uuid = UUID.fromString(expected)
        assertEquals(5, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals("RID01", RidFlightId.canonicalAircraftId("rid-01 "))
    }

    @Test fun oldRandomIdArchivesStillLoad() {
        val random = UUID.randomUUID().toString()
        val geoJson = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray()
                .put(JSONArray().put(-121.1).put(39.1).put(100).put(1_790_553_873_000L))
                .put(JSONArray().put(-121.2).put(39.2).put(101).put(1_790_553_874_000L))))
            .put("properties", JSONObject().put("title", "1SAR7").put("remote_id", "RID-1").put("r2c_flight_id", random))))
            .toString().toByteArray()
        assertEquals(random, FlightArchiveRebuild.flightId(geoJson))
        assertEquals(2, FlightArchiveRebuild.decode(geoJson)!!.points.size)
        // Late clues: a matching flight id wins; otherwise (old files, unknown id) the time rule decides.
        val stable = RidFlightId.make("RID-1", 1_790_553_873_000L)
        val old = AwaitingMapClueMatch.Candidate(random, "1SAR7", listOf(1_790_553_873_000L, 1_790_553_874_000L))
        val new = AwaitingMapClueMatch.Candidate(stable, "1SAR7", listOf(1_790_553_990_000L, 1_790_553_991_000L))
        assertEquals(stable, AwaitingMapClueMatch.ownerId("1SAR7", 1_790_553_873_500L, stable, listOf(old, new)))
        assertEquals(random, AwaitingMapClueMatch.ownerId("1SAR7", 1_790_553_873_500L, "unknown-flight", listOf(old, new)))
        assertEquals(random, AwaitingMapClueMatch.ownerId("1SAR7", 1_790_553_873_500L, null, listOf(old, new)))
        assertNull(AwaitingMapClueMatch.ownerId("1SAR7", 0L, "unknown-flight", emptyList()))
    }
}
