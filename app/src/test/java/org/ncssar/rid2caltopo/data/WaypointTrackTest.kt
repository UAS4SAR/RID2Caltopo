package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.app.R2CActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WaypointTrackTest {
    private lateinit var trackMapField: Field
    private lateinit var mapNodeField: Field
    private var originalTrackMap: MutableMap<Any, Any>? = null
    private var originalMapNode: Any? = null
    private var originalWaypointCount: Int = 0

    @Before
    fun setUp() {
        trackMapField = WaypointTrack::class.java.getDeclaredField("TrackMap").apply {
            isAccessible = true
        }
        mapNodeField = CaltopoMap::class.java.getDeclaredField("MapNode").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        originalTrackMap = HashMap(trackMapField.get(null) as MutableMap<Any, Any>)
        originalMapNode = mapNodeField.get(null)
        originalWaypointCount = WaypointTrack.WaypointCount
        resetTracks()
        mapNodeField.set(null, null)
        CtDroneSpec.MyLat = 0.0
        CtDroneSpec.MyLng = 0.0
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        val trackMap = trackMapField.get(null) as MutableMap<Any, Any>
        trackMap.clear()
        originalTrackMap?.let { trackMap.putAll(it) }
        WaypointTrack.WaypointCount = originalWaypointCount
        mapNodeField.set(null, originalMapNode)
        CtDroneSpec.MyLat = 0.0
        CtDroneSpec.MyLng = 0.0
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun addWaypointForTrack_recordsSnapshotInLatLngAltitudeTimestampOrder() {
        val drone = activeDrone("RID123", "RID123")

        WaypointTrack.AddWaypointForTrack(drone, 39.153061, -121.132946, 101L, 12_345L)

        val points = WaypointTrack.GetTrackPointsSnapshot(drone)
        assertEquals(1, points.size)
        assertEquals(39.153061, points[0].lat, 0.000001)
        assertEquals(-121.132946, points[0].lng, 0.000001)
        assertEquals(101.0, points[0].ele, 0.0)
        assertEquals(12_345L, points[0].timestampMsec)
        assertEquals(1, WaypointTrack.WaypointCount)
    }

    @Test
    fun routingDesignatorChange_keepsPilotCallsignTrackLabel() {
        val drone = activeDrone("RID456", "RID456")
        val oldTrackLabel = drone.trackLabel()
        WaypointTrack.AddWaypointForTrack(drone, 39.153061, -121.132946, 101L, 12_345L)

        drone.setMappedId("1sar7DjMn4Pr")

        assertEquals(oldTrackLabel, drone.trackLabel())
        val points = WaypointTrack.GetTrackPointsSnapshot(drone)
        assertEquals(1, points.size)
        assertEquals(12_345L, points.single().timestampMsec)
    }

    @Test
    fun renameTrack_mergesIntoExistingDestinationTrack() {
        val alpha = activeDrone("RID-A", "RID-A", "Pilot Alpha")
        WaypointTrack.AddWaypointForTrack(alpha, 39.153000, -121.132000, 100L, 1_000L)

        val bravo = activeDrone("RID-B", "RID-B", "Pilot Bravo")
        WaypointTrack.AddWaypointForTrack(bravo, 39.154000, -121.133000, 101L, 2_000L)

        WaypointTrack.RenameTrack(bravo.trackLabel(), alpha.trackLabel(), alpha)

        val points = WaypointTrack.GetTrackPointsSnapshot(alpha)
        assertEquals(2, points.size)
        assertEquals(listOf(1_000L, 2_000L), points.map { it.timestampMsec })
        assertEquals(emptyList<WaypointTrack.TrackPoint>(), WaypointTrack.GetTrackPointsSnapshot(bravo))
    }

    @Test
    fun getGeoJson_includesDeviceAndBuildMetadataInR2cProp() {
        val originalDeviceName = R2CActivity.MyDeviceName
        R2CActivity.MyDeviceName = "Field Tablet Alpha"
        try {
            val drone = activeDrone("RID-META", "RID-META")
            val track = WaypointTrack(drone.trackLabel(), drone)
            track.addWaypoint(39.153000, -121.132000, 100L, 1_000L)

            val r2cProp = track.getGeoJson()!!
                .getJSONArray("features")
                .getJSONObject(0)
                .getJSONObject("properties")
                .getJSONObject("r2c_prop")

            assertEquals("Field Tablet Alpha", r2cProp.getString("device_name"))
            assertEquals(BuildConfig.BUILD_VERSION, r2cProp.getString("BUILD_VERSION"))
            assertEquals(BuildConfig.BUILD_TIME, r2cProp.getString("BUILD_TIME"))
        } finally {
            R2CActivity.MyDeviceName = originalDeviceName
        }
    }

    @Test
    fun shouldPublishGeoJsonStatsForTracker_requiresDroneOrgToMatchTrackerOrgUppercase() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        confirmTeamDrone("RIDTEAM1", "NCSSAR")

        assertTrue(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid(" ncssar ", "RIDTEAM1")))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("MUTUALAID", "RIDTEAM1")))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("", "RIDTEAM1")))
    }

    @Test
    fun shouldPublishGeoJsonStatsForTracker_allowsMapMatchedMutualAidTrackerOrg() {
        mapNodeField.set(null, CaltopoNode.MapNode("map-ncssar", "NCSSAR Search", 0L))
        confirmTeamDrone("RIDTEAM2", "NCSSAR")
        CaltopoClient.UpsertCaltopoProfile(
            CaltopoProfileRecord(
                "ma-ncssar",
                "NCSSAR Mutual Aid",
                "MUTUAL_AID",
                CaltopoCredentials(),
                "caltopo.com",
                "Drone Tracks",
                "Training",
                "1",
                "tracker-token",
                "https://tracker.example.org",
                false,
                0L,
                false,
                "NCSSAR",
                "map-ncssar",
                "NCSSAR Search",
                "",
                0L,
                ""
            ),
            true,
            false
        )

        assertTrue(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("NCSSAR", "RIDTEAM2")))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("OTHER", "RIDTEAM2")))
    }

    @Test
    fun shouldPublishGeoJsonStatsForTracker_usesOrgConfigSourceLabelForLegacyHomeTrackerProfile() {
        confirmTeamDrone("RIDTEAM3", "NCSSAR")
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetMutualAidTemplate(
            MutualAidTemplateRecord(
                "team-id",
                "credential-id",
                "credential-secret",
                "caltopo.com",
                "NCSSAR",
                "MAI"
            )
        )

        assertTrue(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("NCSSAR", "RIDTEAM3")))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("OTHER", "RIDTEAM3")))
    }

    @Test
    fun shouldPublishGeoJsonStatsForTracker_requiresRemoteIdToMatchTeamDrone() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        confirmTeamDrone("RIDTEAM4", "NCSSAR")
        confirmTeamDrone("RIDTEAM5", "OTHER")

        assertTrue(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("NCSSAR", "RIDTEAM4")))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("NCSSAR", "RIDUNKNOWN")))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("NCSSAR", "")))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJsonWithOrgAndRid("NCSSAR", "RIDTEAM5")))
    }

    @Test
    fun shouldPublishGeoJsonStatsForTracker_recoversLegacyConfirmedTeamTrack() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        val legacyConfirmedTrack = geoJsonWithOrgAndRid("NCSSAR", "RIDLEGACY1")
        legacyConfirmedTrack.getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("properties")
            .getJSONObject("r2c_prop")
            .put("mid", "1sar7DjMn4Pr")

        assertTrue(
            WaypointTrack.ShouldPublishGeoJsonStatsForTracker(
                legacyConfirmedTrack
            )
        )
        assertFalse(
            WaypointTrack.ShouldPublishGeoJsonStatsForTracker(
                geoJsonWithOrgAndRid("NCSSAR", "RIDUNKNOWN")
            )
        )
    }

    @Test
    fun prepareForArchive_preservesAuthorizedUploadAfterFlightConfirmationClears() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        confirmTeamDrone("RIDSNAPSHOT1", "NCSSAR")
        val drone = activeDrone("RIDSNAPSHOT1", "1sar7DjMn4Pr")
        val track = WaypointTrack(drone.trackLabel(), drone)
        track.addWaypoint(39.153000, -121.132000, 100L, 1_000L)

        track.prepareForArchive()
        CaltopoClient.ClearCurrentPeerDroneConfirmation("RIDSNAPSHOT1")
        drone.reset()

        val geoJson = track.getGeoJson()!!
        val r2cProp = geoJson.getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("properties")
            .getJSONObject("r2c_prop")
        assertTrue(r2cProp.getBoolean("tracker_upload_authorized"))
        assertEquals("RIDSNAPSHOT1", r2cProp.getString("rid"))
        assertEquals("1sar7DjMn4Pr", r2cProp.getString("mid"))
        assertTrue(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJson))
    }

    @Test
    fun prepareForArchive_doesNotAuthorizeTrackConfirmedAfterFlightEnds() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        val drone = activeDrone("RIDUNCONFIRMED1", "RIDUNCONFIRMED1")
        val track = WaypointTrack(drone.trackLabel(), drone)

        track.prepareForArchive()
        confirmTeamDrone("RIDUNCONFIRMED1", "NCSSAR")

        val geoJson = track.getGeoJson()!!
        val r2cProp = geoJson.getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("properties")
            .getJSONObject("r2c_prop")
        assertFalse(r2cProp.getBoolean("tracker_upload_authorized"))
        assertFalse(WaypointTrack.ShouldPublishGeoJsonStatsForTracker(geoJson))
    }

    @Test
    fun shouldMarkGeoJsonStatsReportedForResponse_doesNotReportLocalUploadSkip() {
        assertFalse(WaypointTrack.ShouldMarkGeoJsonStatsReportedForResponse(WaypointTrack.GEOJSON_STATS_UPLOAD_SKIPPED))
        assertFalse(WaypointTrack.ShouldMarkGeoJsonStatsReportedForResponse(408))
        assertFalse(WaypointTrack.ShouldMarkGeoJsonStatsReportedForResponse(429))
        assertFalse(WaypointTrack.ShouldMarkGeoJsonStatsReportedForResponse(503))
        assertTrue(WaypointTrack.ShouldMarkGeoJsonStatsReportedForResponse(200))
        assertTrue(WaypointTrack.ShouldMarkGeoJsonStatsReportedForResponse(409))
    }

    @Test
    fun publishGeoJsonStatsWithRetryAsyncForTesting_returnsBeforeSlowTrackerCompletes() {
        CaltopoClient.SetHomeOrgName("NCSSAR")
        confirmTeamDrone("RIDTEAMASYNC", "NCSSAR")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publisher = TrackerPublisher {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            200
        }
        val fixture = TestR2cRuntimeFactory.create("async-archive")
        R2cRuntimeRegistry.setDefaultRuntimeForTesting(
            R2cRuntime(
                "async-archive",
                fixture.peerCoordinator,
                fixture.calTopoSessionGateway,
                publisher
            )
        )
        try {
            val future = WaypointTrack.PublishGeoJsonStatsWithRetryAsyncForTesting(
                geoJsonWithOrgAndRid("NCSSAR", "RIDTEAMASYNC").toString(),
                "test async publish"
            )

            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(future.isDone)
            release.countDown()
            assertEquals(200, future.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
        }
    }

    @Test
    fun archiveTrack_detachesTrackAndReturnsBeforeArchiveWorkCompletes() {
        val drone = activeDrone("RID-ARCHIVE-ASYNC", "RID-ARCHIVE-ASYNC")
        val track = BlockingArchiveTrack(drone.trackLabel(), drone)
        @Suppress("UNCHECKED_CAST")
        val trackMap = trackMapField.get(null) as MutableMap<String, WaypointTrack>
        trackMap[drone.trackLabel()] = track
        val caller = Executors.newSingleThreadExecutor()
        try {
            val archiveCall = caller.submit {
                WaypointTrack.ArchiveTrack(drone.trackLabel())
            }

            assertTrue(track.archiveStarted.await(2, TimeUnit.SECONDS))
            archiveCall.get(2, TimeUnit.SECONDS)
            assertFalse(trackMap.containsKey(drone.trackLabel()))
            assertFalse(track.archiveReleased)
        } finally {
            track.releaseArchive.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun isTrackDirectoryWithinRecentDays_includesTodayAndPreviousNDaysOnly() {
        val today = LocalDate.of(2026, 6, 5)

        assertTrue(WaypointTrack.IsTrackDirectoryWithinRecentDays("tracks-05Jun2026", 2, today))
        assertTrue(WaypointTrack.IsTrackDirectoryWithinRecentDays("tracks-04Jun2026", 2, today))
        assertFalse(WaypointTrack.IsTrackDirectoryWithinRecentDays("tracks-03Jun2026", 2, today))
        assertFalse(WaypointTrack.IsTrackDirectoryWithinRecentDays("tracks-06Jun2026", 2, today))
        assertFalse(WaypointTrack.IsTrackDirectoryWithinRecentDays("cache", 2, today))
    }

    @Test
    fun isTrackFileActive_usesCurrentTimeInsteadOfReportMarkerTime() {
        val hourMs = TimeUnit.HOURS.toMillis(1)
        val now = 10 * hourMs

        assertTrue(WaypointTrack.IsTrackFileActive(now - hourMs + 1, now))
        assertFalse(WaypointTrack.IsTrackFileActive(now - hourMs, now))
        assertFalse(WaypointTrack.IsTrackFileActive(0, now))
    }

    private fun activeDrone(
        remoteId: String,
        mappedId: String,
        pilotCallsign: String = "Pilot"
    ): CtDroneSpec {
        val drone = CtDroneSpec(remoteId, mappedId, "NCSSAR", "DJI Mini 4 Pro", pilotCallsign)
        assertTrue(
            drone.checkNewWaypoint(
                39.153061,
                -121.132946,
                100.0,
                1_000L,
                1_000L,
                true,
                CtDroneSpec.TransportTypeEnum.BT4
            )
        )
        return drone
    }

    private fun confirmTeamDrone(remoteId: String, org: String) {
        CaltopoClient.SaveDroneSpecConfirmation(remoteId, org, "DJI Mini 4 Pro", "Pilot", "1sar$remoteId")
    }

    private fun geoJsonWithOrgAndRid(org: String, remoteId: String): JSONObject =
        JSONObject()
            .put("type", "FeatureCollection")
            .put(
                "features",
                JSONArray().put(
                    JSONObject()
                        .put("type", "Feature")
                        .put(
                            "properties",
                            JSONObject().put(
                                "r2c_prop",
                                JSONObject()
                                    .put("org", org)
                                    .put("rid", remoteId)
                            )
                        )
                )
            )

    private fun resetTracks() {
        @Suppress("UNCHECKED_CAST")
        val trackMap = trackMapField.get(null) as MutableMap<Any, Any>
        trackMap.clear()
        WaypointTrack.WaypointCount = 0
    }

    private class BlockingArchiveTrack(trackLabel: String, droneSpec: CtDroneSpec) :
        WaypointTrack(trackLabel, droneSpec) {
        val archiveStarted = CountDownLatch(1)
        val releaseArchive = CountDownLatch(1)
        @Volatile
        var archiveReleased = false

        override fun archive() {
            archiveStarted.countDown()
            releaseArchive.await(5, TimeUnit.SECONDS)
            archiveReleased = true
        }
    }
}
