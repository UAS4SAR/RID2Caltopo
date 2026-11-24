package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxSteering.Hint
import org.ncssar.rid2caltopo.btrx.BtrxSteering.Vector
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/** Hint derivation per btrx design doc section 41.2 (same cases as the iPad's BtrxSteeringTests). */
class BtrxSteeringTest {
    private var now = 1_000_000L
    /** The pod's clock (window_end_ms), advancing with [now]. */
    private var podMs = 50_000L
    private var seq = 0
    private val st = BtrxSteering()

    private fun steer(
        port: Int, stbd: Int, peak: Int = maxOf(port, stbd), src: Int = 7, gated: Boolean = false,
        imu: Boolean = false, rollX2: Int = 0, target: Boolean = true, devClass: Int = P.DEV_PHONE, muted: Boolean = false,
        hitsPort: Int = 3, hitsStbd: Int = 3,
    ): Hint? {
        var f = 0
        if (target) f = f or P.STEER_F_TARGET
        if (gated) f = f or P.STEER_F_GATED
        if (imu) f = f or P.STEER_F_IMU_OK
        if (muted) f = f or P.STEER_F_MUTED
        val s = P.Steer(++seq and 0xFF, f, podMs, 100, if (target) src else 0, if (target) port else -128, if (target) stbd else -128,
            if (target) peak else -128, if (target) hitsPort else 0, if (target) hitsStbd else 0, 0x4C, devClass, 1,
            byteArrayOf(1, 2, 3, 4, 5, 6), rollX2, 0)
        st.onSteer(s, now)
        return st.view(now)?.hint
    }

    private fun tick(ms: Long = 1_000) { now += ms; podMs += ms }
    private fun hint() = st.view(now)?.hint
    private fun vector() = st.view(now)?.vector

    @Test
    fun steerHintMatchesTheSimulatorOnTheFirmwareVector() {
        // test_protocol.py STEER_PAYLOAD: port -61, stbd -70 -> r2c_sim steer_hint "left".
        val s = P.parseSteer(P.fromHex("0703040302014101efbec3bac603044c000102aa0000000055fa04"))
        st.onSteer(s, now)
        assertEquals(Hint.LEFT, hint())
        assertEquals(Vector.HARD_LEFT, vector()) // 9 dB >= 8: hard left
    }

    @Test
    fun enterAtThreeLeaveOnlyBelowTwo() {
        assertEquals(Hint.STRAIGHT, steer(-60, -62))     // 2 dB: straight
        tick(); steer(-60, -63); tick(); steer(-60, -63)  // 3 dB twice: left
        assertEquals(Hint.LEFT, hint())
        tick(); assertEquals(Hint.LEFT, steer(-60, -62)) // 2 dB: dead band holds LEFT
        tick(); assertEquals(Hint.LEFT, steer(-60, -61)) // 1 dB once: not yet
        tick(); assertEquals(Hint.STRAIGHT, steer(-60, -61)) // twice: straight
    }

    @Test
    fun aSingleReportDoesNotFlipButTwoInARowDo() {
        steer(-60, -64); tick(); steer(-60, -64)
        assertEquals(Hint.LEFT, hint())
        tick(); assertEquals(Hint.LEFT, steer(-64, -60))  // one RIGHT report: no flicker
        tick(); assertEquals(Hint.LEFT, steer(-60, -64))  // back left: the pending flip is dropped
        tick(); assertEquals(Hint.LEFT, steer(-64, -60))
        tick(); assertEquals(Hint.RIGHT, steer(-64, -60)) // two in a row: RIGHT
    }

    @Test
    fun aStrongDifferenceFlipsAtOnce() {
        steer(-60, -64)
        assertEquals(Hint.LEFT, hint())
        tick(); assertEquals(Hint.RIGHT, steer(-66, -60)) // |delta| 6
        assertEquals(Vector.RIGHT, vector())
        tick(); assertEquals(Hint.RIGHT, steer(-70, -60))
        assertEquals(Vector.HARD_RIGHT, vector())        // |delta| 10 >= 8
    }

    @Test
    fun heardOnOnePatchWithTwoHitsIsThatSideAtOnceAndHard() {
        assertEquals(Hint.RIGHT, steer(P.RSSI_NOT_HEARD, -80, imu = true, hitsPort = 0, hitsStbd = 2))
        assertEquals(Vector.HARD_RIGHT, vector())
        tick(); assertEquals(Hint.LEFT, steer(-80, P.RSSI_NOT_HEARD, hitsPort = 2, hitsStbd = 0))
        assertEquals(Vector.HARD_LEFT, vector())
    }

    @Test
    fun portBoardSilentIsNoDirectionNotAHardRight() {
        // A5 bench 10:45:26: port -128, hits 0/7, IMU-ok clear (pod B off the link), stbd -24.
        assertEquals(Hint.NO_PORT, steer(P.RSSI_NOT_HEARD, -24, hitsPort = 0, hitsStbd = 7))
        val v = st.view(now)!!
        assertTrue(v.gray)
        assertEquals(Vector.AHEAD, v.vector) // nothing learnt: no side
        // Pod B back with both patches: the hint works from there.
        tick(); assertEquals(Hint.LEFT, steer(-60, -66, imu = true))
        // Pod B drops out again: the LEFT is held (gray), not flipped to RIGHT.
        repeat(3) { tick(); assertEquals(Hint.NO_PORT, steer(P.RSSI_NOT_HEARD, -24, hitsPort = 0, hitsStbd = 7)) }
        assertEquals(Vector.LEFT, vector())
        tick(); assertEquals(Hint.LEFT, steer(-60, -66, imu = true))
    }

    @Test
    fun heardOnOnePatchWithOneHitNeedsConfirming() {
        steer(-60, -64)
        assertEquals(Hint.LEFT, hint())
        tick(); assertEquals(Hint.LEFT, steer(P.RSSI_NOT_HEARD, -80, imu = true, hitsPort = 0, hitsStbd = 1)) // weak: not yet
        tick(); assertEquals(Hint.RIGHT, steer(P.RSSI_NOT_HEARD, -80, imu = true, hitsPort = 0, hitsStbd = 1)) // confirmed
        assertEquals(Vector.RIGHT, vector()) // not hard on 1 hit
        tick(); steer(P.RSSI_NOT_HEARD, -80, imu = true, hitsPort = 0, hitsStbd = 2)
        assertEquals(Vector.HARD_RIGHT, vector())
    }

    @Test
    fun rollCorrectionIsOffByDefault() {
        assertFalse(BtrxSteering.ROLL_CORRECTION_DEFAULT)
        assertFalse(st.rollCorrection)
        steer(-60, -62, imu = true, rollX2 = 20); tick()
        assertEquals(Hint.STRAIGHT, steer(-60, -62, imu = true, rollX2 = 20)) // 2 dB raw, not corrected
    }

    @Test
    fun rollIsCorrectedWhenEnabledAndOnlyWithTheImuFlag() {
        st.rollCorrection = true
        // Raw delta 2 dB (straight); starboard down 10 deg adds 2.0 dB -> 4 dB, LEFT.
        assertEquals(Hint.STRAIGHT, steer(-60, -62, rollX2 = 20))
        tick(); steer(-60, -62, imu = true, rollX2 = 20); tick()
        assertEquals(Hint.LEFT, steer(-60, -62, imu = true, rollX2 = 20))
        val s = { roll: Int -> P.Steer(0, P.STEER_F_TARGET or P.STEER_F_IMU_OK, 0, 0, 1, -60, -62, -60, 1, 1, 0, 0, 0, ByteArray(6), roll, 0) }
        assertEquals(4.0, BtrxSteering.deltaDb(s(20), rollCorrection = true), 1e-9)
        assertEquals(0.0, BtrxSteering.deltaDb(s(-20), rollCorrection = true), 1e-9)
        assertEquals(2.0, BtrxSteering.deltaDb(s(20), rollCorrection = false), 1e-9)
        assertEquals(0.2, BtrxSteering.ROLL_DB_PER_DEG, 0.0)
    }

    /** Six reports 1 s apart while STRAIGHT, peak rising by [step] dB per report. */
    private fun ramp(start: Int, step: Int, n: Int = 6): Hint? {
        var h: Hint? = null
        for (i in 0 until n) {
            val p = start + i * step
            h = steer(-60, -60, peak = p)
            if (i < n - 1) tick()
        }
        return h
    }

    @Test
    fun closerNeedsSixReportsAndASignificantSlopeAgainstPodTime() {
        steer(-60, -60, peak = -75); tick()
        steer(-60, -60, peak = -74); tick()
        steer(-60, -60, peak = -73); tick()
        steer(-60, -60, peak = -72); tick()
        assertEquals(Hint.STRAIGHT, steer(-60, -60, peak = -71)) // 5 reports: not yet
        tick(); assertEquals(Hint.CLOSER, steer(-60, -60, peak = -70)) // 6 at 1 dB/s
        assertEquals(Vector.AHEAD, vector())
    }

    @Test
    fun fartherPointsBack() {
        assertEquals(Hint.FARTHER, ramp(-60, -1))
        assertEquals(Vector.BEHIND, vector())
    }

    @Test
    fun theSlopeIsPerSecondOfPodTimeNotPerReport() {
        // 1 dB per report but reports 3 s apart: 0.33 dB/s, under 0.5: no call.
        for (i in 0 until 6) {
            steer(-60, -60, peak = -75 + i)
            if (i < 5) { now += 1_000; podMs += 3_000 }
        }
        assertEquals(Hint.STRAIGHT, hint())
    }

    @Test
    fun aNoisySlopeIsNotCalled() {
        // Slope about +0.9 dB/s but scattered by several dB: under 2 standard errors.
        val peaks = listOf(-72, -62, -72, -62, -70, -63)
        peaks.forEachIndexed { i, p -> steer(-60, -60, peak = p); if (i < peaks.size - 1) tick() }
        val fit = BtrxSteering.fit(peaks.mapIndexed { i, p -> (i * 1000L) to p })!!
        assertTrue(fit.slopeDbPerS >= 0.5)
        assertTrue(fit.slopeDbPerS < 2 * fit.stdErr)
        assertEquals(Hint.STRAIGHT, hint())
    }

    @Test
    fun closerClearsBelowAQuarterDbPerSecond() {
        assertEquals(Hint.CLOSER, ramp(-75, 1))
        // Flat reports: the 10 s window slides and the slope falls under 0.25 dB/s.
        var h: Hint? = Hint.CLOSER
        var n = 0
        while (h == Hint.CLOSER && n < 15) { tick(); h = steer(-60, -60, peak = -70); n++ }
        assertEquals(Hint.STRAIGHT, h)
        val fit = BtrxSteering.fit(listOf(0L to -70, 1000L to -70, 2000L to -70))!!
        assertEquals(0.0, fit.slopeDbPerS, 1e-9)
    }

    @Test
    fun onlyTheLastTenSecondsOfPodTimeCount() {
        assertEquals(Hint.CLOSER, ramp(-75, 1))
        tick(11_000) // gap: older reports fall out of the window
        assertEquals(Hint.STRAIGHT, steer(-60, -60, peak = -70)) // the target was lost meanwhile: fresh
    }

    @Test
    fun gatedAndEmptyReportsAreLeftOutOfTheFit() {
        steer(-60, -60, peak = -75); tick()
        steer(-60, -60, peak = -74); tick()
        steer(-60, -60, peak = -20, gated = true); tick() // would wreck the fit
        steer(0, 0, target = false); tick()
        steer(-60, -60, peak = -73); tick()
        steer(-60, -60, peak = -72); tick()
        steer(-60, -60, peak = -71); tick()
        assertEquals(Hint.CLOSER, steer(-60, -60, peak = -70))
    }

    @Test
    fun fitGivesSlopeInDbPerSecondAndStandardError() {
        val exact = BtrxSteering.fit(listOf(0L to -70, 1000L to -69, 2000L to -68, 3000L to -67))!!
        assertEquals(1.0, exact.slopeDbPerS, 1e-9)
        assertEquals(0.0, exact.stdErr, 1e-9)
        val half = BtrxSteering.fit(listOf(0L to -60, 2000L to -61, 4000L to -62))!!
        assertEquals(-0.5, half.slopeDbPerS, 1e-9)
        assertNull(BtrxSteering.fit(listOf(0L to -60, 1000L to -60)))
    }

    @Test
    fun gatedShowsLevelInGrayAndHoldsTheLastHint() {
        steer(-60, -66)
        assertEquals(Hint.LEFT, hint())
        tick(); assertEquals(Hint.LEVEL, steer(-66, -60, gated = true))
        val v = st.view(now)!!
        assertTrue(v.gray)
        assertEquals(Vector.LEFT, v.vector) // the held vector, drawn gray
        tick(); assertEquals(Hint.LEFT, steer(-60, -64)) // ungated: the held LEFT, not re-derived from the gated report
    }

    @Test
    fun noReportsForTwoSecondsFadesButKeepsTheHint() {
        steer(-60, -66)
        assertFalse(st.view(now)!!.faded)
        now += 1_999
        assertFalse(st.view(now)!!.faded)
        now += 1
        val v = st.view(now)!!
        assertTrue(v.faded)
        assertEquals(Hint.LEFT, v.hint)
        assertNotNull(v.vector)
        now += 3_000
        assertEquals(Hint.LOST, hint()) // 5 s without a target
    }

    @Test
    fun lostAfterFiveSecondsWithoutATarget() {
        steer(-60, -66)
        tick(); assertEquals(Hint.LEFT, steer(0, 0, target = false)) // bit0 clear: held, faded
        assertTrue(st.view(now)!!.faded)
        tick(3_999); assertEquals(Hint.LEFT, hint())
        tick(1); assertEquals(Hint.LOST, hint())
        val v = st.view(now)!!
        assertNull(v.vector)
        assertTrue(v.faded)
        assertFalse(v.canMute)
        tick(BtrxSteering.SHOW_MS - 4_000 + 1)
        assertNull(st.view(now)) // no STEER at all for 12 s: overlay gone
        assertEquals(Hint.LOST, st.lastView(now)!!.hint) // the Desk Pod screen still has the last one
    }

    @Test
    fun aNewSourceStartsFresh() {
        steer(-60, -66, src = 7)
        assertEquals(Hint.LEFT, hint())
        tick(); assertEquals(Hint.STRAIGHT, steer(-62, -61, src = 9))
        assertEquals(9, st.view(now)!!.srcId)
        tick(); assertEquals(Hint.STRAIGHT, steer(-60, -63, src = 9)) // needs confirming again
    }

    @Test
    fun podRestartResetsColorsHintAndMutedFlag() {
        steer(-60, -66, src = 1, muted = true)
        tick(); steer(-60, -66, src = 2, muted = true)
        assertEquals(1, st.view(now)!!.colorIndex)
        assertTrue(st.view(now)!!.muted)
        tick()
        podMs = 3_000 // pod rebooted: its clock went back
        val s = P.Steer(1, P.STEER_F_TARGET, podMs, 0, 2, -62, -61, -61, 3, 3, 0, P.DEV_PHONE, 1, ByteArray(6), 0, 0)
        assertTrue(st.onSteer(s, now))
        val v = st.view(now)!!
        assertEquals(0, v.colorIndex) // src 2 is a new source: first color again
        assertEquals(Hint.STRAIGHT, v.hint)
        assertFalse(v.muted)
        assertEquals(1, st.restarts)
        tick(); assertFalse(st.onSteer(P.Steer(2, P.STEER_F_TARGET, podMs, 0, 2, -62, -61, -61, 3, 3, 0, 1, 1, ByteArray(6), 0, 0), now))
    }

    @Test
    fun colorsAreStableAndWrapAfterEight() {
        val ids = listOf(5, 9, 11, 12, 40, 41, 42, 43, 44)
        val first = ids.map { st.colorFor(it) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 0), first)
        assertEquals(first, ids.map { st.colorFor(it) })
        assertNull(st.colorFor(0))
        assertEquals(8, BtrxSteering.PALETTE.size)
        assertFalse(BtrxSteering.PALETTE.contains(BtrxSteering.GRAY))
        st.reset()
        assertEquals(0, st.colorFor(44))
    }

    @Test
    fun theViewCarriesKindMutedAndColor() {
        steer(-60, -60, src = 3, devClass = P.DEV_TRACKER, muted = true)
        val v = st.view(now)!!
        assertEquals(P.DEV_TRACKER, v.devClass)
        assertTrue(v.muted)
        assertEquals(0, v.colorIndex)
        assertTrue(v.canMute)
    }

    // ---- which tile (the drone carrying the pod) ----

    @Test
    /**
     * Ken, Nov 23: no "BTRX hidden" chip; the card shows only while the stream is primary, and the
     * "Drone Pod is on this drone" pick (a setting) shows whichever window is primary.
     */
    fun cardFollowsThePrimaryWindowAndThereIsNoHide() {
        fun mode(tile: String, live: Set<String>, chosen: String?, hasView: Boolean, onStream: Boolean) =
            BtrxSteeringTarget.tileOverlay(tile, live, chosen, hasView, onStream)
        assertEquals(BtrxSteeringTarget.TileOverlay.CARD, mode("A", setOf("A"), null, true, onStream = true))
        assertEquals(BtrxSteeringTarget.TileOverlay.NONE, mode("A", setOf("A"), null, true, onStream = false))
        // No live steering (not paired, link down, STEER stopped): nothing.
        assertEquals(BtrxSteeringTarget.TileOverlay.NONE, mode("A", setOf("A"), null, false, onStream = true))
        // Several live, none chosen: the pick, on either primary window.
        assertEquals(BtrxSteeringTarget.TileOverlay.PICK, mode("A", setOf("A", "B"), null, true, onStream = true))
        assertEquals(BtrxSteeringTarget.TileOverlay.PICK, mode("A", setOf("A", "B"), null, true, onStream = false))
        assertEquals(BtrxSteeringTarget.TileOverlay.CARD, mode("A", setOf("A", "B"), "A", true, onStream = true))
        // The other drone's tile shows nothing either way.
        assertEquals(BtrxSteeringTarget.TileOverlay.NONE, mode("B", setOf("A", "B"), "A", true, onStream = true))
        assertEquals(BtrxSteeringTarget.TileOverlay.NONE, mode("B", setOf("A", "B"), "A", true, onStream = false))
    }

    @Test
    fun oneLiveStreamShowsTheOverlayWithoutAPick() {
        val live = setOf("A")
        assertTrue(BtrxSteeringTarget.showsOverlay("A", live, null))
        assertFalse(BtrxSteeringTarget.offersPick("A", live, null))
        assertFalse(BtrxSteeringTarget.showsOverlay("B", live, null)) // not live
    }

    @Test
    fun severalLiveStreamsNeedAPick() {
        val live = setOf("A", "B")
        assertFalse(BtrxSteeringTarget.showsOverlay("A", live, null))
        assertTrue(BtrxSteeringTarget.offersPick("A", live, null))
        assertTrue(BtrxSteeringTarget.offersPick("B", live, null))
        assertTrue(BtrxSteeringTarget.showsOverlay("B", live, "B"))
        assertFalse(BtrxSteeringTarget.showsOverlay("A", live, "B"))
        assertFalse(BtrxSteeringTarget.offersPick("A", live, "B"))
    }

    @Test
    fun theChoiceEndsWithItsStream() {
        assertEquals("B", BtrxSteeringTarget.retained("B", setOf("A", "B")))
        assertNull(BtrxSteeringTarget.retained("B", setOf("A")))
        assertNull(BtrxSteeringTarget.retained(null, setOf("A")))
        // B ended: A alone is automatic again.
        assertTrue(BtrxSteeringTarget.showsOverlay("A", setOf("A"), BtrxSteeringTarget.retained("B", setOf("A"))))
    }

    // --- Arrow (Ken, Nov 12 2026): direction and length instead of words. ---

    private fun len() = st.view(now)!!.length

    @Test
    fun arrowPointsUpForAheadSidewaysForATurnAndFlatForAHardTurn() {
        val near = 0.0
        val ahead = BtrxSteering.arrowGeometry(Vector.AHEAD, near).tip
        assertEquals(0.0, ahead.x, 1e-9); assertTrue(ahead.y < 0) // straight up
        val left = BtrxSteering.arrowGeometry(Vector.LEFT, near).tip
        assertTrue(left.x < 0 && left.y < 0); assertEquals(-left.x, -left.y, 1e-9) // up and left, 45 deg
        val right = BtrxSteering.arrowGeometry(Vector.RIGHT, near).tip
        assertTrue(right.x > 0 && right.y < 0); assertEquals(right.x, -right.y, 1e-9)
        val hardLeft = BtrxSteering.arrowGeometry(Vector.HARD_LEFT, near).tip
        assertTrue(hardLeft.x < 0); assertEquals(0.0, hardLeft.y, 1e-9) // horizontal, left
        val hardRight = BtrxSteering.arrowGeometry(Vector.HARD_RIGHT, near).tip
        assertTrue(hardRight.x > 0); assertEquals(0.0, hardRight.y, 1e-9)
    }

    @Test
    fun behindIsATurnAroundArrowWhoseHeadPointsDown() {
        val a = BtrxSteering.arrowGeometry(Vector.BEHIND, 0.5)
        assertTrue(a.shaft[1].y < a.shaft[0].y)           // leaves the circle going up
        assertTrue(a.shaft.minOf { it.y } < -BtrxSteering.MARK_CIRCLE_R)
        assertTrue(a.tip.y > a.head[1].y)                 // head points down: turn around
        assertTrue(a.tip.x > 0)
        // Everything stays inside the mark.
        for (l in listOf(0.0, 1.0)) for (v in Vector.values()) {
            val g = BtrxSteering.arrowGeometry(v, l, closer = true)
            for (p in g.shaft + g.head + g.chevron) assertTrue("$v $l $p", kotlin.math.abs(p.x) <= 1.0 && kotlin.math.abs(p.y) <= 1.0)
        }
    }

    @Test
    fun arrowIsLongerWhenTheSourceIsFarther() {
        assertEquals(BtrxSteering.ARROW_MIN_REACH, BtrxSteering.arrowReach(0.0), 1e-9)
        assertEquals(BtrxSteering.ARROW_MAX_REACH, BtrxSteering.arrowReach(1.0), 1e-9)
        for (v in Vector.values().filter { it != Vector.BEHIND }) {
            val near = BtrxSteering.arrowGeometry(v, 0.0).tip
            val far = BtrxSteering.arrowGeometry(v, 1.0).tip
            assertTrue(far.x * far.x + far.y * far.y > near.x * near.x + near.y * near.y)
        }
        val uNear = BtrxSteering.arrowGeometry(Vector.BEHIND, 0.0)
        val uFar = BtrxSteering.arrowGeometry(Vector.BEHIND, 1.0)
        assertTrue(uFar.shaft.minOf { it.y } < uNear.shaft.minOf { it.y })
    }

    @Test
    fun lengthIsLinearInTheSumSignalBetweenMinus50AndMinus100() {
        assertEquals(0.0, BtrxSteering.lengthFor(-40.0), 1e-9)  // stronger than -50: shortest
        assertEquals(0.0, BtrxSteering.lengthFor(-50.0), 1e-9)
        assertEquals(0.5, BtrxSteering.lengthFor(-75.0), 1e-9)
        assertEquals(1.0, BtrxSteering.lengthFor(-100.0), 1e-9)
        assertEquals(1.0, BtrxSteering.lengthFor(-115.0), 1e-9) // weaker: longest
    }

    @Test
    fun sumSignalIsTheStrongerPatchMean() {
        steer(-70, -80, peak = -60)
        assertEquals(BtrxSteering.lengthFor(-70.0), len(), 1e-9) // first report: no smoothing
        val s = P.Steer(1, P.STEER_F_TARGET, 1L, 0, 9, -128, -90, -85, 0, 2, 0, 0, 1, ByteArray(6), 0, 0)
        assertEquals(-90.0, BtrxSteering.sumDbm(s)!!, 1e-9) // one patch: that mean
        val peakOnly = P.Steer(1, P.STEER_F_TARGET, 1L, 0, 9, -128, -128, -85, 0, 0, 0, 0, 1, ByteArray(6), 0, 0)
        assertEquals(-85.0, BtrxSteering.sumDbm(peakOnly)!!, 1e-9)
    }

    @Test
    fun lengthIsSmoothedAndSlewLimited() {
        steer(-95, -95)                         // far: 0.9
        assertEquals(0.9, len(), 1e-9)
        tick(); steer(-50, -50)                 // jumps to near: EMA would move 0.27, slew allows 0.1/s
        assertEquals(0.8, len(), 1e-9)
        tick(); steer(-50, -50)
        assertEquals(0.7, len(), 1e-9)
        assertEquals(0.73, BtrxSteering.smoothLength(0.7, 0.8, 1.0), 1e-9) // small change: plain EMA 0.3
        assertEquals(0.5, BtrxSteering.smoothLength(null, 0.5, 1.0), 1e-9)
    }

    @Test
    fun lengthFreezesWhileGatedOrFadedAndRestartsWithANewSource() {
        steer(-60, -60)
        val l = len()
        tick(); steer(-95, -95, gated = true)
        assertEquals(l, len(), 1e-9)            // LEVEL: held
        tick(); steer(-95, -95, target = false)
        assertEquals(l, len(), 1e-9)            // no target: held, faded
        tick(); steer(-95, -95, src = 8)        // a new source starts afresh, unsmoothed
        assertEquals(0.9, len(), 1e-9)
    }

    @Test
    fun closerShowsTheChevronAndFartherTheTurnAround() {
        assertEquals(Hint.CLOSER, ramp(-75, 1))
        val v = st.view(now)!!
        assertTrue(v.closer)
        assertTrue(BtrxSteering.arrowGeometry(v.vector!!, v.length, v.closer).chevron.size == 3)
        assertTrue(BtrxSteering.arrowGeometry(Vector.AHEAD, 0.5, closer = false).chevron.isEmpty())
        st.reset()
        tick(); assertEquals(Hint.FARTHER, ramp(-60, -1))
        assertFalse(st.view(now)!!.closer)
        assertEquals(Vector.BEHIND, vector())
    }

    @Test
    fun portStrongerPointsTheArrowLeft() {
        // Bench role check: the Desk Pod's BLE right in front of the port patch.
        steer(-45, -72, hitsPort = 8, hitsStbd = 3)
        assertEquals(Hint.LEFT, hint())
        val v = st.view(now)!!
        val tip = BtrxSteering.arrowGeometry(v.vector!!, v.length).tip
        assertTrue(tip.x < 0)                   // the arrow points left
        assertEquals(Vector.HARD_LEFT, v.vector)
        assertEquals(0.0, v.length, 1e-9)       // -45 dBm: near, shortest
    }
}
