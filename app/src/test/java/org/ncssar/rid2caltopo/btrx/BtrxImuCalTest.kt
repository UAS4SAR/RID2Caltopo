package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxImuCal.Step
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/** The IMU calibration flow (design doc section 29.3). Same cases as the iPad's BtrxImuCalTests. */
class BtrxImuCalTest {
    private var now = 50_000L
    private val cal = BtrxImuCal()
    private val v get() = cal.view(now)

    private fun ack(r: BtrxImuCal.Request, status: Int = P.IMUCAL_OK, flags: Int = P.IMUCAL_F_IMU_OK, roll: Int = 0, pitch: Int = 0, tilt: Int = 6, face: Int = 0, y: Int = 0) =
        P.ImuCalAck(r.op, r.tag, status, flags, roll, pitch, tilt, face, y, listOf(0, 0, 0))

    /** Level OK, Start starboard step, countdown; returns the starboard request. */
    private fun throughCountdown(): BtrxImuCal.Request {
        val level = cal.start(now)!!
        assertTrue(cal.onAck(ack(level, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_LEVEL_HELD)))
        assertTrue(cal.startStarboard(now))
        now += BtrxImuCal.STBD_COUNTDOWN_MS
        return cal.tick(now)!!
    }

    @Test
    fun happyPathLevelThenStarboardIsCalibrated() {
        assertTrue(v.canStart); assertFalse(v.canStartStarboard); assertTrue(v.canCheckOrClear)
        assertNull(v.calibrated)
        val level = cal.start(now)!!
        assertEquals(P.IMUCAL_OP_LEVEL, level.op)
        assertEquals(1, level.tag)
        assertEquals(Step.LEVEL, v.step)
        assertTrue(v.message.startsWith("Hold the pod level and still"))
        assertTrue(v.waiting); assertTrue(v.canCancel); assertFalse(v.canStart); assertFalse(v.canCheckOrClear)
        assertNull(cal.start(now)) // one request at a time
        assertTrue(cal.onAck(ack(level, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_LEVEL_HELD, tilt = 7)))
        assertEquals(Step.STBD_READY, v.step)
        assertTrue(v.message, v.message.startsWith("Level step done (board 3.5° from flat in the lid)."))
        assertTrue(v.canStartStarboard)
        assertTrue(cal.startStarboard(now))
        assertEquals(Step.STBD_COUNTDOWN, v.step)
        assertTrue(v.message.startsWith("Tip the starboard side down about 20° and hold"))
        assertEquals(3, v.countdownS)
        now += 1_000
        assertNull(cal.tick(now))
        assertEquals(2, v.countdownS)
        now += 2_000
        val stbd = cal.tick(now)!!
        assertEquals(P.IMUCAL_OP_STBD, stbd.op)
        assertEquals(2, stbd.tag)
        assertEquals(Step.STBD, v.step)
        assertTrue(cal.onAck(ack(stbd, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_CALIBRATED, roll = 41, pitch = -1)))
        assertEquals(Step.CALIBRATED, v.step)
        assertEquals("Calibrated. The pod read roll +20.5° for the tip (pitch -0.5°). It is saved on the pod.", v.message)
        assertEquals(true, v.calibrated)
        assertFalse(v.calibratedFromSteer)
        assertTrue(v.canClose); assertFalse(v.canCancel); assertFalse(v.canStartStarboard)
        cal.close()
        assertEquals(Step.IDLE, v.step)
        assertEquals("", v.message)
        assertEquals(true, v.calibrated) // closing keeps what is known
    }

    @Test
    fun answersWithAnotherTagOrOpAreIgnored() {
        val level = cal.start(now)!!
        assertFalse(cal.onAck(ack(level).copy(tag = level.tag + 1)))
        assertFalse(cal.onAck(ack(level).copy(op = P.IMUCAL_OP_QUERY)))
        assertEquals(Step.LEVEL, v.step)
        assertTrue(v.waiting)
        assertTrue(cal.onAck(ack(level)))
        assertFalse(cal.onAck(ack(level))) // a repeat after the answer
        assertEquals(Step.STBD_READY, v.step)
    }

    @Test
    fun tagsRunOneTo255AndWrap() {
        var last = 0
        repeat(256) {
            val r = cal.check(now)!!
            assertTrue(r.tag in 1..255)
            assertTrue(cal.onAck(ack(r)))
            last = r.tag
        }
        assertEquals(1, last) // 255 then 1 (0 is never used)
    }

    @Test
    fun noAnswerTimesOutAfterEightSeconds() {
        val level = cal.start(now)!!
        now += BtrxImuCal.ACK_TIMEOUT_MS - 1
        cal.tick(now)
        assertEquals(Step.LEVEL, v.step)
        now += 1
        assertNull(cal.tick(now))
        assertEquals(Step.FAILED, v.step)
        assertTrue(v.message, v.message.startsWith("Level step: no answer from the Drone Pod within 8 s."))
        assertTrue(v.message.contains("0x0102"))
        assertFalse(v.canStartStarboard) // no level held
        assertTrue(v.canStart); assertTrue(v.canClose)
        assertFalse(cal.onAck(ack(level))) // a late answer is not ours any more
        assertEquals(Step.FAILED, v.step)
    }

    @Test
    fun starboardTimeoutOffersRetryBecauseTheLevelIsKept() {
        throughCountdown()
        now += BtrxImuCal.ACK_TIMEOUT_MS
        cal.tick(now)
        assertEquals(Step.FAILED, v.step)
        assertTrue(v.message.startsWith("Starboard step: no answer"))
        assertTrue(v.canStartStarboard)
    }

    @Test
    fun failedStarboardWithLevelHeldIsRetried() {
        val stbd = throughCountdown()
        assertTrue(cal.onAck(ack(stbd, status = P.IMUCAL_TILT_SMALL, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_LEVEL_HELD, roll = 9)))
        assertEquals(Step.FAILED, v.step)
        assertEquals(P.IMUCAL_TILT_SMALL, v.failedStatus)
        assertEquals("Starboard step: " + BtrxImuCal.statusText(P.IMUCAL_TILT_SMALL), v.message)
        assertTrue(v.canStartStarboard)
        assertTrue(cal.startStarboard(now))
        now += BtrxImuCal.STBD_COUNTDOWN_MS
        val again = cal.tick(now)!!
        assertEquals(P.IMUCAL_OP_STBD, again.op)
        assertTrue(cal.onAck(ack(again, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_CALIBRATED, roll = 40)))
        assertEquals(Step.CALIBRATED, v.step)
    }

    @Test
    fun failedStarboardWithoutLevelNeedsStartAgain() {
        val stbd = throughCountdown()
        cal.onAck(ack(stbd, status = P.IMUCAL_NEED_LEVEL, flags = P.IMUCAL_F_IMU_OK))
        assertEquals(Step.FAILED, v.step)
        assertFalse(v.canStartStarboard)
        assertFalse(cal.startStarboard(now))
        assertTrue(v.canStart)
    }

    @Test
    fun saveFailIsAFailureWithRetry() {
        val stbd = throughCountdown()
        cal.onAck(ack(stbd, status = P.IMUCAL_SAVE_FAIL, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_LEVEL_HELD))
        assertEquals(Step.FAILED, v.step)
        assertTrue(v.canStartStarboard)
        assertTrue(v.message.contains("not saved"))
    }

    @Test
    fun failedLevelShowsWhy() {
        val level = cal.start(now)!!
        cal.onAck(ack(level, status = P.IMUCAL_NOT_LEVEL))
        assertEquals(Step.FAILED, v.step)
        assertEquals("Level step: " + BtrxImuCal.statusText(P.IMUCAL_NOT_LEVEL), v.message)
        assertFalse(v.canStartStarboard)
        val l2 = cal.start(now)!!
        cal.onAck(ack(l2, status = P.IMUCAL_NO_IMU, flags = 0))
        assertTrue(v.message.contains("motion sensor"))
    }

    @Test
    fun cancelEndsTheFlowAndSendsNothing() {
        cal.start(now)!!
        cal.cancel()
        assertEquals(Step.IDLE, v.step)
        assertEquals(BtrxImuCal.CANCELLED, v.message)
        assertFalse(v.waiting)
        assertTrue(v.canStart); assertTrue(v.canClose)
        // Cancel during the countdown: the starboard request never goes out.
        val level = cal.start(now)!!
        cal.onAck(ack(level, flags = P.IMUCAL_F_LEVEL_HELD))
        cal.startStarboard(now)
        cal.cancel()
        now += BtrxImuCal.STBD_COUNTDOWN_MS
        assertNull(cal.tick(now))
        assertEquals(BtrxImuCal.CANCELLED, v.message)
        // After the starboard step was sent: it may still save on the pod.
        throughCountdown()
        cal.cancel()
        assertEquals(BtrxImuCal.CANCELLED_AFTER_STBD, v.message)
        cal.close()
        assertEquals("", v.message)
        assertFalse(v.canClose)
    }

    @Test
    fun cancelOutsideTheFlowDoesNothing() {
        val r = cal.check(now)!!
        cal.cancel()
        assertEquals(Step.CHECKING, v.step)
        assertTrue(cal.onAck(ack(r, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_CALIBRATED, face = 1, y = 1)))
        assertEquals(Step.IDLE, v.step)
    }

    @Test
    fun checkDescribesTheSavedCalibration() {
        val r = cal.check(now)!!
        assertEquals(P.IMUCAL_OP_QUERY, r.op)
        assertFalse(v.canCheckOrClear); assertFalse(v.canStart)
        cal.onAck(ack(r, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_CALIBRATED, roll = 1, pitch = -3, face = 1, y = 1))
        assertEquals(Step.IDLE, v.step)
        assertEquals("A saved calibration is in use. Board component side down, its Y arrow pointing aft. Roll now +0.5°, pitch -1.5°.", v.message)
        assertEquals(true, v.calibrated)
        assertTrue(v.canClose)
        val r2 = cal.check(now)!!
        cal.onAck(ack(r2, flags = P.IMUCAL_F_IMU_OK, face = 255, y = 255))
        assertTrue(v.message.startsWith("No saved calibration: the pod uses the mounting set in its firmware build. Roll now"))
        assertEquals(false, v.calibrated)
        val r3 = cal.check(now)!!
        cal.onAck(ack(r3, status = P.IMUCAL_BUSY))
        assertEquals(Step.IDLE, v.step) // busy is information, not a failure
        assertEquals("The pod is still measuring a calibration step.", v.message)
    }

    @Test
    fun clearGoesBackToTheBuildSetting() {
        cal.onSteer(imuOk = true, imuCal = true)
        val r = cal.clear(now)!!
        assertEquals(P.IMUCAL_OP_CLEAR, r.op)
        assertEquals(Step.CLEARING, v.step)
        cal.onAck(ack(r, flags = P.IMUCAL_F_IMU_OK))
        assertEquals(Step.IDLE, v.step)
        assertEquals(false, v.calibrated)
        assertTrue(v.message.startsWith("Saved calibration cleared."))
    }

    @Test
    fun checkAndClearWaitForTheFlow() {
        cal.start(now)
        assertNull(cal.check(now))
        assertNull(cal.clear(now))
    }

    @Test
    fun steerBit6CountsOnlyWhileTheImuIsOk() {
        cal.onSteer(imuOk = false, imuCal = false)
        assertNull(v.calibrated)
        cal.onSteer(imuOk = true, imuCal = true)
        assertEquals(true, v.calibrated)
        assertTrue(v.calibratedFromSteer)
        assertEquals("IMU calibration: a saved calibration is in use (STEER).", BtrxImuCal.calibratedLine(v))
        cal.onSteer(imuOk = false, imuCal = false) // IMU dropped out: keep the last known
        assertEquals(true, v.calibrated)
        cal.onSteer(imuOk = true, imuCal = false)
        assertEquals(false, v.calibrated)
        // An ACK (even an unmatched one) is the newest word.
        assertFalse(cal.onAck(P.ImuCalAck(3, 99, 0, P.IMUCAL_F_IMU_OK or P.IMUCAL_F_CALIBRATED, 0, 0, 0, 0, 0, listOf(0, 0, 0))))
        assertEquals(true, v.calibrated)
        assertFalse(v.calibratedFromSteer)
        assertEquals("IMU calibration: a saved calibration is in use (the pod's answer).", BtrxImuCal.calibratedLine(v))
        cal.reset()
        assertEquals("IMU calibration: not known yet (tap Check).", BtrxImuCal.calibratedLine(v))
    }

    @Test
    fun linkLostAndNotSentFailTheStep() {
        cal.start(now)
        cal.linkLost("Bluetooth off")
        assertEquals(Step.FAILED, v.step)
        assertEquals("The link to the Desk Pod was lost (Bluetooth off).", v.message)
        assertFalse(v.waiting)
        cal.linkLost("again") // nothing out: no change
        assertEquals("The link to the Desk Pod was lost (Bluetooth off).", v.message)
        val r = cal.check(now)!!
        cal.notSent(BtrxImuCal.Request(r.op, r.tag + 1), "other") // not the one out
        assertEquals(Step.CHECKING, v.step)
        cal.notSent(null, "busy guard") // a request is out: not touched
        assertEquals(Step.CHECKING, v.step)
        cal.notSent(r, "The Desk Pod is not connected.")
        assertEquals(Step.FAILED, v.step)
        assertEquals("The Desk Pod is not connected.", v.message)
        cal.close()
        cal.notSent(null, "No Drone Pod is paired. Pair it first.")
        assertEquals(Step.FAILED, v.step)
    }

    @Test
    fun startStarboardOnlyAfterLevel() {
        assertFalse(cal.startStarboard(now))
        assertEquals(Step.IDLE, v.step)
    }

    @Test
    fun everyStatusHasPlainWords() {
        val texts = (0..9).map { BtrxImuCal.statusText(it) }
        assertEquals("OK.", texts[0])
        assertEquals(10, texts.toSet().size)
        assertTrue(texts.drop(1).all { it.endsWith(".") && it.length > 20 })
        assertTrue(texts[P.IMUCAL_MOTION].contains("moved too much"))
        assertTrue(texts[P.IMUCAL_TILT_SMALL].contains("too small"))
        assertTrue(texts[P.IMUCAL_NOT_ROLL].contains("not a roll"))
        assertEquals("Unknown answer from the pod (status 42).", BtrxImuCal.statusText(42))
        assertEquals("Level step", BtrxImuCal.opLabel(P.IMUCAL_OP_LEVEL))
        assertEquals("Starboard step", BtrxImuCal.opLabel(P.IMUCAL_OP_STBD))
        assertNotNull(BtrxImuCal.faceWords(0))
        assertEquals("to starboard", BtrxImuCal.yWords(3))
    }
}
