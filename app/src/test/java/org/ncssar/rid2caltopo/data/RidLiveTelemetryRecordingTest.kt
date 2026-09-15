package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RidLiveTelemetryRecordingTest {
    @Test
    fun nearbyLiveUpdatesReachMapWithoutAddingEverySampleToArchive() {
        val fixture = TestR2cRuntimeFactory.create("rid-live-recording")
        fixture.setAsDefaultRuntime()
        CaltopoClient.ResetPersistedClientState()
        CtDroneSpec.ClearMyLocationBaselineForTests()
        CaltopoMap.MyLocation = null
        CaltopoMap.SetMyLocationOverride(null)
        val heights = mutableListOf<Double>()
        val listener = CaltopoLiveTrack.LocalTrackListener { _, _, _, _, altitude, _ -> heights.add(altitude) }
        CaltopoLiveTrack.AddLocalTrackListener(listener)
        try {
            val client = CaltopoClient.ClientForRemoteId("LIVERECORDING")
            val spec = client.droneSpec
            for (step in 0..60) {
                val now = 10_000L + step * 100L
                val lat = 39.0 + (step % 2) * 0.000001
                assertTrue(spec.checkNewWaypoint(lat, -121.0, 100.0 + step, now, now,
                    true, CtDroneSpec.TransportTypeEnum.WIFI))
                client.newWaypoint(lat, -121.0, 100.0 + step, now, CtDroneSpec.TransportTypeEnum.WIFI, true)
            }
            assertEquals(61, heights.size)
            assertEquals(160.0, heights.last(), 0.00001)
            assertEquals(3, WaypointTrack.GetTrackPointsSnapshot(spec).size)
        } finally {
            CaltopoLiveTrack.RemoveLocalTrackListener(listener)
            CaltopoClient.ResetPersistedClientState()
            R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
        }
    }
}
