package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkKind
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkStatus
import java.io.File

/** Session rules for the map chip (Ken, Nov 18). Same fixture as the iPad's BtrxMapChipSessionTests. */
class BtrxMapChipSessionTest {
    private fun ble(phase: LinkPhase, failed: Int = 0) = LinkStatus(LinkKind.BLUETOOTH, phase, failedAttempts = failed)
    private fun chip(link: LinkStatus, readyEarlier: Boolean = false, requestedAtMs: Long? = null, nowMs: Long = 0,
                     bluetoothOff: Boolean = false, enabled: Boolean = true, paired: Boolean = true) =
        BtrxMapStatus.status(paired, link, bluetoothOff, false, false, linkEnabled = enabled,
            readyEarlier = readyEarlier, requestedAtMs = requestedAtMs, nowMs = nowMs)?.text

    @Test
    fun sharedFixtureMatchesIos() {
        val fixture = sequenceOf(File("test-fixtures/btrx/map-chip-visibility.tsv"), File("../test-fixtures/btrx/map-chip-visibility.tsv")).first { it.isFile }
        val rows = fixture.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(12, rows.size)
        for (r in rows) {
            val got = BtrxMapStatus.visibility(r[0].toBoolean(), r[1].toBoolean(), r[2].toBoolean(), r[3].toBoolean(),
                r[4].takeIf { it != "-" }?.toLong(), r[5].toLong())
            assertEquals(r[7], r[6], got)
        }
        assertEquals(120_000L, BtrxMapStatus.CONNECTING_WINDOW_MS)
    }

    @Test
    fun noBtDownBeforeReadyThisSession() {
        // A5 Nov 18: saved Meshtastic_42ca powered off, background connect waiting.
        assertNull(chip(ble(LinkPhase.RETRY_WAIT, 1), nowMs = 600_000))
        assertNull(chip(ble(LinkPhase.CONNECTING, 4), nowMs = 600_000))
        assertNull(chip(ble(LinkPhase.SCANNING, 2), nowMs = 600_000))
        assertEquals("BTRX: Inactive - BT Down", chip(ble(LinkPhase.RETRY_WAIT, 1), readyEarlier = true))
    }

    @Test
    fun userActionShowsConnectingForTwoMinutesThenHides() {
        assertEquals("BTRX: Inactive - Connecting", chip(ble(LinkPhase.RETRY_WAIT, 1), requestedAtMs = 10_000, nowMs = 20_000))
        assertEquals("BTRX: Inactive - Connecting", chip(ble(LinkPhase.SCANNING), requestedAtMs = 10_000, nowMs = 129_999))
        assertNull(chip(ble(LinkPhase.SCANNING), requestedAtMs = 10_000, nowMs = 130_000))
        // Reasons the operator can act on still show inside the window.
        assertEquals("BTRX: Inactive - Bluetooth off", chip(ble(LinkPhase.UNAVAILABLE), requestedAtMs = 0, nowMs = 1, bluetoothOff = true))
        assertEquals("BTRX: Inactive - No permission", chip(ble(LinkPhase.NO_PERMISSION), requestedAtMs = 0, nowMs = 1))
        assertEquals("BTRX: Inactive - Bluetooth unavailable", chip(ble(LinkPhase.UNAVAILABLE), requestedAtMs = 0, nowMs = 1))
    }

    @Test
    fun linkOffAndNeverPairedNeverShow() {
        assertNull(chip(LinkStatus(LinkKind.NONE, LinkPhase.OFF), readyEarlier = true, requestedAtMs = 0, nowMs = 1))
        assertNull(chip(ble(LinkPhase.RETRY_WAIT, 1), readyEarlier = true, enabled = false))
        assertNull(chip(ble(LinkPhase.SCANNING), requestedAtMs = 0, nowMs = 1, paired = false))
        assertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled = true, deskPodRemembered = false, usbLink = false, pausedForUsb = false))
        assertEquals("BTRX: Active - No Drone Pod reports", chip(ble(LinkPhase.READY)))
    }

    @Test
    fun sessionRemembersReadyUntilForgotten() {
        val s = BtrxMapStatus.Session()
        assertFalse(s.readyEarlier)
        s.observe(false); assertFalse(s.readyEarlier)
        s.observe(true); s.observe(false); assertTrue(s.readyEarlier)
        s.request(5); assertEquals(5L, s.requestedAtMs)
        s.forget(); assertFalse(s.readyEarlier); assertNull(s.requestedAtMs)
    }
}
