package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class ShortFlightRecordingTest {
    @Test fun bothLimitsAreStrictAndUnknownMeasurementsDoNotOfferDiscard() {
        assertTrue(isShortFlight(59.999, 160.9343))
        assertTrue(isShortFlight(0.0, 0.0))
        assertFalse(isShortFlight(60.0, 0.0))
        assertFalse(isShortFlight(1.0, 160.9344))
        assertFalse(isShortFlight(Double.NaN, 0.0))
        assertFalse(isShortFlight(-1.0, 0.0))
    }
    @Test fun timeoutKeepsExactlyOnceAndLateNoCannotDiscard() {
        var now = 0L
        var saved = 0
        val gate = ShortFlightRecordingGate { now }
        gate.setActive(true)
        assertTrue(gate.request("A", 10.0, 1.0) { saved++ })
        val id = gate.prompts.value.single().id
        now = 9999; gate.expire(); assertEquals(0, saved)
        now = 10000; gate.decide(id, false); gate.expire()
        assertEquals(1, saved)
        assertTrue(gate.prompts.value.isEmpty())
    }
    @Test fun noResponseAtTenSecondsAutomaticallyRecords() {
        var now = 0L; var saved = 0
        val gate = ShortFlightRecordingGate { now }
        gate.setActive(true)
        gate.request("A", 10.0, 1.0) { saved++ }
        now = 10_000; gate.expire(); gate.expire()
        assertEquals(1, saved)
        assertTrue(gate.prompts.value.isEmpty())
    }
    @Test fun choicesArePerFlightAndBackgroundDefaultsYes() {
        val gate = ShortFlightRecordingGate { 0 }
        var savedA = 0; var savedB = 0
        gate.setActive(true)
        gate.request("A", 10.0, 1.0) { savedA++ }
        gate.request("B", 10.0, 1.0) { savedB++ }
        gate.decide(gate.prompts.value.first().id, false)
        gate.setActive(false)
        assertEquals(0, savedA); assertEquals(1, savedB)
        assertFalse(gate.request("C", 10.0, 1.0) { fail("Caller records synchronously when inactive") })
    }
    @Test fun yesRecordsImmediatelyAndTimeoutCannotDuplicate() {
        var now = 0L; var saved = 0
        val gate = ShortFlightRecordingGate { now }
        gate.setActive(true)
        gate.request("A", 10.0, 1.0) { saved++ }
        gate.decide(gate.prompts.value.single().id, true)
        now = 20000; gate.expire()
        assertEquals(1, saved)
    }
}
