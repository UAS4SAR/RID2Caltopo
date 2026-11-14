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
