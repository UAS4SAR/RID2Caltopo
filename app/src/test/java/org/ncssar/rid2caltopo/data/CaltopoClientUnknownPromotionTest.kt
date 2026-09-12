package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class CaltopoClientUnknownPromotionTest {
    private lateinit var fixture: TestR2cRuntimeFactory.Fixture
    private lateinit var peerCoordinator: FakePeerCoordinator

    private class FakeLiveTrack(private val remoteId: String) : LiveTrackOwnerDelegate {
        override fun getRemoteId(): String = remoteId
        override fun setLocalOwner(isOwner: Boolean) = Unit
    }

    @Before
    fun setUp() {
        fixture = TestR2cRuntimeFactory.create("promotion-test")
        fixture.setAsDefaultRuntime()
        peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetHomeOrgName("NCSSAR")
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun unresolvedPilotRetainsTeamFlightForTrackerWithoutInventingCallsign() {
        val remoteId = "UNRESOLVED1"
        CaltopoClient.SaveDroneSpecUnknownConfirmation(remoteId)
        CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Matrice", "", "AIRCRAFT1")
        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        drone.setFlightReadinessJson(FlightReadiness(aircraft = AircraftReadiness(), payloadDescription = "water bottle").confirmed().toJSON().toString())
        assertFalse(drone.isLocalArchiveOnly)
        assertFalse(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertEquals("", drone.owner)
        assertEquals("water bottle", org.json.JSONObject(drone.flightReadinessJson).getString("payloadDescription"))
        assertEquals(0, org.json.JSONObject(drone.flightReadinessJson).getJSONObject("pilot").length())
    }

    @Test
    fun promoteLocalArchiveOnlyDrone_rejoinsCoordinatorForActiveFlight() {
        val remoteId = "DRONE1"
        CaltopoClient.SaveDroneSpecUnknownConfirmation(remoteId)
        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        drone.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            1234L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertFalse(drone.trackLabel().isEmpty())

        val liveTrack = FakeLiveTrack(remoteId)
        CaltopoClient.promoteLocalArchiveOnlyDrone(remoteId, liveTrack)

        assertFalse(drone.isLocalArchiveOnly())
        val event = peerCoordinator.latestEventOfKind("onLiveTrackCreated")
        assertNotNull(event)
        assertEquals("DRONE1 dist=NaN", event!!.summary)
    }

    @Test
    fun unknownConfirmationPersistsForAppSessionUntilPromoted() {
        val remoteId = "DRONE2"
        CaltopoClient.SaveDroneSpecUnknownConfirmation(remoteId)
        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        assertTrue(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertTrue(drone.isLocalArchiveOnly)
        drone.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            1234L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        drone.reset()
        assertFalse(drone.isLocalArchiveOnly)

        val client = CaltopoClient.ClientForRemoteId(remoteId)
        client.newWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            CtDroneSpec.TransportTypeEnum.BT4,
            true
        )

        assertTrue(drone.isLocalArchiveOnly)

        CaltopoClient.promoteLocalArchiveOnlyDrone(remoteId, FakeLiveTrack(remoteId))

        assertFalse(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertFalse(drone.isLocalArchiveOnly)
    }

    @Test
    fun neverSeenRemoteIdIsArchivedLocallyAndShownInLocalMapButSuppressedFromExternalPublicationByDefault() {
        val remoteId = "ELDORADO1"
        val client = CaltopoClient.ClientForRemoteId(remoteId)
        val drone = client.droneSpec
        val localPoints = mutableListOf<String>()
        val listener = CaltopoLiveTrack.LocalTrackListener { pointRemoteId, mappedId, lat, lng, altitudeMeters, timestampMsec ->
            localPoints.add("$pointRemoteId:$mappedId:$lat:$lng:$altitudeMeters:$timestampMsec")
        }

        CaltopoLiveTrack.AddLocalTrackListener(listener)
        try {
            assertTrue(drone.isLocalArchiveOnly)
            drone.checkNewWaypoint(
                39.1,
                -121.2,
                120.0,
                1234L,
                1234L,
                true,
                CtDroneSpec.TransportTypeEnum.BT4
            )
            client.newWaypoint(
                39.1,
                -121.2,
                120.0,
                1234L,
                CtDroneSpec.TransportTypeEnum.BT4,
                true
            )
        } finally {
            CaltopoLiveTrack.RemoveLocalTrackListener(listener)
        }

        assertTrue(drone.isActive)
        assertTrue(drone.isLocalArchiveOnly)
        assertEquals(1, WaypointTrack.GetTrackPointsSnapshot(drone).size)
        assertEquals(listOf("ELDORADO1:ELDORADO1:39.1:-121.2:120.0:1234"), localPoints)
        assertEquals(0, peerCoordinator.countEvents("onLiveTrackCreated"))
    }

    @Test
    fun confirmationSavePromotesNeverSeenRemoteIdToWaypointTrackLogging() {
        val remoteId = "ELDORADO2"
        val client = CaltopoClient.ClientForRemoteId(remoteId)
        val drone = client.droneSpec

        assertTrue(drone.isLocalArchiveOnly)
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Matrice 4TD",
            "1SAR83",
            "1SAR83M4TD"
        )

        assertFalse(drone.isLocalArchiveOnly)
        drone.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            1234L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        client.newWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            CtDroneSpec.TransportTypeEnum.BT4,
            true
        )

        assertEquals(1, WaypointTrack.GetTrackPointsSnapshot(drone).size)
    }

    @Test
    fun nonTeamConfirmationKeepsActiveDroneLocalArchiveOnly() {
        val remoteId = "ELDORADO3"
        val client = CaltopoClient.ClientForRemoteId(remoteId)
        val drone = client.droneSpec
        val localPoints = mutableListOf<String>()
        val listener = CaltopoLiveTrack.LocalTrackListener { pointRemoteId, mappedId, lat, lng, altitudeMeters, timestampMsec ->
            localPoints.add("$pointRemoteId:$mappedId:$lat:$lng:$altitudeMeters:$timestampMsec")
        }

        CaltopoLiveTrack.AddLocalTrackListener(listener)
        try {
            drone.checkNewWaypoint(
                39.1,
                -121.2,
                120.0,
                1234L,
                1234L,
                true,
                CtDroneSpec.TransportTypeEnum.BT4
            )
            client.newWaypoint(
                39.1,
                -121.2,
                120.0,
                1234L,
                CtDroneSpec.TransportTypeEnum.BT4,
                true
            )
            assertEquals(listOf("ELDORADO3:ELDORADO3:39.1:-121.2:120.0:1234"), localPoints)
            localPoints.clear()

            CaltopoClient.SaveDroneSpecConfirmation(
                remoteId,
                "unk",
                "Unknown",
                "unk1",
                "unk1Unk"
            )
        } finally {
            CaltopoLiveTrack.RemoveLocalTrackListener(listener)
        }

        assertTrue(drone.isLocalArchiveOnly)
        assertEquals("unk1Unk", drone.mappedId)
        assertEquals(listOf("ELDORADO3:unk1Unk:39.1:-121.2:120.0:1234"), localPoints)
        assertEquals(0, peerCoordinator.countEvents("onLiveTrackCreated"))
    }

    @Test
    fun peerConfirmationUpdatesMetadataWithoutSessionSuppressingRemoteId() {
        val remoteId = "DRONE3"

        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR7",
            "1SAR7m3"
        )

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )

        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        assertFalse(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertFalse(drone.isLocalArchiveOnly)
        assertEquals("1SAR8m3", drone.mappedId)
        assertEquals("1SAR8", drone.owner)
    }

    @Test
    fun peerConfirmationCreatesSessionOnlyDroneSpecWhenMissing() {
        val remoteId = "DRONE5"

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "MA-SAR",
            "Autel Evo Max",
            "MA12",
            "MA12Autel"
        )

        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        assertFalse(drone.isLocalArchiveOnly)
        assertEquals("MA12Autel", drone.mappedId)
        assertEquals("MA-SAR", drone.org)
        assertEquals("Autel Evo Max", drone.model)
        assertEquals("MA12", drone.owner)
        assertEquals(0, CaltopoClient.GetRidmapCount())
        assertTrue(CaltopoClient.GetPersistedDroneSpecs().isEmpty())
    }

    @Test
    fun peerConfirmationDoesNotMutatePersistedDroneSpecCache() {
        val remoteId = "DRONE6"
        CaltopoClient.ApplyRemoteDroneSpec(
            remoteId,
            "CachedMapped",
            "CachedOrg",
            "CachedModel",
            "CachedOwner"
        )

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "PeerOrg",
            "PeerModel",
            "PeerOwner",
            "PeerMapped"
        )

        val sessionDrone = CaltopoClient.GetDroneSpec(remoteId)!!
        assertEquals("PeerMapped", sessionDrone.mappedId)
        assertEquals("PeerOrg", sessionDrone.org)
        assertEquals("PeerModel", sessionDrone.model)
        assertEquals("PeerOwner", sessionDrone.owner)

        val persistedDrone = CaltopoClient.GetPersistedDroneSpecs().single()
        assertEquals("CachedMapped", persistedDrone.mappedId)
        assertEquals("CachedOrg", persistedDrone.org)
        assertEquals("CachedModel", persistedDrone.model)
        assertEquals("CachedOwner", persistedDrone.owner)
    }

    @Test
    fun peerConfirmationBlankFieldsDoNotEraseSessionValues() {
        val remoteId = "DRONE7"
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            "LocalOrg",
            "LocalModel",
            "LocalOwner",
            "LocalMapped"
        )

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "",
            "",
            "",
            ""
        )

        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        assertEquals("LocalMapped", drone.mappedId)
        assertEquals("LocalOrg", drone.org)
        assertEquals("LocalModel", drone.model)
        assertEquals("LocalOwner", drone.owner)
    }

    @Test
    fun confirmationSavePromotesUnknownDroneBackToCooperativeHandling() {
        val remoteId = "DRONE4"
        CaltopoClient.SaveDroneSpecUnknownConfirmation(remoteId)
        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        assertTrue(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertTrue(drone.isLocalArchiveOnly)

        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR7",
            "1SAR7m3"
        )

        assertFalse(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertFalse(drone.isLocalArchiveOnly)
        assertEquals("1SAR7m3", drone.mappedId)
    }
}
