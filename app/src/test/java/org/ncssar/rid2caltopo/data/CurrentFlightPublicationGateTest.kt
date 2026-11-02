package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.ui.CompletedFlightConfirmationGuard

class CurrentFlightPublicationGateTest {
    @Test
    fun unconfirmedAndIgnoredNeverPublish() {
        assertEquals(
            CurrentFlightPublicationGate.GATED_UNCONFIRMED,
            CurrentFlightPublicationGate.decide(confirmed = false, ignored = false)
        )
        assertEquals(
            CurrentFlightPublicationGate.GATED_IGNORED,
            CurrentFlightPublicationGate.decide(confirmed = false, ignored = true)
        )
        assertFalse(CurrentFlightPublicationGate.decide(confirmed = false, ignored = false).allowsPublication)
        assertFalse(CurrentFlightPublicationGate.decide(confirmed = true, ignored = true).allowsPublication)
    }

    @Test
    fun confirmedAllowsPublication() {
        val allowed = CurrentFlightPublicationGate.decide(confirmed = true, ignored = false)
        assertEquals(CurrentFlightPublicationGate.ALLOWED, allowed)
        assertTrue(allowed.allowsPublication)
        assertEquals("allowed", allowed.logLabel)
    }

    @Test
    fun quickReturnWithoutPublisherRequiresNewConfirmationBeforePublish() {
        val logs = mutableListOf<String>()
        val guard = CompletedFlightConfirmationGuard { logs.add(it) }
        val remoteId = "1581F6Z9C24BH0036EJL"
        guard.updateSessions(mapOf(remoteId to setOf("1sar7djmn4pr|192.168.68.60:39302")))
        guard.end(remoteId, 1_000L)
        // Same aircraft returns ~51s later with fresh receive time and no publisher session.
        assertTrue(guard.allows(remoteId, 1_051L))
        assertTrue(logs.any { it.contains("fresh_aircraft") })
        assertEquals(
            CurrentFlightPublicationGate.GATED_UNCONFIRMED,
            CurrentFlightPublicationGate.decide(confirmed = false, ignored = false)
        )
        assertFalse(
            CurrentFlightPublicationGate.decide(confirmed = false, ignored = false).allowsPublication
        )
        assertTrue(
            CurrentFlightPublicationGate.decide(confirmed = true, ignored = false).allowsPublication
        )
    }

    @Test
    fun flightEndClearsConfirmedSoMapAndTrackerStayGatedUntilNextSave() {
        val remoteId = "RIDQUICKRETURN"
        CaltopoClient.ClientForRemoteId(remoteId)
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId, "NCSSAR", "DJI Mini 4 Pro", "1SAR7", "1sar7DjMn4Pr"
        )
        val confirmed = CaltopoClient.GetDroneSpec(remoteId)!!
        assertTrue(confirmed.isCurrentFlightConfirmed)
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        assertTrue(
            CurrentFlightPublicationGate.decide(
                confirmed = confirmed.isCurrentFlightConfirmed,
                ignored = false
            ).allowsPublication
        )

        CaltopoClient.ClearCurrentFlightConfirmation(remoteId)
        val after = CaltopoClient.GetDroneSpec(remoteId)!!
        assertFalse(after.isCurrentFlightConfirmed)
        assertFalse(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        assertFalse(CaltopoClient.IsSessionDroneConfirmed(remoteId))
        assertEquals(
            CurrentFlightPublicationGate.GATED_UNCONFIRMED,
            CurrentFlightPublicationGate.decide(
                confirmed = after.isCurrentFlightConfirmed ||
                    CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId),
                ignored = CaltopoClient.IsSessionUnknownDrone(remoteId)
            )
        )
    }
}
