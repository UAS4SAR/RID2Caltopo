package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class DefaultPeerCoordinatorTest {

    @Test
    fun reenrollmentNoticeIsShownOncePerRejectedCredential() {
        assertTrue(DefaultPeerCoordinator.shouldShowReenrollmentNotice(null, "token-a"))
        assertFalse(DefaultPeerCoordinator.shouldShowReenrollmentNotice("token-a", "token-a"))
        assertTrue(DefaultPeerCoordinator.shouldShowReenrollmentNotice("token-a", "token-b"))
    }

    private class FakeTransport : TrackerCoordinationTransport {
        var connected = false
        var connectCount = 0
        var autoOpen = true
        val sentMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
        private var callback: TrackerCoordinationTransport.Callback? = null

        override fun setCallback(callback: TrackerCoordinationTransport.Callback?) {
            this.callback = callback
        }

        override fun connect(websocketUrl: String, apiKey: String?) {
            connectCount++
            if (autoOpen) {
                connected = true
                callback?.onOpen()
            }
        }

        override fun disconnect() {
            connected = false
        }

        override fun isConnected(): Boolean = connected

        override fun send(text: String): Boolean {
            sentMessages.add(text)
            return true
        }

        fun fail(responseCode: Int, responseMessage: String) {
            connected = false
            callback?.onFailure(RuntimeException("HTTP $responseCode"), responseCode, responseMessage)
        }

    }

    private lateinit var transport: FakeTransport
    private lateinit var mqttFallback: FakePeerCoordinator

    @Before
    fun setUp() {
        CaltopoClient.ResetPersistedClientState()
        transport = FakeTransport()
        mqttFallback = FakePeerCoordinator("mqtt-fallback")
        TrackerPeerCoordinator.setTransportFactoryForTesting { transport }
        DefaultPeerCoordinator.setMqttCoordinatorOverrideForTesting(mqttFallback)
        CaltopoClient.SetUsePeers(true)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
    }

    @After
    fun tearDown() {
        DefaultPeerCoordinator.getInstance().stop()
        TrackerPeerCoordinator.resetForTesting()
        DefaultPeerCoordinator.setMqttCoordinatorOverrideForTesting(null)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        CaltopoClient.SetUsePeers(false)
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
    }

    @Test
    fun start_usesTrackerCoordinatorWhenConfigured() {
        DefaultPeerCoordinator.getInstance().start("MAP1", "zone-alpha", "Alpha", null)

        assertTrue(transport.connected)
    }

    @Test
    fun resumeAfterReauthentication_reconnectsStoppedTrackerCoordinator() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        TrackerPeerCoordinator.getInstance().stop()
        assertFalse(transport.connected)

        coordinator.resumeAfterReauthentication()

        assertEquals(2, transport.connectCount)
        assertTrue(transport.connected)
    }

    @Test
    fun standaloneR2cCoordinationDefaultsOff() {
        CaltopoClient.ResetPersistedClientState()

        assertFalse(CaltopoClient.GetStandaloneR2cCoordinationEnabled())
    }

    @Test
    fun mapConnectedStartStillUsesTrackerWhenStandaloneToggleOff() {
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        DefaultPeerCoordinator.getInstance().start("MAP1", "zone-alpha", "Alpha", null)

        assertEquals(1, transport.connectCount)
        assertTrue(transport.connected)
    }

    @Test
    fun legacyStandaloneOptInDoesNotEnableTracker() {
        val coordinator = DefaultPeerCoordinator.getInstance()

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)

        assertEquals("Tracker link disabled", coordinator.coordinationStatusText)
        assertTrue(
            coordinator.coordinationDiagnosticLines.toString(),
            coordinator.coordinationDiagnosticLines.any { it == "Standalone tracker coordination disabled" }
        )
    }

    @Test
    fun statusBeforeStartShowsDisabledWhenStandaloneTrackerToggleOff() {
        val coordinator = DefaultPeerCoordinator.getInstance()

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        assertEquals(PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED, coordinator.coordinationIndicatorState)
        assertEquals("Tracker link disabled", coordinator.coordinationStatusText)
        assertTrue(coordinator.isLocalAlertEligible("RID-1"))
        assertTrue(
            coordinator.coordinationDiagnosticLines.toString(),
            coordinator.coordinationDiagnosticLines.any { it == "Standalone tracker coordination disabled" }
        )
    }

    @Test
    fun stoppingTrackerCoordinationRestoresUngatedStandaloneAlerts() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.stop()

        assertTrue(coordinator.isLocalAlertEligible("RID-1"))
    }

    @Test
    fun hardTrackerFailure_fallsBackToMqttCoordinator() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = object : LiveTrackOwnerDelegate {
            override fun getRemoteId(): String = "DRONE1"
            override fun setLocalOwner(isOwner: Boolean) = Unit
        }
        CaltopoClient.SaveDroneSpecConfirmation(
            "DRONE1", "NCSSAR", "DJI Mini 4 Pro", "1SAR7", "1SAR7Mn4pr"
        )
        coordinator.onLiveTrackCreated(track, drone, 42.0, 1234L)

        transport.fail(403, "Forbidden")

        assertTrue(mqttFallback.isStarted())
        assertEquals("MAP1", mqttFallback.getStartedMapId())
        assertEquals(1, mqttFallback.countEvents("onLiveTrackCreated"))
        assertEquals(
            PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED,
            coordinator.coordinationIndicatorState
        )
        assertEquals(
            DefaultPeerCoordinator.TRACKER_REENROLLMENT_REQUIRED_STATUS,
            coordinator.coordinationStatusText
        )
        assertTrue(
            coordinator.coordinationDiagnosticLines.toString(),
            coordinator.coordinationDiagnosticLines.any {
                it == "Tracker unavailable: HTTP 403 Forbidden"
            }
        )
    }

    @Test
    fun unconfirmedTrackIsBufferedUntilOperatorConfirmation() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val remoteId = "DRONEPENDING"
        val drone = CtDroneSpec(remoteId)
        var localOwner = true
        val track = object : LiveTrackOwnerDelegate {
            override fun getRemoteId(): String = remoteId
            override fun setLocalOwner(isOwner: Boolean) {
                localOwner = isOwner
            }
        }

        coordinator.onLiveTrackCreated(track, drone, 42.0, 1234L)
        coordinator.onWaypointReceived(drone, 39.1, -121.2, 120.0, 42.0, 2345L, null)

        assertFalse(localOwner)
        assertEquals(0, mqttFallback.countEvents("onLiveTrackCreated"))
        assertEquals(0, mqttFallback.countEvents("onWaypointReceived"))

        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId, "NCSSAR", "DJI Mini 4 Pro", "1SAR7", "1SAR7Mn4pr"
        )
        coordinator.onDroneConfirmed(
            remoteId, "NCSSAR", "DJI Mini 4 Pro", "1SAR7", "1SAR7Mn4pr"
        )
        coordinator.onWaypointReceived(drone, 39.2, -121.3, 121.0, 40.0, 3456L, null)

        assertEquals(1, mqttFallback.countEvents("onLiveTrackCreated"))
        assertEquals(1, mqttFallback.countEvents("onWaypointReceived"))
    }

    @Test
    fun unconfirmedTrackStillSendsTrackerAdvisoryTraffic() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONEPENDING")
        var localOwner = true
        val track = object : LiveTrackOwnerDelegate {
            override fun getRemoteId(): String = drone.remoteId
            override fun setLocalOwner(isOwner: Boolean) {
                localOwner = isOwner
            }
        }

        coordinator.onLiveTrackCreated(track, drone, 42.0, 1234L)
        coordinator.onWaypointReceived(drone, 39.1, -121.2, 120.0, 42.0, 2345L, null)

        assertFalse(localOwner)
        val messages = synchronized(transport.sentMessages) {
            transport.sentMessages.map(::JSONObject)
        }
        assertTrue(messages.any {
            it.optString("type") == "traffic_position" &&
                it.optString("source") == "rid" &&
                it.optString("remoteId") == drone.remoteId
        })
        assertFalse(messages.any { it.optString("type") == "sighting" })
    }

    @Test
    fun seiAdvisoryTrafficBypassesOwnershipConfirmation() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.onSEIPositionReceived(
            "DRONESEI", "1SAR7DjMtrc4td", 39.1, -121.2, 550.0,
            2345L, 2300L, 87.0
        )

        val messages = synchronized(transport.sentMessages) {
            transport.sentMessages.map(::JSONObject)
        }
        assertTrue(messages.any {
            it.optString("type") == "traffic_position" &&
                it.optString("source") == "sei" &&
                it.optString("remoteId") == "DRONESEI"
        })
    }

    @Test
    fun droneConfirmed_forwardsToActiveCoordinator() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.onDroneConfirmed(
            "RID-1",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1sar7",
            "1sar7DjMn4Pr"
        )

        val event = mqttFallback.latestEventOfKind("onDroneConfirmed")
        assertEquals("RID-1 mappedId=1sar7DjMn4Pr", event?.summary)
    }

    @Test
    fun droneConfirmed_usesTrackerCoordinatorWhenConfigured() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.onDroneConfirmed(
            "RID-1",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1sar7",
            "1sar7DjMn4Pr"
        )

        val sentMessages = synchronized(transport.sentMessages) {
            transport.sentMessages.toList()
        }
        assertTrue(sentMessages.any {
            val message = JSONObject(it)
            message.optString("type") == "drone_confirmed" &&
                message.optString("remoteId") == "RID-1" &&
                !message.has("flight" + "StartMsec")
        })
        assertEquals(null, mqttFallback.latestEventOfKind("onDroneConfirmed"))
    }

    @Test
    fun missingTrackerConfiguration_isReportedUnavailableInsteadOfMqttHealthy() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        assertEquals(
            PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED,
            coordinator.coordinationIndicatorState
        )
        assertEquals("Coordinator unavailable", coordinator.coordinationStatusText)
        assertTrue(coordinator.coordinationDiagnosticLines.contains("Tracker not configured"))
    }

    @Test
    fun duplicateStartWhileTrackerDegraded_doesNotForceRestart() {
        transport.autoOpen = false
        val coordinator = DefaultPeerCoordinator.getInstance()

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        assertEquals(1, transport.connectCount)
        assertFalse(transport.connected)
    }

}
