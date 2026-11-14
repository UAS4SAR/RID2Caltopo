package org.ncssar.rid2caltopo.app

import org.junit.Assert.*
import org.junit.Test

class ForegroundLocationGateTest {
    @Test fun locationRequiresBothSessionAndVisibleActivity() {
        val gate = ForegroundLocationGate()
        assertNull(gate.enable())
        val first = gate.activityResumed()!!
        assertTrue(gate.accepts(first))
        assertNull(gate.activityResumed()) // another app screen overlaps
        assertNull(gate.activityPaused())
        assertTrue(gate.accepts(first))
        gate.activityPaused()
        assertFalse(gate.accepts(first))
        val reopened = gate.activityResumed()!!
        assertTrue(gate.accepts(reopened))
        assertFalse(gate.accepts(first)) // an old asynchronous fix must not be published
        gate.disable()
        assertFalse(gate.accepts(reopened))
        assertNull(gate.activityPaused())
    }

    @Test fun activityBeforeConsentDoesNotStartLocation() {
        val gate = ForegroundLocationGate()
        assertNull(gate.activityResumed())
        assertFalse(gate.session.active)
        assertTrue(gate.accepts(gate.enable()!!))
    }

    @Test fun accuracyLineIsHonestAboutPrecision() {
        assertEquals("Tablet location: precise ±5 m", DeviceLocationText.accuracyLine(4.6f, true))
        assertEquals(
            "Tablet location: approximate ±2000 m (precise location is off for this app in Android settings)",
            DeviceLocationText.accuracyLine(2000f, false)
        )
        assertEquals("Tablet location: precise", DeviceLocationText.accuracyLine(null, true))
        assertEquals("Tablet location: precise", DeviceLocationText.accuracyLine(Float.NaN, true))
    }
}
