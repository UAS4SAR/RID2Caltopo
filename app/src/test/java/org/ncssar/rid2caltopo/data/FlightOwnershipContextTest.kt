package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightOwnershipContextTest {
    @Test fun minePendingIsHeldWithoutTreatingItAsVerified() {
        val snapshot = FlightOwnershipContext.capture(true, false, false, FlightOwnershipContext.PENDING, "1SAR7", "NCSSAR", "Mini", "1SAR7Mini")
        assertTrue(snapshot.requiresHold)
        assertFalse(snapshot.mayAutoPublish)
        assertEquals("1SAR7", snapshot.recordedPilot)
        assertFalse(snapshot.blocksOperatorPublish)
    }

    @Test fun adverseClaimsStayHeldAndDoNotCopyAnotherPilot() {
        for (claim in listOf(FlightOwnershipContext.REJECTED, FlightOwnershipContext.CONFLICT, FlightOwnershipContext.EXPIRED)) {
            val snapshot = FlightOwnershipContext.capture(true, false, false, claim, "1SAR7", "NCSSAR", "Mini", "1SAR7Mini")
            assertTrue(claim, snapshot.requiresHold)
            assertFalse(snapshot.mayAutoPublish)
            assertEquals("1SAR7", snapshot.recordedPilot)
        }
    }

    @Test fun verifiedMineCanPublishAndOfflineMineIsNotAFailedClaim() {
        val verified = FlightOwnershipContext.capture(true, false, false, FlightOwnershipContext.VERIFIED, "1SAR7", "NCSSAR", "Mini", "mid")
        assertFalse(verified.requiresHold)
        assertTrue(verified.mayAutoPublish)
        val offline = FlightOwnershipContext.capture(true, false, false, FlightOwnershipContext.NONE, "1SAR7", "NCSSAR", "Mini", "mid")
        assertFalse(offline.requiresHold)
        assertTrue(offline.mayAutoPublish)
        assertEquals("1SAR7", offline.recordedPilot)
    }

    @Test fun unconfirmedAndNotMineDoNotPublishOrInventAPilot() {
        val unconfirmed = FlightOwnershipContext.capture(false, false, false, FlightOwnershipContext.NONE, "1SAR7", "NCSSAR", "Mini", "mid")
        assertEquals(FlightOwnershipContext.UNCONFIRMED, unconfirmed.confirmation)
        assertTrue(unconfirmed.blocksOperatorPublish)
        assertFalse(unconfirmed.mayAutoPublish)
        assertEquals("", unconfirmed.recordedPilot)
        val ignored = FlightOwnershipContext.capture(false, true, true, FlightOwnershipContext.NONE, "1SAR7", "", "", "")
        assertEquals(FlightOwnershipContext.IGNORED, ignored.confirmation)
        assertEquals("", ignored.recordedPilot)
        assertTrue(ignored.blocksOperatorPublish)
    }

    @Test fun legacyContextIsHeldAndRoundTripsWithoutAPilot() {
        val legacy = FlightOwnershipContext.legacy()
        assertFalse(legacy.known)
        assertTrue(legacy.requiresHold)
        assertFalse(legacy.mayAutoPublish)
        assertEquals("", legacy.recordedPilot)
        val restored = FlightOwnershipContext.fromJson(FlightOwnershipContext.toJson(legacy))
        assertFalse(restored.known)
        val mine = FlightOwnershipContext.capture(true, false, false, FlightOwnershipContext.PENDING, "1SAR7", "NCSSAR", "Mini", "mid")
        val decoded = FlightOwnershipContext.fromJson(FlightOwnershipContext.toJson(mine))
        assertEquals("1SAR7", decoded.recordedPilot)
        assertEquals(FlightOwnershipContext.PENDING, decoded.claim)
    }
}
