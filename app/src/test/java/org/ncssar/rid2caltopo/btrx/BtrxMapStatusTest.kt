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
    private fun text(link: LinkStatus, reporting: Boolean = false, bluetoothOff: Boolean = false, pausedForUsb: Boolean = false) =
        BtrxMapStatus.status(true, link, bluetoothOff, pausedForUsb, reporting)!!.text

    @Test
    fun hiddenUntilBtrxIsSetUp() {
        assertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled = false, deskPodRemembered = false, usbLink = false, pausedForUsb = false))
        assertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled = true, deskPodRemembered = false, usbLink = false, pausedForUsb = false))
        assertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled = false, deskPodRemembered = true, usbLink = false, pausedForUsb = false))
        assertNull(BtrxMapStatus.status(false, ble(LinkPhase.READY), false, false, true))
        assertNull(BtrxMapStatus.status(false, ble(LinkPhase.READY), false, false, false))
    }

    @Test
    fun activeFollowsTheLinkAndWarnsWhenTheDronePodIsQuiet() {
        val s = BtrxMapStatus.status(true, ble(LinkPhase.READY), false, false, dronePodReporting = true)!!
        assertEquals("BTRX: Active", s.text)
        assertTrue(s.active)
        assertFalse(s.warning)
        // READY with no Drone Pod reports is still Active (the link is up), in orange.
        val quiet = BtrxMapStatus.status(true, ble(LinkPhase.READY), false, false, dronePodReporting = false)!!
        assertEquals("BTRX: Active - No Drone Pod reports", quiet.text)
        assertTrue(quiet.active)
        assertTrue(quiet.warning)
        // Reports only matter while READY.
        assertEquals("BTRX: Inactive - BT Down", text(ble(LinkPhase.RETRY_WAIT, 1), reporting = true))
        assertFalse(BtrxMapStatus.status(true, ble(LinkPhase.RETRY_WAIT, 1), false, false, true)!!.warning)
    }

    @Test
    fun dronePodReportWindowIs15Seconds() {
        assertEquals(15_000L, BtrxMapStatus.DRONE_POD_REPORT_MS)
        assertFalse(BtrxMapStatus.dronePodReporting(0L, 100_000L)) // none yet
        assertTrue(BtrxMapStatus.dronePodReporting(100_000L, 100_000L))
        assertTrue(BtrxMapStatus.dronePodReporting(100_000L, 115_000L))
        assertFalse(BtrxMapStatus.dronePodReporting(100_000L, 115_001L))
        assertFalse(BtrxMapStatus.dronePodReporting(100_000L, 99_000L)) // clock went back: not trusted
    }

    @Test
    fun onlyLivePairedPodFramesOtherThanPairReqCount() {
        assertTrue(BtrxMapStatus.isDronePodReport(BtrxProtocol.STEER, backlog = false, fromPairedPod = true))
        assertTrue(BtrxMapStatus.isDronePodReport(BtrxProtocol.HEALTH, backlog = false, fromPairedPod = true))
        assertTrue(BtrxMapStatus.isDronePodReport(BtrxProtocol.DETECT, backlog = false, fromPairedPod = true))
        assertFalse(BtrxMapStatus.isDronePodReport(BtrxProtocol.STEER, backlog = true, fromPairedPod = true))
        assertFalse(BtrxMapStatus.isDronePodReport(BtrxProtocol.HEALTH, backlog = false, fromPairedPod = false)) // Desk Pod's own HEALTH
        assertFalse(BtrxMapStatus.isDronePodReport(BtrxProtocol.PAIR_REQ, backlog = false, fromPairedPod = true))
    }

    @Test
    fun deskPodHealthEvery30sNoLongerFlapsTheChip() {
        // Nov 16: Desk Pod HEALTH every 30 s, no Drone Pod: the chip stays one state.
        val texts = (0..60).map { t ->
            BtrxMapStatus.status(true, ble(LinkPhase.READY), false, false, BtrxMapStatus.dronePodReporting(0L, t * 1000L))!!.text
        }.toSet()
        assertEquals(setOf("BTRX: Active - No Drone Pod reports"), texts)
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
        assertEquals("BTRX: Active", text(LinkStatus(LinkKind.USB, LinkPhase.READY), reporting = true))
        assertEquals("BTRX: Active - No Drone Pod reports", text(LinkStatus(LinkKind.USB, LinkPhase.READY)))
        assertEquals("BTRX: Inactive - Connecting", text(LinkStatus(LinkKind.USB, LinkPhase.CONFIGURING)))
    }
}
