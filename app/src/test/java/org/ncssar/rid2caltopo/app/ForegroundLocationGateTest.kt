package org.ncssar.rid2caltopo.app

import org.junit.Assert.*
import org.junit.Test

class ForegroundLocationGateTest {
    @Test fun locationRequiresBothSessionAndVisibleActivity() {
        val gate = ForegroundLocationGate()
        assertNull(gate.enable())
        val first = gate.activityStarted()!!
        assertTrue(gate.accepts(first))
        assertNull(gate.activityStarted()) // another app screen overlaps
        assertNull(gate.activityStopped())
        assertTrue(gate.accepts(first))
        gate.activityStopped()
        assertFalse(gate.accepts(first))
        val reopened = gate.activityStarted()!!
        assertTrue(gate.accepts(reopened))
        assertFalse(gate.accepts(first)) // an old asynchronous fix must not be published
        gate.disable()
        assertFalse(gate.accepts(reopened))
        assertNull(gate.activityStopped())
    }

    @Test fun activityBeforeConsentDoesNotStartLocation() {
        val gate = ForegroundLocationGate()
        assertNull(gate.activityStarted())
        assertFalse(gate.session.active)
        assertTrue(gate.accepts(gate.enable()!!))
    }
}
