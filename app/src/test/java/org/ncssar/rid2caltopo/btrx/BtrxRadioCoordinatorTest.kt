package org.ncssar.rid2caltopo.btrx

import android.bluetooth.le.ScanSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase

class BtrxRadioCoordinatorTest {
    private val c = BtrxRadioCoordinator

    @Before fun setUp() = c.resetForTest()
    @After fun tearDown() = c.resetForTest()

    @Test
    fun busyWhileConnectingConfiguringOrReady() {
        val busy = LinkPhase.values().filter { c.isBusy(it) }.toSet()
        assertEquals(setOf(LinkPhase.CONNECTING, LinkPhase.CONFIGURING, LinkPhase.READY), busy)
        assertFalse(c.deskPodLinkBusy())
        c.onLinkPhase(LinkPhase.CONNECTING)
        assertTrue(c.deskPodLinkBusy())
        assertFalse(c.deskPodLinkReady())
        c.onLinkPhase(LinkPhase.RETRY_WAIT)
        assertFalse(c.deskPodLinkBusy())
    }

    @Test
    fun periodicRestartIsSkippedWhileBusyUpToTheDeferralLimit() {
        assertFalse(c.deferPeriodicRestart(linkBusy = false, scanRunningMs = 120_000))
        assertTrue(c.deferPeriodicRestart(linkBusy = true, scanRunningMs = 120_000))
        assertTrue(c.deferPeriodicRestart(linkBusy = true, scanRunningMs = c.MAX_RESTART_DEFERRAL_MS - 1))
        assertFalse(c.deferPeriodicRestart(linkBusy = true, scanRunningMs = c.MAX_RESTART_DEFERRAL_MS))
        // Below Android's 30 minute scan downgrade.
        assertTrue(c.MAX_RESTART_DEFERRAL_MS < 30 * 60_000L)
        c.onLinkPhase(LinkPhase.READY)
        assertTrue(c.deferPeriodicRestart(240_000))
    }

    @Test
    fun scanModeStaysLowLatencyUnlessRelaxed() {
        assertFalse(c.RELAX_RID_SCAN_WHILE_LINKED) // the default; see the constant's note
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, c.ridScanMode(linkReady = true))
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, c.ridScanMode(linkReady = false, relax = true))
        assertEquals(ScanSettings.SCAN_MODE_BALANCED, c.ridScanMode(linkReady = true, relax = true))
    }

    @Test
    fun listenersHearOnlyReadyTransitions() {
        val heard = mutableListOf<Boolean>()
        val l = BtrxRadioCoordinator.Listener { heard += it }
        c.addListener(l)
        c.onLinkPhase(LinkPhase.CONNECTING)
        c.onLinkPhase(LinkPhase.CONFIGURING)
        c.onLinkPhase(LinkPhase.READY)
        c.onLinkPhase(LinkPhase.READY)
        c.onLinkPhase(LinkPhase.RETRY_WAIT)
        c.removeListener(l)
        c.onLinkPhase(LinkPhase.READY)
        assertEquals(listOf(true, false), heard)
    }

    @Test
    fun adapterResetRefusedWhileADeskPodGattIsOpen() {
        assertTrue(c.adapterResetAllowed())
        c.onGattOpen(true)
        assertFalse(c.adapterResetAllowed())
        assertFalse(c.adapterResetAllowed(deskPodGattOpen = true))
        c.onGattOpen(false)
        assertTrue(c.adapterResetAllowed())
    }
}
