package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxSteering.Hint
import org.ncssar.rid2caltopo.btrx.BtrxSteering.Vector
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/** Hint derivation per btrx design doc section 41.2 (same cases as the iPad's BtrxSteeringTests). */
class BtrxSteeringTest {
    private var now = 1_000_000L
    private var seq = 0
    private val st = BtrxSteering()

    private fun steer(
        port: Int, stbd: Int, peak: Int = maxOf(port, stbd), src: Int = 7, gated: Boolean = false,
        imu: Boolean = false, rollX2: Int = 0, target: Boolean = true, devClass: Int = P.DEV_PHONE, muted: Boolean = false,
    ): Hint? {
        var f = 0
        if (target) f = f or P.STEER_F_TARGET
        if (gated) f = f or P.STEER_F_GATED
        if (imu) f = f or P.STEER_F_IMU_OK
        if (muted) f = f or P.STEER_F_MUTED
        val s = P.Steer(++seq and 0xFF, f, 0, 100, if (target) src else 0, if (target) port else -128, if (target) stbd else -128,
            if (target) peak else -128, 3, 3, 0x4C, devClass, 1, byteArrayOf(1, 2, 3, 4, 5, 6), rollX2, 0)
        st.onSteer(s, now)
        return st.view(now)?.hint
    }

    private fun tick(ms: Long = 1_000) { now += ms }
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
    fun heardOnOnePatchOnlyIsThatSide() {
        assertEquals(Hint.RIGHT, steer(P.RSSI_NOT_HEARD, -80))
        assertEquals(Vector.HARD_RIGHT, vector())
        tick(); assertEquals(Hint.LEFT, steer(-80, P.RSSI_NOT_HEARD))
    }

    @Test
    fun rollIsCorrectedOnlyWithTheImuFlag() {
        // Raw delta 2 dB (straight); starboard down 10 deg adds 1.2 dB -> 3.2 dB, LEFT.
        assertEquals(Hint.STRAIGHT, steer(-60, -62, rollX2 = 20))
        tick(); steer(-60, -62, imu = true, rollX2 = 20); tick()
        assertEquals(Hint.LEFT, steer(-60, -62, imu = true, rollX2 = 20))
        assertEquals(3.2, BtrxSteering.deltaDb(P.Steer(0, P.STEER_F_TARGET or P.STEER_F_IMU_OK, 0, 0, 1, -60, -62, -60, 1, 1, 0, 0, 0, ByteArray(6), 20, 0)), 1e-9)
        assertEquals(0.8, BtrxSteering.deltaDb(P.Steer(0, P.STEER_F_TARGET or P.STEER_F_IMU_OK, 0, 0, 1, -60, -62, -60, 1, 1, 0, 0, 0, ByteArray(6), -20, 0)), 1e-9)
    }

    @Test
    fun closerAndFartherComeFromThePeakSlopeWhileStraight() {
        steer(-70, -70, peak = -70); tick()
        steer(-69, -69, peak = -69); tick()
        assertEquals(Hint.CLOSER, steer(-68, -68, peak = -68))
        assertEquals(Vector.AHEAD, vector())
        tick(); assertEquals(Hint.CLOSER, steer(-68, -68, peak = -68)) // slope 0.7 still
        tick(); steer(-68, -68, peak = -68)
        tick(); steer(-68, -68, peak = -68)
        tick(); assertEquals(Hint.STRAIGHT, steer(-68, -68, peak = -68)) // flat: back to STRAIGHT
        tick(); steer(-69, -69, peak = -69); tick(); steer(-70, -70, peak = -70); tick()
        assertEquals(Hint.FARTHER, steer(-71, -71, peak = -71))
        assertEquals(Vector.BEHIND, vector())
    }

    @Test
    fun slopeIsLeastSquares() {
        assertEquals(1.0, BtrxSteering.slope(listOf(-70, -69, -68)), 1e-9)
        assertEquals(-0.5, BtrxSteering.slope(listOf(-60, -60, -61, -61, -62)), 1e-9)
        assertEquals(0.0, BtrxSteering.slope(listOf(-60)), 1e-9)
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
}
