package org.ncssar.rid2caltopo.app

import org.junit.Assert.*
import org.junit.Test

class NetworkCheckRecoveryGateTest {
    @Test fun offlineToOnlineRefreshesOnceDespiteRepeatedCallbacks() {
        val gate = NetworkCheckRecoveryGate()
        for (online in listOf(false, false)) assertFalse(gate.update(online))
        assertTrue(gate.update(true))
        repeat(10) { assertFalse(gate.update(true)) }
        assertFalse(gate.update(false))
        assertTrue(gate.update(true))
    }
    @Test fun startupOnlineRequestsOneRefresh() {
        val gate = NetworkCheckRecoveryGate()
        assertTrue(gate.update(true))
        assertFalse(gate.update(true))
    }
}
