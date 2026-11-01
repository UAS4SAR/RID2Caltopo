package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClueUploadWakeGateTest {
    @Test
    fun idleOwnerStartsFreshLoop() {
        val decision = ClueUploadWakeGate.decide(ownerActive = false, inFlightIds = emptyList())
        assertTrue(decision.startOwner)
        assertNull(decision.skipLogIds)
    }

    @Test
    fun activeOwnerWithUploadIdsLogsThoseIdsAndDoesNotRestart() {
        val decision = ClueUploadWakeGate.decide(
            ownerActive = true,
            inFlightIds = listOf("fda322b4", "1eb73175"),
        )
        assertFalse(decision.startOwner)
        assertEquals(listOf("fda322b4", "1eb73175"), decision.skipLogIds)
    }

    @Test
    fun activeOwnerWithEmptyIdsNeverClaimsUploadInFlight() {
        // Reconcile/reverify used to hold the owner with ids={} while Submit skipped.
        val decision = ClueUploadWakeGate.decide(ownerActive = true, inFlightIds = emptyList())
        assertFalse(decision.startOwner)
        assertNull(decision.skipLogIds)
    }

    @Test
    fun blankIdsAreIgnoredInSkipLog() {
        val decision = ClueUploadWakeGate.decide(ownerActive = true, inFlightIds = listOf("", "  "))
        assertFalse(decision.startOwner)
        assertNull(decision.skipLogIds)
    }
}
