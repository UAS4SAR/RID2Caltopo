package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * Nov 17 watch test: the own-aircraft banner (which source, kept closed once answered, one
 * line) and the 0x010B HEALTH supply fields. Same cases as the iPad's BtrxOwnSuggestionTests.
 */
class BtrxOwnSuggestionTest {
    companion object {
        fun src(key: Long, shortId: String, srcId: Int = 0, flags: Int = 0) = BtrxBearingSource(
            index = 0, key = key, shortId = shortId, provisional = false, devClass = 0, rgb = 0x2196F3, flags = flags,
            firstHeardUs = 0L, lastHeardUs = 5_000_000L, reportsIn = 40, reportsUsed = 30, reportsInWindow = 25, peakAlpha = 0.6f,
            estimate = null, latestRay = null, srcId = srcId,
        )
    }

    private val suspect = BtrxBearingNative.SRC_OWN_SUSPECT

    @Test fun pickSkipsIgnoredAnsweredAndPlain() {
        val plain = src(1L, "Plain1")
        val ignored = src(2L, "Ign222", flags = suspect or BtrxBearingNative.SRC_IGNORED)
        val q = src(3L, "QBXFvg", srcId = 50203, flags = suspect)
        assertEquals(q, BtrxOwnSuggestion.pick(listOf(plain, ignored, q), BtrxOwnAnswers()))
        assertNull(BtrxOwnSuggestion.pick(listOf(plain, ignored, q), BtrxOwnAnswers().plus(3L, 50203)))
        // By src_id: a new address key with the same src_id stays closed; src_id 0 never matches.
        assertNull(BtrxOwnSuggestion.pick(listOf(q.copy(key = 30L)), BtrxOwnAnswers().plus(3L, 50203)))
        val a0 = BtrxOwnAnswers().plus(4L, 0)
        assertTrue(a0.srcIds.isEmpty())
        assertEquals(q.copy(srcId = 0), BtrxOwnSuggestion.pick(listOf(q.copy(srcId = 0)), a0))
        assertNull(BtrxOwnSuggestion.pick(emptyList(), BtrxOwnAnswers()))
    }

    @Test fun bannerIsOneShortLine() {
        val q = src(3L, "QBXFvg", flags = suspect)
        assertEquals("QBXFvg (unknown) on the drone?", BtrxBearingText.ownSuggestion(q))
        assertEquals("QBXFvg on drone?", BtrxBearingText.ownSuggestionCollapsed(q))
        assertEquals("Own aircraft", BtrxBearingText.OWN_SUGGEST_SAVE)
        assertEquals("Not mine", BtrxBearingText.OWN_SUGGEST_KEEP)
        assertFalse(BtrxBearingText.ownSuggestion(q).contains("\n"))
        assertTrue(BtrxBearingText.ownSuggestion(q).length < 40)
    }

    /** A 59-byte 0x010B Desk Pod HEALTH, as from the bench capture: 3301 / 3290 / 4180 / 4012 mV, no battery, vbus+usb_ready, soft-reset. */
    private fun health59(): ByteArray {
        val p = ByteArray(59)
        p[6] = P.ROLE_DESK_POD.toByte()
        fun u16(o: Int, v: Int) { p[o] = (v and 0xFF).toByte(); p[o + 1] = (v shr 8).toByte() }
        u16(44, 0); u16(46, 3301); u16(48, 3290); u16(50, 4180); u16(52, 4012); u16(54, 0)
        p[56] = 0x03; p[57] = 5; p[58] = 2
        return p
    }

    @Test fun health59PowerFieldsByLength() {
        val h = P.parseHealth(health59())
        assertEquals(P.HealthPower(3301, 3290, 4180, 4012, 0, 0x03, 5, 2), h.power)
        assertEquals(0, h.scanLostPerS)
        assertNull(P.parseHealth(health59().copyOf(58)).power)
        assertNull(P.parseHealth(health59().copyOf(46)).power)
        assertNull(P.parseHealth(health59().copyOf(44)).power)
        assertEquals(h.power, P.parseHealth(health59() + ByteArray(4)).power)   // trailing bytes tolerated
        assertEquals("vbus,usb_ready", P.powerFlagNames(0x03))
        assertEquals("vbus,usb_ready,battery,vdd_low,pof,vbus_lost,prev_pof,prev_vbus_lost", P.powerFlagNames(0xFF))
        assertEquals("none", P.powerFlagNames(0))
        assertEquals(listOf("unknown", "power-on", "brown-out", "reset-pin", "watchdog", "soft-reset", "lockup", "wake", "9"),
            (0..7).map { P.bootCauseName(it) } + P.bootCauseName(9))
    }

    @Test fun health59InFrameLogAndDeskPodScreen() {
        val w = P.parseHealth(health59()).power!!
        assertEquals(" vdd_mv=3301 vdd_min_mv=3290 vddh_mv=4180 vddh_min_mv=4012 vbat_mv=0 power_flags=0x03(vbus,usb_ready) " +
            "boot_cause=5(soft-reset) vbus_drops=2", BtrxFrameLog.powerFields(w))
        assertEquals(listOf(
            "Desk Pod supply: VDD 3301 mV (min 3290 mV), VDDH 4180 mV (min 4012 mV), VBAT none seen",
            "Desk Pod USB power: present, lost 2 times while running (flags vbus,usb_ready)",
            "Desk Pod started by: soft-reset",
        ), BtrxDeskPodText.deskSupplyLines(w))
        val bad = w.copy(vddMv = 2950, powerFlags = P.PWR_VBUS_LOST or P.PWR_VDD_LOW or P.PWR_POF or P.PWR_PREV_VBUS_LOST, vbusDrops = 1)
        val lines = BtrxDeskPodText.deskSupplyLines(bad)
        assertEquals("Desk Pod USB power: ABSENT, lost 1 time while running (flags vdd_low,pof,vbus_lost,prev_vbus_lost)", lines[1])
        assertEquals("Desk Pod power warning: 3.3 V rail below 3.0 V; supply sagged (power-fail) since it started; USB power lost in the run before", lines[3])
        assertTrue(BtrxDeskPodText.deskSupplyChange(null, w)!!.startsWith("desk pod power: vdd_mv=3301"))
        assertNull(BtrxDeskPodText.deskSupplyChange(w, w.copy(vddMv = 3299)))          // rails alone: no line
        assertTrue(BtrxDeskPodText.deskSupplyChange(w, w.copy(vbusDrops = 3)) != null)
    }

    // ---- "Ignore all except this" planner ----

    private fun s2(key: Long, id: String, srcId: Int, rssi: Int?, heardUs: Long, reports: Int = 10, flags: Int = 0) =
        src(key, id, srcId, flags).copy(maxRssiDbm = rssi, lastHeardUs = heardUs, reportsIn = reports)

    @Test fun ignoreAllPlanKeepsSkipsAndOrdersByStrength() {
        val now = 100_000_000L
        val keep = s2(1, "Garmin", 50703, -60, now)
        val rotatedKeep = s2(2, "Garmn2", 50703, -58, now)                  // same src_id as the kept one
        val own = s2(3, "QBXFvg", 50203, -30, now, flags = BtrxBearingNative.SRC_IGNORED)
        val suspect = s2(4, "Susp44", 4444, -35, now, flags = BtrxBearingNative.SRC_OWN_SUSPECT)
        val saved = s2(5, "Saved5", 5555, -50, now)
        val weak = s2(6, "Weak66", 6666, -90, now)
        val strong = s2(7, "Strng7", 7777, -55, now)
        val stale = s2(8, "Stale8", 8888, -40, now - 55_000_000L)           // pod no longer holds it
        val noId = s2(9, "NoId99", 0, -45, now)
        val tie = s2(10, "Tie100", 1000, -55, now, reports = 50)             // same RSSI, more reports: first
        val p = BtrxIgnoreAllExcept.plan(listOf(keep, rotatedKeep, own, suspect, saved, weak, strong, stale, noId, tie), now, keep,
            savedSrcIds = listOf(5555), podStored = 1, podSupported = true)
        assertEquals(listOf(8L, 9L, 10L, 7L, 6L), p.localKeys)
        assertEquals(5, p.count)
        assertEquals(listOf(1000, 7777, 6666), p.podSrcIds)
        assertEquals(31, p.podRoom); assertEquals(0, p.overLimit)
        assertEquals("Ignore 5 other sources?", BtrxBearingText.ignoreAllTitle(p.count))
        assertEquals("Ignore 1 other source?", BtrxBearingText.ignoreAllTitle(1))
        assertEquals("Keeps Garmin. 3 strongest also saved on the Drone Pod (room for 31 of 32); the rest on this device only. " +
            "\"Restore ignored\" undoes only these.", BtrxBearingText.ignoreAllDetail(p, true))
        // Little room on the pod: strongest first, the rest counted.
        val tight = BtrxIgnoreAllExcept.plan(listOf(keep, weak, strong, tie), now, keep, emptyList(), podStored = 31, podSupported = true)
        assertEquals(listOf(1000), tight.podSrcIds); assertEquals(2, tight.overLimit)
        assertTrue(BtrxBearingText.ignoreAllDetail(tight, true).contains("; 2 more only on this device"))
        // Older firmware: this device only.
        val old = BtrxIgnoreAllExcept.plan(listOf(keep, weak), now, keep, emptyList(), null, podSupported = false)
        assertTrue(old.podSrcIds.isEmpty()); assertEquals(listOf(6L), old.localKeys)
        assertTrue(BtrxBearingText.ignoreAllDetail(old, false).contains("this device only"))
        assertEquals(0, BtrxIgnoreAllExcept.plan(listOf(keep), now, keep, emptyList(), null, true).count)
        assertEquals("Restore ignored (5)", BtrxBearingText.restoreLabel(5))
    }
}
