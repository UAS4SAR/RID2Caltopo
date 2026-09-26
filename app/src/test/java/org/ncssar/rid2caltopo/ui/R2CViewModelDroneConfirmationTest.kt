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
    @Test fun telemetryTimeoutClearsConfirmationEvenWhileVideoRemainsListed() {
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
        model.onDroneSpecsChanged(listOf(drone))
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
    fun trackerConfirmedRemoteIdDoesNotReopenDuringCurrentActiveLifecycle() {
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

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun trackerConfirmationReplayBeforeLocalSightingSuppressesCurrentPanel() {
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

        assertNull(viewModel.pendingDroneConfirmation.value)
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
    fun trackerConfirmationRemainsSuppressedAfterBriefTrackerLifecycleGap() {
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

        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoClient.ClearCurrentPeerDroneConfirmation(remoteId)
        viewModel.onDroneSpecsChanged(emptyList())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNull(viewModel.pendingDroneConfirmation.value)
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
    fun peerConfirmedFirstRidSightingDoesNotOpenConfirmationPanel() {
        val remoteId = "DRONEFIRSTSEENPEER"
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(CtDroneSpec(remoteId))

        assertNull(viewModel.pendingDroneConfirmation.value)
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

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)

        CaltopoClient.ClearCurrentPeerDroneConfirmation(remoteId)
        val recreatedViewModel = R2CViewModel(SimpleTimer())
        recreatedViewModel.onDroneConfirmationCandidate(CtDroneSpec(remoteId))

        assertTrue(CaltopoClient.IsSessionDroneConfirmed(remoteId))
        assertEquals(remoteId, recreatedViewModel.pendingDroneConfirmation.value?.remoteId)
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

        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
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
