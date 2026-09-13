package org.ncssar.rid2caltopo.video.surface

import org.junit.Assert.*
import org.junit.Test

class SurfaceWorkGateTest {
    @Test fun launchAcceptsGroundTypedZeroButRequiresFreshGroundEvidence() {
        assertTrue(canObserveAolLaunch(true,0.0,true,true))
        assertTrue(canObserveAolLaunch(true,0.5,true,true))
        assertFalse(canObserveAolLaunch(false,0.0,true,true))
        assertFalse(canObserveAolLaunch(true,0.0,false,true))
        assertFalse(canObserveAolLaunch(true,0.0,true,false))
        assertFalse(canObserveAolLaunch(true,2.0,true,true))
        assertFalse(canObserveAolLaunch(true,-1000.0,true,true))
        assertFalse(canObserveAolLaunch(true,Double.NaN,true,true))
    }
    @Test fun disabledBusyAndRateLimitedWorkIsNeverAdmitted() {
        val gate = SurfaceWorkGate()
        assertFalse(gate.begin("a", false, 0))
        assertTrue(gate.begin("a", true, 0))
        assertFalse(gate.begin("b", true, 5000))
        gate.finish()
        assertFalse(gate.begin("a", true, 999))
        assertTrue(gate.begin("b", true, 999))
        gate.finish()
        assertTrue(gate.begin("a", true, 1000))
        gate.finish()
        assertFalse(gate.begin("a", false, 5000))
        assertTrue(gate.begin("a", true, 5000))
    }
    @Test fun removedAircraftDoesNotRetainItsCooldown() {
        val gate = SurfaceWorkGate()
        assertTrue(gate.begin("a", true, 0))
        gate.forget("a")
        assertFalse(gate.begin("a", true, 1)) // Forgetting cannot open a busy worker.
        gate.finish()
        assertTrue(gate.begin("a", true, 1))
    }
}
