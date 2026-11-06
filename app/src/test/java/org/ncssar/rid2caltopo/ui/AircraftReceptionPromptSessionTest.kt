package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CtDroneSpec

class AircraftReceptionPromptSessionTest {
    @Before
    fun setUp() {
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun firstSightingOpensOnceAndASecondPacketDoesNot() {
        val session = AircraftReceptionPromptSession()
        assertEquals(
            AircraftReceptionPromptDecision.Open,
            session.observe("BIRD0001", unidentified = true, dispositionedThisSession = false, panelOpen = false),
        )
        assertEquals(
            AircraftReceptionPromptDecision.Skip,
            session.observe("BIRD0001", unidentified = true, dispositionedThisSession = false, panelOpen = false),
        )
        assertEquals(
            AircraftReceptionPromptDecision.Skip,
            session.observe("  BIRD0001  ", unidentified = true, dispositionedThisSession = false, panelOpen = true),
        )
    }

    @Test
    fun aNewIdOpensAgain() {
        val session = AircraftReceptionPromptSession()
        assertEquals(
            AircraftReceptionPromptDecision.Open,
            session.observe("BIRD0001", unidentified = true, dispositionedThisSession = false, panelOpen = false),
        )
        assertEquals(
            AircraftReceptionPromptDecision.AlreadyVisible,
            session.observe("BIRD0002", unidentified = true, dispositionedThisSession = false, panelOpen = true),
        )
        assertEquals(
            AircraftReceptionPromptDecision.Skip,
            session.observe("BIRD0002", unidentified = true, dispositionedThisSession = false, panelOpen = true),
        )
    }

    @Test
    fun clearedSessionOpensAgain() {
        val session = AircraftReceptionPromptSession()
        session.observe("BIRD0001", unidentified = true, dispositionedThisSession = false, panelOpen = false)
        session.reset()
        assertEquals(
            AircraftReceptionPromptDecision.Open,
            session.observe("BIRD0001", unidentified = true, dispositionedThisSession = false, panelOpen = false),
        )
    }

    @Test
    fun anOpenPanelDoesNotStackAndStillRecordsTheAircraft() {
        val session = AircraftReceptionPromptSession()
        assertEquals(
            AircraftReceptionPromptDecision.AlreadyVisible,
            session.observe("BIRD0001", unidentified = true, dispositionedThisSession = false, panelOpen = true),
        )
        assertEquals(
            AircraftReceptionPromptDecision.Skip,
            session.observe("BIRD0001", unidentified = true, dispositionedThisSession = false, panelOpen = false),
        )
    }

    @Test
    fun teamListAndDontPublishDoNotAutoOpen() {
        val session = AircraftReceptionPromptSession()
        assertFalse(AircraftReceptionPromptSession.isUnidentifiedAircraft("TEAM0001", "Mini4", "NCSSAR"))
        assertTrue(AircraftReceptionPromptSession.isUnidentifiedAircraft("BIRD0001", "BIRD0001", ""))
        assertTrue(AircraftReceptionPromptSession.isUnidentifiedAircraft("BIRD0001", "", "  "))
        assertEquals(
            AircraftReceptionPromptDecision.Skip,
            session.observe("TEAM0001", unidentified = false, dispositionedThisSession = false, panelOpen = false),
        )
        assertEquals(
            AircraftReceptionPromptDecision.Open,
            session.observe("TEAM0001", unidentified = true, dispositionedThisSession = false, panelOpen = false),
        )
        assertEquals(
            AircraftReceptionPromptDecision.Skip,
            session.observe("NOPUB001", unidentified = true, dispositionedThisSession = true, panelOpen = false),
        )
        assertEquals(
            AircraftReceptionPromptDecision.Skip,
            session.observe("NOPUB001", unidentified = true, dispositionedThisSession = false, panelOpen = false),
        )
    }

    @Test
    fun logTokenOmitsTheAircraftId() {
        val aircraftId = "1581F8HGX255S00A0FZT"
        val token = AircraftReceptionPromptSession.logToken(aircraftId)
        assertEquals(8, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(token == aircraftId)
        assertFalse(token.contains(aircraftId))
        assertFalse(aircraftId.contains(token))
        assertEquals(token, AircraftReceptionPromptSession.logToken("  $aircraftId  "))
    }

    @Test
    fun unidentifiedSightingOpensReceptionOncePerViewModel() {
        val first = R2CViewModel(org.ncssar.rid2caltopo.data.SimpleTimer())
        val drone = localOnly("NEWBIRD01")
        first.onDroneSpecsChanged(listOf(drone))
        assertNull(first.pendingDroneConfirmation.value)
        assertEquals(1, first.aircraftReceptionAutoOpenGeneration.value)
        assertEquals("NEWBIRD01", first.aircraftReceptionFocusRemoteId.value)
        first.onDroneSpecsChanged(listOf(drone, localOnly("NEWBIRD02")))
        assertEquals(1, first.aircraftReceptionAutoOpenGeneration.value)
        assertEquals("NEWBIRD02", first.aircraftReceptionFocusRemoteId.value)
        first.onDroneSpecsChanged(listOf(drone, localOnly("NEWBIRD02")))
        assertEquals(1, first.aircraftReceptionAutoOpenGeneration.value)

        val restarted = R2CViewModel(org.ncssar.rid2caltopo.data.SimpleTimer())
        restarted.onDroneSpecsChanged(listOf(localOnly("NEWBIRD01")))
        assertEquals(1, restarted.aircraftReceptionAutoOpenGeneration.value)
    }

    @Test
    fun openPanelDoesNotRequestAnotherPresentation() {
        val model = R2CViewModel(org.ncssar.rid2caltopo.data.SimpleTimer())
        model.setAircraftReceptionPanelOpen(true)
        model.onDroneSpecsChanged(listOf(localOnly("NEWBIRD01")))
        assertEquals(0, model.aircraftReceptionAutoOpenGeneration.value)
        assertEquals("NEWBIRD01", model.aircraftReceptionFocusRemoteId.value)
        model.setAircraftReceptionPanelOpen(false)
        model.onDroneSpecsChanged(listOf(localOnly("NEWBIRD01")))
        assertEquals(0, model.aircraftReceptionAutoOpenGeneration.value)
    }

    @Test
    fun teamDroneAndDontPublishLeaveReceptionClosed() {
        val model = R2CViewModel(org.ncssar.rid2caltopo.data.SimpleTimer())
        val known = CtDroneSpec("TEAM0001", "Mini4", "NCSSAR", "Mini", "Pilot")
        model.onDroneSpecsChanged(listOf(known))
        assertEquals(0, model.aircraftReceptionAutoOpenGeneration.value)
        assertNull(model.pendingDroneConfirmation.value)

        val declined = localOnly("NOPUB001")
        CaltopoClient.SaveDroneSpecUnknownConfirmation(declined.remoteId)
        model.onDroneSpecsChanged(listOf(declined))
        assertNull(model.pendingDroneConfirmation.value)
        assertEquals(0, model.aircraftReceptionAutoOpenGeneration.value)
    }

    @Test
    fun automaticConfirmationIsNotStackedWithReception() {
        val model = R2CViewModel(org.ncssar.rid2caltopo.data.SimpleTimer())
        val drone = CtDroneSpec("CONFIRM01")
        model.onLiveStreamDesignatorsChanged(setOf("CONFIRM01"), listOf(drone))
        assertEquals("CONFIRM01", model.pendingDroneConfirmation.value?.remoteId)
        model.onDroneSpecsChanged(listOf(drone))
        assertEquals(0, model.aircraftReceptionAutoOpenGeneration.value)
        model.onDroneSpecsChanged(listOf(drone))
        assertEquals(0, model.aircraftReceptionAutoOpenGeneration.value)
    }

    private fun localOnly(remoteId: String): CtDroneSpec {
        val drone = CtDroneSpec(remoteId)
        drone.setLocalArchiveOnly(true)
        return drone
    }
}
