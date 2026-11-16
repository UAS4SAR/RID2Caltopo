package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkKind
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkStatus

/** Same cases as the iPad's BtrxMapStatusTests. */
class BtrxMapStatusTest {
    private fun ble(phase: LinkPhase, failed: Int = 0) = LinkStatus(LinkKind.BLUETOOTH, phase, failedAttempts = failed)
    private fun text(link: LinkStatus, talking: Boolean = false, bluetoothOff: Boolean = false, pausedForUsb: Boolean = false) =
        BtrxMapStatus.status(true, link, bluetoothOff, pausedForUsb, talking)!!.text

    @Test
    fun hiddenUntilBtrxIsSetUp() {
        assertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled = false, deskPodRemembered = false, usbLink = false, pausedForUsb = false))
        assertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled = true, deskPodRemembered = false, usbLink = false, pausedForUsb = false))
        assertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled = false, deskPodRemembered = true, usbLink = false, pausedForUsb = false))
        assertNull(BtrxMapStatus.status(false, ble(LinkPhase.READY), false, false, true))
    }

    @Test
    fun activeOnlyWhenReadyAndTalking() {
        val s = BtrxMapStatus.status(true, ble(LinkPhase.READY), false, false, talking = true)!!
        assertEquals("BTRX: Active", s.text)
        assertTrue(s.active)
        val quiet = BtrxMapStatus.status(true, ble(LinkPhase.READY), false, false, talking = false)!!
        assertEquals("BTRX: Inactive - No Drone Pod reports", quiet.text)
        assertFalse(quiet.active)
        // Talking is only meaningful while READY.
        assertEquals("BTRX: Inactive - BT Down", text(ble(LinkPhase.RETRY_WAIT, 1), talking = true))
    }

    @Test
    fun reasons() {
        assertEquals("BTRX: Inactive - Connecting", text(ble(LinkPhase.SCANNING)))
        assertEquals("BTRX: Inactive - Connecting", text(ble(LinkPhase.CONNECTING)))
        assertEquals("BTRX: Inactive - Connecting", text(ble(LinkPhase.CONFIGURING, 3)))
        assertEquals("BTRX: Inactive - BT Down", text(ble(LinkPhase.RETRY_WAIT, 1)))
        assertEquals("BTRX: Inactive - BT Down", text(ble(LinkPhase.CONNECTING, 2)))
        assertEquals("BTRX: Inactive - BT Down", text(ble(LinkPhase.SCANNING, 2)))
        assertEquals("BTRX: Inactive - Bluetooth off", text(ble(LinkPhase.UNAVAILABLE, 1), bluetoothOff = true))
        assertEquals("BTRX: Inactive - Bluetooth off", text(ble(LinkPhase.RETRY_WAIT, 1), bluetoothOff = true))
        assertEquals("BTRX: Inactive - Bluetooth unavailable", text(ble(LinkPhase.UNAVAILABLE)))
        assertEquals("BTRX: Inactive - No permission", text(ble(LinkPhase.NO_PERMISSION, 1)))
        assertEquals("BTRX: Inactive - Paused (USB)", text(ble(LinkPhase.PAUSED_FOR_USB), pausedForUsb = true))
        assertEquals("BTRX: Inactive - Link off", text(LinkStatus(LinkKind.NONE, LinkPhase.OFF), bluetoothOff = true))
    }

    @Test
    fun usbLink() {
        assertEquals("BTRX: Active", text(LinkStatus(LinkKind.USB, LinkPhase.READY), talking = true))
        assertEquals("BTRX: Inactive - No Drone Pod reports", text(LinkStatus(LinkKind.USB, LinkPhase.READY)))
        assertEquals("BTRX: Inactive - Connecting", text(LinkStatus(LinkKind.USB, LinkPhase.CONFIGURING)))
    }
}
