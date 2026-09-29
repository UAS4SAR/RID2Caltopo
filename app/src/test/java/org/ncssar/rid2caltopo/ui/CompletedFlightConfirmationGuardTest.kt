package org.ncssar.rid2caltopo.ui

import org.junit.Assert.*
import org.junit.Test

class CompletedFlightConfirmationGuardTest {
    @Test fun completedFlightNeedsFreshEvidenceAndLogsOnlyTransitions() {
        val logs = mutableListOf<String>()
        val guard = CompletedFlightConfirmationGuard { logs.add(it) }
        guard.updateSessions(mapOf("RID" to setOf("stream|old")))
        assertTrue(guard.allows("RID", 90))
        guard.end("RID", 100)
        repeat(10) { assertFalse(guard.allows("RID", 100)) }
        assertEquals(2, logs.size)
        assertTrue(guard.allows("RID", 101))
        assertTrue(guard.allows("RID", 101))
        assertEquals(3, logs.size)
        assertTrue(logs.last().contains("fresh_aircraft"))
    }

    @Test fun repeatedEndAndTemporaryDisappearanceCannotReviveOldPublisher() {
        val guard = CompletedFlightConfirmationGuard()
        guard.updateSessions(mapOf("RID" to setOf("stream|old")))
        // The registry can publish removal before the track-end callback.
        guard.updateSessions(emptyMap())
        guard.end("RID", 100)
        guard.updateSessions(emptyMap())
        guard.end("RID", 110)
        guard.updateSessions(mapOf("RID" to setOf("stream|old")))
        assertFalse(guard.allows("RID"))
        guard.updateSessions(mapOf("RID" to setOf("stream|new")))
        assertTrue(guard.allows("RID"))
    }

    @Test fun lateIdentityResolutionDoesNotProveANewPublisher() {
        val guard = CompletedFlightConfirmationGuard()
        guard.updateSessions(mapOf("RID" to setOf("stream|unknown")))
        guard.end("RID", 100)
        guard.updateSessions(mapOf("RID" to setOf("stream|now-known")))
        assertFalse(guard.allows("RID"))
        assertTrue(guard.allows("RID", 101))
    }

    @Test fun unknownPublisherAndOtherAircraftCannotRearmRetiredFlight() {
        val guard = CompletedFlightConfirmationGuard()
        guard.end("RID", 100)
        guard.updateSessions(mapOf("RID" to setOf("stream|unknown"), "OTHER" to setOf("stream2|new")))
        assertFalse(guard.allows("RID", 99))
        assertTrue(guard.allows("OTHER", 101))
        assertFalse(guard.allows("RID"))
    }
}
