package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.SimpleTimer

class R2CViewModelDroneConfirmationTest {
    @Test fun restrictivePolicyWithdrawsAutomaticPanelButKeepsManualClaim() {
        val drone = activeDrone("POLICYTRANSITION", waypointTimestampMsec = 1234L)
            .apply { setMappedId("mini4pro") }
        val model = R2CViewModel(SimpleTimer())
        model.onDroneConfirmationCandidate(drone)
        assertNotNull(model.pendingDroneConfirmation.value)
        model.applyOwnershipPolicyChange(true)
        assertNull(model.pendingDroneConfirmation.value)
        model.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))
        assertEquals(drone.remoteId, model.pendingDroneConfirmation.value?.remoteId)
        model.requestDroneConfirmation(drone)
        model.applyOwnershipPolicyChange(false)
        model.applyOwnershipPolicyChange(true)
        assertEquals(drone.remoteId, model.pendingDroneConfirmation.value?.remoteId)
    }

    @Test fun sharedIncidentPolicyUsesVideoOnlyWhenPresenceRequiresIt() {
        assertFalse(ownershipRequiresStream("all_detected", true, false, true))
        assertTrue(ownershipRequiresStream("streaming_device", false, true, false))
        assertFalse(ownershipRequiresStream("streaming_shared_incident", false, false, false))
        assertFalse(ownershipRequiresStream("streaming_shared_incident", true, true, false))
        assertTrue(ownershipRequiresStream("streaming_shared_incident", true, true, true))
        assertTrue(ownershipRequiresStream("streaming_shared_incident", true, false, false))
    }

    @Test fun streamingOwnershipAllowsManualClaimForTelemetryAndPromptsWhenVideoArrives() {
        org.ncssar.rid2caltopo.data.OrgConfigManager.setDroneOwnershipForTesting("streaming_device")
        try {
            val drone = activeDrone("TELEMETRYONLY", waypointTimestampMsec = 1234L)
                .apply { setMappedId("mini4pro") }
            val manual = R2CViewModel(SimpleTimer())
            manual.onDroneConfirmationCandidate(drone)
            assertNull(manual.pendingDroneConfirmation.value)
            manual.requestDroneConfirmation(drone)
            assertEquals(drone.remoteId, manual.pendingDroneConfirmation.value?.remoteId)

            val automatic = R2CViewModel(SimpleTimer())
            automatic.onDroneConfirmationCandidate(drone)
            assertNull(automatic.pendingDroneConfirmation.value)
            automatic.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))
            assertEquals(drone.remoteId, automatic.pendingDroneConfirmation.value?.remoteId)
        } finally {
            org.ncssar.rid2caltopo.data.OrgConfigManager.setDroneOwnershipForTesting(
                org.ncssar.rid2caltopo.data.DroneOwnershipPolicy.ALL)
            org.ncssar.rid2caltopo.data.OrgConfigManager.setDroneOwnershipForTesting(null)
        }
    }

    @Test fun proximitySettingsKeepsMainOrLiveViewUnderneath() {
        for (liveView in listOf(false, true)) {
            val model = R2CViewModel(SimpleTimer())
            if (liveView) model.showStreams()
            val origin = model.activeScreen.value
            model.showProximitySettings()
            assertTrue(model.proximitySettingsOpen)
            assertEquals(origin, model.activeScreen.value)
            model.dismissProximitySettings()
            assertFalse(model.proximitySettingsOpen)
            assertEquals(origin, model.activeScreen.value)
        }
    }

    @Test fun mapConnectionDialogsKeepTheOriginatingPageThroughCancelAndReconnect() {
        val mapField = CaltopoMap::class.java.getDeclaredField("MapNode").apply { isAccessible = true }
        val previousMap = mapField.get(null)
        try {
            mapField.set(null, org.ncssar.rid2caltopo.data.CaltopoNode.MapNode("map-test", "Taylor Site", 0L))
        for (liveView in listOf(false, true)) {
            val model = R2CViewModel(SimpleTimer())
            if (liveView) model.showStreams()
            val origin = model.activeScreen.value
            model.openConnectionOverlayFromCurrentScreen()
            assertFalse(model.showIncidentSelection)
            model.openIncidentSelectionFromSettings()
            assertTrue(model.showIncidentSelection)
            model.dismissIncidentSelection()
            assertFalse(model.showIncidentSelection)
            model.openConnectionOverlayFromCurrentScreen()
            model.connectIncidentMap()
            assertEquals(origin, model.activeScreen.value)
            model.onUIEvent(UIEvent.DismissRequested)
            assertEquals(OverlayState.None, model.overlay)
            assertEquals(origin, model.activeScreen.value)

            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.credentialsVerified))
            assertEquals(OverlayState.None, model.overlay)
            model.openConnectionOverlayFromCurrentScreen()
            assertFalse(model.showIncidentSelection)
            model.dismissIncidentSelection()
            assertFalse(model.showIncidentSelection)
            model.openConnectionOverlayFromCurrentScreen()
            model.connectIncidentMap()
            assertEquals(OverlayState.MapBrowser, model.overlay)
            assertEquals(origin, model.activeScreen.value)
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.connecting))
            assertEquals(origin, model.activeScreen.value)
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.up))
            assertEquals(OverlayState.None, model.overlay)
            assertEquals(origin, model.activeScreen.value)
            model.openConnectionOverlayFromCurrentScreen()
            assertFalse(model.showIncidentSelection)
            model.dismissIncidentSelection()
            assertFalse(model.showIncidentSelection)
            model.openConnectionOverlayFromCurrentScreen()
            model.connectIncidentMap()
            model.onUIEvent(UIEvent.SwitchMapRequested)
            model.onUIEvent(UIEvent.DismissRequested)
            assertEquals(origin, model.activeScreen.value)
            model.onUIEvent(UIEvent.NotAbleToReadConfigFile)
            assertEquals(origin, model.activeScreen.value)
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.down))
            assertEquals(origin, model.activeScreen.value)
        }
        } finally { mapField.set(null, previousMap) }
    }

    @Test fun cancellingMapSwitchPreservesSelectedMapAndHeaderAction() {
        val mapField = CaltopoMap::class.java.getDeclaredField("MapNode").apply { isAccessible = true }
        val previousMap = mapField.get(null)
        val map = org.ncssar.rid2caltopo.data.CaltopoNode.MapNode("map-test", "Taylor Site", 0L)
        try {
            mapField.set(null, map)
            val model = R2CViewModel(SimpleTimer())
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.up))
            model.openConnectionOverlayFromCurrentScreen()
            assertFalse(model.showIncidentSelection)
            assertEquals(OverlayState.Management, model.overlay)
            model.onUIEvent(UIEvent.SwitchMapRequested)
            assertEquals(OverlayState.MapBrowser, model.overlay)
            assertEquals("Taylor Site", incidentMapDisplayValue(model.connectionState))
            model.onUIEvent(UIEvent.DismissRequested)
            assertEquals(OverlayState.None, model.overlay)
            assertEquals("Taylor Site", incidentMapDisplayValue(model.connectionState))
            assertSame(map, CaltopoMap.GetMapNode())
            model.onUIEvent(UIEvent.HeaderClicked)
            assertEquals(OverlayState.Management, model.overlay)
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.down))
            assertEquals("No map", incidentMapDisplayValue(model.connectionState))
        } finally { mapField.set(null, previousMap) }
    }

    @Test fun cancellingInitialMapBrowserPreservesVerifiedCredentials() {
        val model = R2CViewModel(SimpleTimer())
        model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.credentialsVerified))
        model.onUIEvent(UIEvent.DismissRequested)
        assertEquals(CaltopoConnectionState.CredentialsVerified, model.connectionState)
        assertEquals(OverlayState.None, model.overlay)
        model.onUIEvent(UIEvent.HeaderClicked)
        assertEquals(OverlayState.MapBrowser, model.overlay)
    }

    @Test fun closingMapPickerDoesNotReopenItWhenCredentialCheckFinishes() {
        val model = R2CViewModel(SimpleTimer())
        model.onUIEvent(UIEvent.DismissRequested)
        model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.credentialsVerified))
        assertEquals(OverlayState.None, model.overlay)
        model.onUIEvent(UIEvent.HeaderClicked)
        assertEquals(OverlayState.MapBrowser, model.overlay)
    }

    @Test fun telemetryTimeoutDoesNotReopenConfirmationWhileOldVideoRemainsListed() {
        val remoteId = "RETURNINGVIDEO"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L).apply { setMappedId("1sar7M4TD") }
        val model = R2CViewModel(SimpleTimer())
        model.showStreams()
        model.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))
        model.updatePendingDroneConfirmation("NCSSAR", "1SAR7", "DJI Matrice 4TD")
        model.savePendingDroneConfirmation()
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        assertNull(model.pendingDroneConfirmation.value)

        model.onLocalTrackFinished(remoteId, drone.mappedId, "telemetry idle after Wi-Fi loss")
        assertFalse(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        repeat(3) {
            model.onDroneSpecsChanged(listOf(drone))
            model.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))
            assertNull(model.pendingDroneConfirmation.value)
        }
        val nextFlight = activeDrone(remoteId, waypointTimestampMsec = System.currentTimeMillis() + 1)
        model.onDroneSpecsChanged(listOf(nextFlight))
        assertEquals(remoteId, model.pendingDroneConfirmation.value?.remoteId)
        assertEquals(ActiveScreen.STREAMS, model.activeScreen.value)
        model.savePendingDroneConfirmation()
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        model.onDroneSpecsChanged(listOf(drone))
        assertNull(model.pendingDroneConfirmation.value)
    }

    @Test fun uniqueVideoOpensConfirmationBeforeRidAndDoesNotRepeatOnRidArrival() {
        val drone=CtDroneSpec("VIDEO1","1sar7Mn4Pr","NCSSAR","DJI Mini 4 Pro","1SAR7")
        val model=R2CViewModel(SimpleTimer())
        model.showStreams()
        model.onLiveStreamDesignatorsChanged(setOf(" 1SAR7MN4PR "),listOf(drone))
        assertEquals(ActiveScreen.STREAMS, model.activeScreen.value)
        assertEquals("VIDEO1",model.pendingDroneConfirmation.value?.remoteId)
        model.onDroneSpecsChanged(emptyList())
        assertNotNull(model.pendingDroneConfirmation.value)
        model.markPendingDroneConfirmationUnknown()
        model.onDroneConfirmationCandidate(drone)
        assertNull(model.pendingDroneConfirmation.value)
    }

    @Test fun videoConfirmationBeforeTelemetryRetainsPreparedPilot() {
        val remoteId = "VIDEOPILOT"
        val drone = CtDroneSpec(remoteId, "1sar1001-01DjMtrc4td", "NCSSAR", "DJI Matrice 4TD", "1SAR1001")
        val model = R2CViewModel(SimpleTimer())
        model.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))
        model.updatePendingDroneConfirmation("NCSSAR", "10", "DJI Matrice 4TD")
        assertNull(CaltopoClient.GetDroneSpec(remoteId))
        val state = model.pendingDroneConfirmation.value!!
        val prepared = PreparedDroneConfirmation(state, CaltopoClient.GetSettingsRevision(), CaltopoMap.GetMapId(), null, 0,
            "{\"pilot\":{\"callsign\":\"10\"}}")
        model.savePendingDroneConfirmation(prepared = prepared)
        val active = CaltopoClient.GetDroneSpec(remoteId)!!
        assertTrue(active.isCurrentFlightConfirmed)
        assertEquals("10", org.json.JSONObject(active.flightReadinessJson).getJSONObject("pilot").getString("callsign"))
        assertEquals("10-01DjMtrc4td", active.displayLabel)
        assertEquals("1sar1001-01DjMtrc4td", active.mappedId)
    }

    @Test fun confirmedPilotLabelRefreshesDroneRowWithoutNewTelemetry() {
        val drone = activeDrone("LABELROW", waypointTimestampMsec = 1234L)
        drone.setMappedId("1sar1001-01DjMtrc4td")
        drone.owner = "10"
        val model = R2CViewModel(SimpleTimer())
        model.onDroneSpecsChanged(listOf(drone))
        val before = model.drones.value
        drone.setCurrentFlightConfirmed(true)
        model.onDroneSpecsChanged(listOf(drone))
        assertNotSame(before, model.drones.value)
        assertEquals("10-01DjMtrc4td", model.drones.value.single().displayLabel)
    }

    @Test fun stalePreparedPilotCannotConfirmChangedReadiness() {
        val remoteId = "STALEPILOT"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L)
        val model = R2CViewModel(SimpleTimer())
        model.onDroneSpecsChanged(listOf(drone))
        CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Mavic", "1SAR7", "1sar7Mavic")
        val active = CaltopoClient.GetDroneSpec(remoteId)!!
        val snapshot = active.flightReadinessSnapshot()
        val state = model.pendingDroneConfirmation.value!!
        active.setFlightReadinessJson("{\"pilot\":{\"callsign\":\"1SAR8\"}}")
        val saved = CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Mavic", "1SAR7", "1sar7Mavic",
            active, snapshot.revision, "{\"pilot\":{\"callsign\":\"1SAR7\"}}")
        assertFalse(saved)
        assertEquals("1SAR8", org.json.JSONObject(active.flightReadinessJson).getJSONObject("pilot").getString("callsign"))
        val other = CtDroneSpec(remoteId)
        assertFalse(CaltopoClient.SaveDroneSpecConfirmation(remoteId, "NCSSAR", "Mavic", "1SAR7", "1sar7Mavic",
            other, active.flightReadinessSnapshot().revision, "{\"pilot\":{\"callsign\":\"1SAR7\"}}"))
        assertEquals(remoteId, state.remoteId)
    }
    @Test
    fun automaticConfirmationKeepsLiveViewOpenThroughSaveAndIgnore() {
        for (save in listOf(false, true)) {
            val model = R2CViewModel(SimpleTimer())
            val drone = CtDroneSpec(if (save) "CONTEXTSAVE" else "CONTEXTIGNORE")
            model.showStreams()

            model.onDroneConfirmationCandidate(drone)

            assertNotNull(model.pendingDroneConfirmation.value)
            assertEquals(ActiveScreen.STREAMS, model.activeScreen.value)
            if (save) model.savePendingDroneConfirmation() else model.markPendingDroneConfirmationUnknown()
            assertNull(model.pendingDroneConfirmation.value)
            assertEquals(ActiveScreen.STREAMS, model.activeScreen.value)
        }
    }

    @Test
    fun activeFlightConfirmationKeepsLiveViewOpenWhenFlightEnds() {
        val model = R2CViewModel(SimpleTimer())
        model.showStreams()
        model.onDroneSpecsChanged(listOf(activeDrone("CONTEXTEND", waypointTimestampMsec = 1234L)))

        assertNotNull(model.pendingDroneConfirmation.value)
        assertEquals(ActiveScreen.STREAMS, model.activeScreen.value)
        model.onLocalTrackFinished("CONTEXTEND", "", "test flight ended")
        assertNull(model.pendingDroneConfirmation.value)
        assertEquals(ActiveScreen.STREAMS, model.activeScreen.value)
    }

    @Test fun ambiguousVideoDoesNotChooseAnAircraft() {
        assertNull(uniqueStreamConfirmationRemoteId("same",listOf("A" to "Same","B" to "same")))
        assertNull(uniqueStreamConfirmationRemoteId("missing",listOf("A" to "Same")))
        assertEquals("A",uniqueStreamConfirmationRemoteId(" SAME ",listOf("A" to "same")))
    }
    @Test fun newPublisherAfterTrackEndAllowsVideoOnlyConfirmation() {
        val drone = CtDroneSpec("RECONNECT", "mini4pro", "NCSSAR", "Mini", "1SAR7")
        val model = R2CViewModel(SimpleTimer())
        model.onLiveStreamDesignatorsChanged(setOf("mini4pro"), listOf(drone), mapOf("mini4pro" to "old"))
        model.savePendingDroneConfirmation()
        model.onLocalTrackFinished(drone.remoteId, drone.mappedId, "timeout")
        model.onLiveStreamDesignatorsChanged(setOf("mini4pro"), listOf(drone), mapOf("mini4pro" to "old"))
        assertNull(model.pendingDroneConfirmation.value)
        model.onLiveStreamDesignatorsChanged(emptySet(), listOf(drone))
        model.onLiveStreamDesignatorsChanged(setOf("mini4pro"), listOf(drone), mapOf("mini4pro" to "old"))
        assertNull(model.pendingDroneConfirmation.value)
        model.onLiveStreamDesignatorsChanged(setOf("mini4pro"), listOf(drone), mapOf("mini4pro" to "new"))
        assertEquals(drone.remoteId, model.pendingDroneConfirmation.value?.remoteId)
    }

    @Before
    fun setUp() {
        clearLocalTrackListeners()
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
        clearLocalTrackListeners()
    }

    @Test
    fun peerClaimLeavesTheOpenPanelAvailableForTakeover() {
        val remoteId = "DRONEPEER"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
        assertFalse(CaltopoClient.IsCurrentLocallyConfirmedDrone(remoteId))
    }

    @Test
    fun trackerConfirmationBeforeLocalSightingStillOffersTakeover() {
        val remoteId = "DRONELATE"
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(emptyList())

        viewModel.onDroneSpecsChanged(listOf(activeDrone(remoteId, waypointTimestampMsec = 3456L)))

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
        assertFalse(CaltopoClient.IsCurrentLocallyConfirmedDrone(remoteId))
    }

    @Test
    fun operatorRequestedConfirmationStaysOpenAfterPeerSave() {
        val remoteId = "DRONEINSPECT"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 4567L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.markPendingDroneConfirmationUnknown()
        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        viewModel.requestDroneConfirmation(drone)
        assertNotNull(viewModel.pendingDroneConfirmation.value)

        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun peerClaimDoesNotKeepThePanelClosedAfterTheAircraftReturns() {
        val remoteId = "DRONEREUSE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 5678L)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)

        CaltopoClient.ClearCurrentPeerDroneConfirmation(remoteId)
        viewModel.onDroneSpecsChanged(emptyList())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun unconfirmedFlightStillOpensConfirmationPanel() {
        val remoteId = "DRONELOCAL"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 5678L)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun firstRidSightingOpensConfirmationBeforeAcceptedWaypoint() {
        val remoteId = "DRONEFIRSTSEEN"
        val drone = CtDroneSpec(remoteId)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun suppressedFirstRidSightingDoesNotOpenConfirmationPanel() {
        val remoteId = "ELDORADOFIRSTSEEN"
        val drone = CaltopoClient.ClientForRemoteId(remoteId).droneSpec

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun suppressedActiveDroneDoesNotOpenConfirmationPanel() {
        val remoteId = "ELDORADOACTIVE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 6789L)
        drone.setLocalArchiveOnly(true)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun operatorRequestedConfirmationReopensSessionUnknownDroneAndAcceptsFieldEdits() {
        val remoteId = "UNKNOWNOPERATOR"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 6789L)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.markPendingDroneConfirmationUnknown()
        assertNull(viewModel.pendingDroneConfirmation.value)

        viewModel.requestDroneConfirmation(drone)

        assertNotNull(viewModel.pendingDroneConfirmation.value)

        viewModel.updatePendingDroneConfirmation(
            pilotCallsign = "1SAR83",
            droneDescription = "DJI Matrice 4TD"
        )

        assertEquals("1SAR83", viewModel.pendingDroneConfirmation.value?.pilotCallsign)
        assertEquals("DJI Matrice 4TD", viewModel.pendingDroneConfirmation.value?.droneDescription)
    }

    @Test
    fun firstUnknownDroneConfirmationStartsWithBlankOrganization() {
        val remoteId = "UNKNOWNBLANKORG"
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneConfirmationCandidate(CtDroneSpec(remoteId))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals("", viewModel.pendingDroneConfirmation.value?.organization)
    }

    @Test
    fun unknownDroneConfirmationUsesLastOperatorEnteredUnknownOrganization() {
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneConfirmationCandidate(CtDroneSpec("UNKNOWNORG1"))
        viewModel.updatePendingDroneConfirmation(
            organization = "Mutual Aid",
            pilotCallsign = "MA12",
            droneDescription = "HolyStone RID"
        )
        viewModel.savePendingDroneConfirmation()

        viewModel.onDroneConfirmationCandidate(CtDroneSpec("UNKNOWNORG2"))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals("Mutual Aid", viewModel.pendingDroneConfirmation.value?.organization)
    }

    @Test
    fun firstRidSightingPromptIsNotReopenedWhenFlightBecomesActive() {
        val remoteId = "DRONEFIRSTSEENACTIVE"
        val drone = CtDroneSpec(remoteId)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.markPendingDroneConfirmationUnknown()
        assertNull(viewModel.pendingDroneConfirmation.value)

        viewModel.onDroneSpecsChanged(listOf(activeDrone(remoteId, waypointTimestampMsec = 6789L)))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun peerClaimOffersConfirmationAndANewVideoPublisherOffersItAgain() {
        val remoteId = "DRONEFIRSTSEENPEER"
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L).apply { setMappedId("1sar8m3") }
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(CtDroneSpec(remoteId))
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
        assertFalse(CaltopoClient.IsCurrentLocallyConfirmedDrone(remoteId))

        viewModel.dismissConfirmationForInspection()
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNull(viewModel.pendingDroneConfirmation.value)

        viewModel.onLiveStreamDesignatorsChanged(
            setOf(drone.mappedId),
            listOf(drone),
            mapOf(drone.mappedId to "192.168.68.63:39014"),
        )
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun foreignClaimLetsTheNextVideoOfferTakeoverAfterLocalMine() {
        val remoteId = "DRONETAKEOVERAGAIN"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L).apply { setMappedId("1sar7m4td") }
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))
        viewModel.updatePendingDroneConfirmation("NCSSAR", "1SAR7", "DJI Matrice 4TD")
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)
        CaltopoClient.ApplyPeerDroneSpecConfirmation(remoteId, "NCSSAR", "DJI Matrice 4TD", "1SAR7", "1sar7m4td")
        viewModel.peerClaimRemoteIdsForTesting = setOf(remoteId)
        viewModel.onLiveStreamDesignatorsChanged(
            setOf(drone.mappedId),
            listOf(drone),
            mapOf(drone.mappedId to "192.168.68.58:56496"),
        )
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
        assertFalse(CaltopoClient.IsCurrentLocallyConfirmedDrone(remoteId))
    }

    @Test
    fun localMineIsNotRearmedWhenVideoMoves() {
        val remoteId = "DRONEMINEVIDEO"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L).apply { setMappedId("1sar7m4") }
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))
        viewModel.updatePendingDroneConfirmation("NCSSAR", "1SAR7", "DJI Mini 4 Pro")
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)
        viewModel.onLiveStreamDesignatorsChanged(
            setOf(drone.mappedId),
            listOf(drone),
            mapOf(drone.mappedId to "192.168.68.63:39014"),
        )
        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun takeoverClaimNamesTheExistingClaim() {
        assertEquals(
            "Claimed by 1SAR7 on Ken's iPad. Mine asks to take over that claim.",
            takeoverClaimText("1SAR7", "Ken's iPad"),
        )
        assertEquals(
            "Claimed by Unknown RPIC on another device. Mine asks to take over that claim.",
            takeoverClaimText(" ", ""),
        )
        assertFalse(claimConflictReopensConfirmation(true, false))
        assertFalse(claimConflictReopensConfirmation(false, true))
        assertTrue(claimConflictReopensConfirmation(false, false))
    }

    @Test
    fun round12SaveStaysClosedWhenThePreviousOwnerEchoes() {
        val drone = activeDrone("1581F8HGX255S00A0FZT", waypointTimestampMsec = 1_794_361_900_000L).apply {
            setMappedId("1sar7DjMtrc4td")
            setOrg("NCSSAR")
            setOwner("1sar7")
            setModel("DJI Matrice 4TD")
        }
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onLiveStreamDesignatorsChanged(
            setOf(drone.mappedId), listOf(drone), mapOf(drone.mappedId to "192.168.68.56:33826"))
        viewModel.requestDroneConfirmation(drone)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1sar7",
            droneDescription = "DJI Matrice 4TD"
        )
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)

        viewModel.peerClaimRemoteIdsForTesting = setOf(drone.remoteId)
        viewModel.onDroneSpecsChanged(listOf(drone))
        viewModel.requestDroneConfirmation(drone)
        viewModel.onLiveStreamDesignatorsChanged(
            setOf(drone.mappedId), listOf(drone), mapOf(drone.mappedId to "192.168.68.56:33826"))

        assertNull(viewModel.pendingDroneConfirmation.value)
        assertTrue(remoteIdsRearmedByNewPublisher(
            locallyConfirmed = setOf(drone.remoteId),
            previous = emptyMap(),
            current = mapOf(drone.remoteId to setOf("1sar7djmtrc4td|192.168.68.56:99999")),
            foreignClaims = setOf(drone.remoteId),
            saveAwaitingGrant = setOf(drone.remoteId)
        ).isEmpty())
        val peer = mapOf("PEER" to setOf("1sar|10.0.0.2:1"))
        assertEquals(setOf("PEER"), remoteIdsRearmedByNewPublisher(emptySet(), emptyMap(), peer))
        assertTrue(remoteIdsRearmedByNewPublisher(setOf("PEER"), emptyMap(), peer).isEmpty())
        assertEquals(setOf("PEER"), remoteIdsRearmedByNewPublisher(setOf("PEER"), emptyMap(), peer, setOf("PEER")))
        assertTrue(remoteIdsRearmedByNewPublisher(emptySet(), peer, peer).isEmpty())
        assertTrue(remoteIdsRearmedByNewPublisher(emptySet(), emptyMap(), mapOf("PEER" to setOf("1sar|unknown"))).isEmpty())
    }

    @Test
    fun savedSessionIdentitySuppressesPanelWhenTrackPointPublishesDrone() {
        val remoteId = "DRONESCREEN"
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR7",
            "1SAR7m3"
        )
        val viewModel = R2CViewModel(SimpleTimer())
        val client = CaltopoClient.ClientForRemoteId(remoteId)
        val nowMsec = System.currentTimeMillis()
        client.droneSpec.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            nowMsec,
            nowMsec,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        client.newWaypoint(
            39.1,
            -121.2,
            120.0,
            nowMsec,
            CtDroneSpec.TransportTypeEnum.BT4,
            true
        )

        assertEquals(remoteId, viewModel.drones.value.single().remoteId)
        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun unchangedVisibleDroneListDoesNotRepublishUiList() {
        val remoteId = "DRONEUNCHANGED"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        val firstList = viewModel.drones.value

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertSame(firstList, viewModel.drones.value)

        drone.checkNewWaypoint(
            39.1001,
            -121.2001,
            120.0,
            2234L,
            2234L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotSame(firstList, viewModel.drones.value)
    }

    @Test
    fun droneListOrdersByFirstWaypointTimestampNotRecentUpdate() {
        val firstSeen = activeDrone("DRONEFIRST", waypointTimestampMsec = 1_000L)
        val secondSeen = activeDrone("DRONESECOND", waypointTimestampMsec = 2_000L)
        val viewModel = R2CViewModel(SimpleTimer())

        firstSeen.checkNewWaypoint(
            39.1001,
            -121.2001,
            120.0,
            3_000L,
            3_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        viewModel.onDroneSpecsChanged(listOf(secondSeen, firstSeen))

        assertEquals(
            listOf("DRONEFIRST", "DRONESECOND"),
            viewModel.drones.value.map { it.remoteId }
        )
    }

    @Test
    fun continueRetainsMissionWithMissingPilotAndAircraftDetails() {
        val drone = activeDrone("INCOMPLETE", waypointTimestampMsec = 91011L)
        val viewModel = R2CViewModel(SimpleTimer())
        CaltopoClient.SetHomeOrgName("NCSSAR")
        viewModel.onDroneSpecsChanged(listOf(drone))
        viewModel.updatePendingDroneConfirmation(organization = "", pilotCallsign = "", droneDescription = "")
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed("INCOMPLETE"))
        assertFalse(CaltopoClient.GetDroneSpec("INCOMPLETE")!!.isLocalArchiveOnly)
        assertEquals("", CaltopoClient.GetDroneSpec("INCOMPLETE")!!.owner)
    }

    @Test
    fun localStandaloneSavePromptsAgainAfterConfirmedFlightFinishes() {
        val remoteId = "DRONESTANDALONE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 91011L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1SAR7",
            droneDescription = "DJI Mavic 3"
        )
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNull(viewModel.pendingDroneConfirmation.value)

        viewModel.onDroneSpecsChanged(emptyList())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoLiveTrack.NotifyLocalTrackFinished(drone, "test track finished")
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNull(viewModel.pendingDroneConfirmation.value)
        viewModel.onDroneSpecsChanged(listOf(activeDrone(remoteId, waypointTimestampMsec = System.currentTimeMillis() + 1)))

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
        // Flight end retires session confirmation; a later Save establishes a new one.
        assertFalse(CaltopoClient.IsSessionDroneConfirmed(remoteId))
        assertFalse(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
    }

    @Test
    fun localTrackFinishedClearsSavedDecisionForNextFlight() {
        val remoteId = "DRONETRACKDONE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 121314L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1SAR7",
            droneDescription = "DJI Mavic 3"
        )
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoLiveTrack.NotifyLocalTrackFinished(drone, "test track finished")
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNull(viewModel.pendingDroneConfirmation.value)
        assertFalse(CaltopoClient.GetDroneSpec(remoteId)!!.isCurrentFlightConfirmed)
        assertFalse(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        viewModel.onDroneSpecsChanged(listOf(activeDrone(remoteId, waypointTimestampMsec = System.currentTimeMillis() + 1)))

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun quickReturnWithoutPublisherSessionQueuesPanelAndKeepsPublicationGated() {
        val remoteId = "RID60SRETURN"
        val firstSeen = System.currentTimeMillis()
        val first = activeDrone(remoteId, waypointTimestampMsec = firstSeen)
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(first))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.updatePendingDroneConfirmation("NCSSAR", "1SAR7", "DJI Mini 4 Pro")
        viewModel.savePendingDroneConfirmation()
        assertTrue(CaltopoClient.GetDroneSpec(remoteId)!!.isCurrentFlightConfirmed)

        CaltopoLiveTrack.NotifyLocalTrackFinished(first, "flight end")
        assertFalse(CaltopoClient.GetDroneSpec(remoteId)!!.isCurrentFlightConfirmed)
        assertFalse(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        // Fresh aircraft receipt ~60s later, no live publisher session.
        val returning = activeDrone(remoteId, waypointTimestampMsec = firstSeen + 60_000L)
        viewModel.onLiveStreamDesignatorsChanged(emptySet(), listOf(returning), emptyMap())
        viewModel.onDroneSpecsChanged(listOf(returning))
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
        assertFalse(CaltopoClient.GetDroneSpec(remoteId)!!.isCurrentFlightConfirmed)
        assertFalse(
            org.ncssar.rid2caltopo.data.CurrentFlightPublicationGate.decide(
                confirmed = false,
                ignored = false
            ).allowsPublication
        )
    }

    @Test
    fun localTrackFinishedRetainsIgnoreDecisionForNextStream() {
        val remoteId = "DRONEIGNOREDTIMEOUT"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 131415L).apply { setMappedId("mini4pro") }
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.markPendingDroneConfirmationUnknown()
        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoLiveTrack.NotifyLocalTrackFinished(drone, "test track finished")
        viewModel.onDroneSpecsChanged(emptyList())
        assertTrue(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertFalse(CaltopoClient.GetDroneSpec(remoteId)!!.isCurrentFlightConfirmed)
        viewModel.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun declinedDroneDoesNotRepromptWhenLocalPublisherStarts() {
        val drone = activeDrone("DRONENEWVIDEO", waypointTimestampMsec = 131415L).apply { setMappedId("mini4pro") }
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)
        viewModel.markPendingDroneConfirmationUnknown()
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNull(viewModel.pendingDroneConfirmation.value)
        viewModel.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone), mapOf(drone.mappedId to "publisher-1"))
        assertNull(viewModel.pendingDroneConfirmation.value)
        assertTrue(CaltopoClient.IsSessionUnknownDrone(drone.remoteId))
        viewModel.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone), mapOf(drone.mappedId to "publisher-2"))
        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun declinedDroneStaysSilentAfterGapUntilAircraftAndReceptionSelection() {
        val remoteId = "DRONEGAPIGNORE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 200_000L).apply { setMappedId("mini4pro") }
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)
        viewModel.markPendingDroneConfirmationUnknown()
        assertTrue(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertNull(viewModel.pendingDroneConfirmation.value)

        // >30 s RID gap ends the flight lifecycle, then a new video session arrives.
        CaltopoLiveTrack.NotifyLocalTrackFinished(drone, "gap after Don't Publish")
        viewModel.onDroneSpecsChanged(emptyList())
        viewModel.onLiveStreamDesignatorsChanged(emptySet(), listOf(drone))
        val resumed = activeDrone(remoteId, waypointTimestampMsec = 200_000L + 45_000L).apply {
            setMappedId("mini4pro")
        }
        viewModel.onDroneSpecsChanged(listOf(resumed))
        viewModel.onLiveStreamDesignatorsChanged(
            setOf(resumed.mappedId), listOf(resumed), mapOf(resumed.mappedId to "publisher-after-gap")
        )
        assertNull(viewModel.pendingDroneConfirmation.value)
        assertTrue(CaltopoClient.IsSessionUnknownDrone(remoteId))

        // Aircraft and Reception selection reopens the panel; Confirm un-ignores.
        viewModel.requestDroneConfirmation(resumed)
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1SAR7",
            droneDescription = "DJI Mini 4 Pro"
        )
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)
        assertFalse(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertTrue(CaltopoClient.GetDroneSpec(remoteId)!!.isCurrentFlightConfirmed)
    }

    @Test
    fun liveStreamRetainsIgnoreDecisionAcrossRidTrackEnd() {
        val drone = activeDrone("DRONEIGNORELIVE", waypointTimestampMsec = 131415L).apply { setMappedId("mini4pro") }
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))
        viewModel.markPendingDroneConfirmationUnknown()
        CaltopoLiveTrack.NotifyLocalTrackFinished(drone, "RID gap while video continues")
        viewModel.onDroneSpecsChanged(emptyList())
        assertTrue(CaltopoClient.IsSessionUnknownDrone(drone.remoteId))
        assertNull(viewModel.pendingDroneConfirmation.value)
        viewModel.onLiveStreamDesignatorsChanged(emptySet(), listOf(drone))
        viewModel.onLiveStreamDesignatorsChanged(setOf(drone.mappedId), listOf(drone))
        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun saveWarnsButAllowsPilotCallsignAlreadyAssignedToAnotherActiveDrone() {
        val activeDrone = activeDrone("DRONEACTIVEPILOT", waypointTimestampMsec = 151617L).apply {
            setMappedId("1SAR7m3")
            setOwner("1SAR7")
        }
        val candidate = activeDrone("DRONECANDIDATE", waypointTimestampMsec = 181920L)
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(activeDrone, candidate))

        viewModel.requestDroneConfirmation(candidate)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1sar7",
            droneDescription = "DJI Mini 4 Pro"
        )

        assertEquals(
            "Warning: pilot callsign 1sar7 is already assigned to active drone 1SAR7m3. Confirm only if this is intentional.",
            viewModel.pendingDroneConfirmation.value?.pilotCallsignWarning
        )

        viewModel.savePendingDroneConfirmation()

        assertNull(viewModel.pendingDroneConfirmation.value)
        assertEquals("1sar7", CaltopoClient.GetDroneSpec("DRONECANDIDATE")?.owner)
    }

    @Test
    fun pilotCallsignCanBeReusedAfterOtherDroneIsNoLongerActive() {
        val previousDrone = activeDrone("DRONEPREVIOUS", waypointTimestampMsec = 212223L).apply {
            setMappedId("1SAR8m3")
            setOwner("1SAR8")
        }
        val candidate = activeDrone("DRONENEXT", waypointTimestampMsec = 242526L)
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(previousDrone, candidate))

        viewModel.requestDroneConfirmation(candidate)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1SAR8",
            droneDescription = "DJI Matrice 4TD"
        )
        assertEquals(
            "Warning: pilot callsign 1SAR8 is already assigned to active drone 1SAR8m3. Confirm only if this is intentional.",
            viewModel.pendingDroneConfirmation.value?.pilotCallsignWarning
        )

        previousDrone.reset()
        viewModel.onDroneSpecsChanged(listOf(previousDrone, candidate))

        assertNull(viewModel.pendingDroneConfirmation.value?.pilotCallsignWarning)
        viewModel.savePendingDroneConfirmation()

        assertNull(viewModel.pendingDroneConfirmation.value)
        assertEquals("1SAR8DjMtrc4td", CaltopoClient.GetDroneSpec("DRONENEXT")?.mappedId)
    }

    @Test fun pairedSetupSavesStreamDesignatorAndConfirmsWithoutTeams() {
        val remoteID = "1581F6Z9C24BH0036EJL"
        CaltopoClient.ClientForRemoteId(remoteID)
        val model = R2CViewModel(SimpleTimer())
        model.showStreams()
        model.requestPairedDroneSetup(remoteID, "field-drone-1")
        assertEquals("DJI Mini 4 Pro", model.pendingDroneConfirmation.value?.droneDescription)
        assertEquals("field-drone-1", model.pendingDroneConfirmation.value?.bootstrapDesignator)
        model.updatePendingDroneConfirmation(pilotCallsign = "Pilot 7")
        model.savePendingDroneConfirmation()
        val saved = CaltopoClient.GetPersistedDroneSpecs().single { it.remoteId == remoteID }
        assertEquals("field-drone-1", saved.mappedId)
        assertEquals("Pilot 7", saved.owner)
        assertEquals("", saved.org)
        assertFalse(saved.isCurrentFlightConfirmed)
        assertTrue(CaltopoClient.GetDroneSpec(remoteID)!!.isCurrentFlightConfirmed)
        assertEquals(ActiveScreen.STREAMS, model.activeScreen.value)
        assertFalse(CaltopoClient.SaveLocalPairedDrone(remoteID, "replacement", "", ""))
        assertFalse(CaltopoClient.SaveLocalPairedDrone("1581F8HGX255S00A0FZT", "FIELD-DRONE-1", "", ""))
    }

    @Test fun pairVideoOnlyDoesNotSaveOrConfirmDrone() {
        val remoteID = "1581F6Z9C24BH0036EJK"
        CaltopoClient.ClientForRemoteId(remoteID)
        val model = R2CViewModel(SimpleTimer())
        model.requestPairedDroneSetup(remoteID, "field-drone-1")
        assertNotNull(model.pendingDroneConfirmation.value)
        model.markPendingDroneConfirmationUnknown()
        assertTrue(CaltopoClient.GetPersistedDroneSpecs().none { it.remoteId == remoteID })
        assertFalse(CaltopoClient.GetDroneSpec(remoteID)!!.isCurrentFlightConfirmed)
        assertNull(model.pendingDroneConfirmation.value)
    }

    private fun activeDrone(remoteId: String, waypointTimestampMsec: Long): CtDroneSpec {
        val drone = CtDroneSpec(remoteId)
        drone.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            waypointTimestampMsec,
            waypointTimestampMsec,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        return drone
    }

    private fun clearLocalTrackListeners() {
        listOf("LocalTrackListeners", "LocalTrackFinishedListeners").forEach { fieldName ->
            CaltopoLiveTrack::class.java.getDeclaredField(fieldName).apply {
                isAccessible = true
                (get(null) as MutableCollection<*>).clear()
            }
        }
        CaltopoMap::class.java.getDeclaredField("MapListeners").apply {
            isAccessible = true
            (get(null) as MutableCollection<*>).clear()
        }
    }
}
