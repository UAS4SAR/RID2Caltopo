package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/**
 * Peer-confirmed non-owner must never enter LiveTrack create/restart (CalTopo write path).
 * Ownership gates deeper in CaltopoLiveTrack are not enough — newWaypoint must suppress first.
 */
class PeerOwnerMapSuppressTest {
    private lateinit var fixture: TestR2cRuntimeFactory.Fixture
    private lateinit var peers: FakePeerCoordinator
    private lateinit var liveTrackField: Field

    @Before
    fun setUp() {
        fixture = TestR2cRuntimeFactory.create("peer-owner-suppress")
        fixture.setAsDefaultRuntime()
        peers = fixture.peerCoordinator as FakePeerCoordinator
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetHomeOrgName("NCSSAR")
        liveTrackField = CaltopoClient::class.java.getDeclaredField("liveTrack").apply { isAccessible = true }
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun shouldSuppressMapTracking_whenActivePeerOwnerEvenIfConfirmedMapped() {
        val remoteId = "1581F8HGX255S00A0FZT"
        CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Avata 2", "Pilot", "1sar7DjMn4Pr")
        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        drone.setCurrentFlightConfirmed(true)
        assertFalse(CaltopoClient.shouldSuppressMapTracking(drone))

        peers.setPeerOwner(remoteId, "ipad-zone-guid")
        assertTrue(peers.hasActivePeerOwner(remoteId))
        assertFalse(peers.isLocalOwner(remoteId))
        assertTrue(CaltopoClient.shouldSuppressMapTracking(drone))
    }

    @Test
    fun newWaypoint_peerOwner_neverStartsLiveTrack_setsLocalArchiveOnly() {
        val remoteId = "1581F8HGX255S00A0FZT"
        CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Avata 2", "Pilot", "1sar7DjMn4Pr")
        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        drone.setCurrentFlightConfirmed(true)
        drone.checkNewWaypoint(
            39.15, -121.13, 100.0, 1_000L, 1_000L, true, CtDroneSpec.TransportTypeEnum.BT4
        )
        peers.setPeerOwner(remoteId, "ipad-zone-guid")

        val client = CaltopoClient.ClientForRemoteId(remoteId)
        assertNull(liveTrackField.get(client))
        assertTrue(
            client.newWaypoint(
                39.1501, -121.1301, 101.0, 2_000L,
                CtDroneSpec.TransportTypeEnum.BT4, true
            )
        )
        assertTrue(drone.isLocalArchiveOnly)
        assertNull("non-owner must not construct/restart LiveTrack", liveTrackField.get(client))
        assertEquals(0, fixture.calTopoSessionGateway.snapshotOperations().size)
    }

    @Test
    fun publishDecision_peerOwnerLocalArchive_reportsNonPublishedPeerOwner() {
        val remoteId = "1581F8HGXPEER1"
        CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Avata 2", "Pilot", "1sarPeerOwn")
        peers.setPeerOwner(remoteId, "zone-a")
        val drone = CtDroneSpec(remoteId, "1sarPeerOwn", "NCSSAR", "Avata 2", "Pilot").apply {
            setLocalArchiveOnly(true)
            setCurrentFlightConfirmed(true)
            checkNewWaypoint(
                39.15, -121.13, 100.0, 1_000L, 1_000L, true, CtDroneSpec.TransportTypeEnum.BT4
            )
        }
        val track = WaypointTrack(drone.trackLabel(), drone)
        track.prepareForArchive()
        val method = WaypointTrack::class.java.getDeclaredMethod("publishDecisionLabel").apply {
            isAccessible = true
        }
        assertEquals("non-published (peer owner zone-a)", method.invoke(track))
    }

    @Test
    fun publishDecision_peerOwnerNotLocalArchive_reportsTrackerSkip() {
        val remoteId = "1581F8HGPEER2"
        CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Avata 2", "Pilot", "1sarPeer2")
        peers.setPeerOwner(remoteId, "zone-b")
        val drone = CtDroneSpec(remoteId, "1sarPeer2", "NCSSAR", "Avata 2", "Pilot").apply {
            setLocalArchiveOnly(false)
            setCurrentFlightConfirmed(true)
            checkNewWaypoint(
                39.15, -121.13, 100.0, 1_000L, 1_000L, true, CtDroneSpec.TransportTypeEnum.BT4
            )
        }
        val track = WaypointTrack(drone.trackLabel(), drone)
        track.prepareForArchive()
        val method = WaypointTrack::class.java.getDeclaredMethod("publishDecisionLabel").apply {
            isAccessible = true
        }
        assertEquals("tracker_skip", method.invoke(track))
    }
}
