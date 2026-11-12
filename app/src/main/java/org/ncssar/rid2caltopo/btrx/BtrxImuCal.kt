package org.ncssar.rid2caltopo.btrx

import java.util.Locale
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * The Drone Pod's IMU calibration flow (btrx design doc section 29.3, firmware/PROTOCOL.md
 * "IMU_CAL", firmware 0x0102). Pure Kotlin, owned by [BtrxDeskPodModel], which sends the
 * requests this returns (IMU_CAL to the paired pod's node, like STEER_MUTE) and feeds back
 * the paired pod's IMU_CAL_ACK and STEER frames. The iPad has the same logic in Swift
 * (R2CCore BtrxImuCal.swift); keep them matching. Not thread-safe: the model's lock guards it.
 *
 * Flow: Calibrate sends level (op 1) at once: the pod must already be level and still; pod
 * B averages about 2 s. A good level step offers the starboard step: Start runs a
 * [STBD_COUNTDOWN_MS] countdown while the pilot tips the starboard side down about 20 deg,
 * then sends starboard down (op 2); pod B averages about 1 s and saves on success. A failed
 * starboard step keeps the level step on the pod (ACK flags bit 1), so it can be retried
 * without redoing level. Check sends status (op 3); Clear sends clear (op 0).
 *
 * One request at a time, matched by its tag (and op): an answer with another tag (late, or
 * for another requester) is ignored. No answer within [ACK_TIMEOUT_MS] fails the step; pod A
 * itself answers "no IMU" after 6 s when pod B is silent. Cancel ends the flow in the app
 * and sends nothing (a step already sent still runs on the pod). "Calibrated" comes from the
 * newest of STEER flags bit 6 (only meaningful while the IMU is ok) and an ACK's bit 0.
 */
class BtrxImuCal {
    enum class Step {
        IDLE,
        /** Level request out; the pod must be held level and still. */
        LEVEL,
        /** Level step done; waiting for the operator to start the starboard step. */
        STBD_READY,
        /** Counting down while the operator tips the starboard side down. */
        STBD_COUNTDOWN,
        /** Starboard-down request out; hold the tip still. */
        STBD,
        CALIBRATED,
        FAILED,
        CLEARING,
        CHECKING,
    }

    data class Request(val op: Int, val tag: Int)

    data class View(
        val step: Step,
        val message: String,
        /** Seconds left in [Step.STBD_COUNTDOWN]. */
        val countdownS: Int,
        /** A request is out and no answer has come yet. */
        val waiting: Boolean,
        /** "Calibrate" (or "Start again"): sends the level step. */
        val canStart: Boolean,
        /** "Start starboard step" (after level) or "Retry starboard step" (after a failed one, level kept). */
        val canStartStarboard: Boolean,
        /** "Cancel calibration": ends the flow. */
        val canCancel: Boolean,
        /** "Close": ends a finished result. */
        val canClose: Boolean,
        /** "Check" and "Clear saved calibration". */
        val canCheckOrClear: Boolean,
        /** Null when not known yet. */
        val calibrated: Boolean?,
        /** Where [calibrated] came from. */
        val calibratedFromSteer: Boolean,
        val lastAck: P.ImuCalAck?,
        val failedStatus: Int?,
    )

    private var step = Step.IDLE
    private var message = ""
    private var pending: Request? = null
    private var pendingSentMs = 0L
    private var countdownEndMs = 0L
    private var lastTag = 0
    private var lastAck: P.ImuCalAck? = null
    private var failedStatus: Int? = null
    /** The pod holds a good level step (ACK flags bit 1), so the starboard step can be (re)tried. */
    private var levelHeld = false
    private var calibrated: Boolean? = null
    private var calibratedFromSteer = false

    val currentStep: Step get() = step

    /** Forget everything (the paired pod was forgotten or replaced). */
    fun reset() {
        step = Step.IDLE; message = ""; pending = null; countdownEndMs = 0
        lastAck = null; failedStatus = null; levelHeld = false
        calibrated = null; calibratedFromSteer = false
    }

    private fun busy(): Boolean = pending != null || step == Step.STBD_COUNTDOWN

    private fun send(op: Int, nowMs: Long): Request {
        lastTag = lastTag % 255 + 1 // 1..255
        val r = Request(op, lastTag)
        pending = r
        pendingSentMs = nowMs
        return r
    }

    /** "Calibrate" / "Start again": the level step. Null while another request or the countdown runs. */
    fun start(nowMs: Long): Request? {
        if (busy()) return null
        step = Step.LEVEL; message = HOLD_LEVEL; failedStatus = null; levelHeld = false
        return send(P.IMUCAL_OP_LEVEL, nowMs)
    }

    /** "Start starboard step" / "Retry starboard step": begins the countdown. */
    fun startStarboard(nowMs: Long): Boolean {
        if (busy() || !(step == Step.STBD_READY || (step == Step.FAILED && levelHeld))) return false
        step = Step.STBD_COUNTDOWN; message = TIP_STBD; failedStatus = null
        countdownEndMs = nowMs + STBD_COUNTDOWN_MS
        return true
    }

    /** "Check": status (op 3). */
    fun check(nowMs: Long): Request? {
        if (busy() || step !in IDLE_STEPS) return null
        step = Step.CHECKING; message = "Asking the Drone Pod which calibration it uses…"; failedStatus = null
        return send(P.IMUCAL_OP_QUERY, nowMs)
    }

    /** "Clear saved calibration": clear (op 0); the pod goes back to its build setting. */
    fun clear(nowMs: Long): Request? {
        if (busy() || step !in IDLE_STEPS) return null
        step = Step.CLEARING; message = "Clearing the saved calibration on the Drone Pod…"; failedStatus = null
        return send(P.IMUCAL_OP_CLEAR, nowMs)
    }

    /** About once a second: the countdown (returns the starboard request when it ends) and the answer timeout. */
    fun tick(nowMs: Long): Request? {
        val p = pending
        if (p != null) {
            if (nowMs - pendingSentMs >= ACK_TIMEOUT_MS) timeout(p)
            return null
        }
        if (step == Step.STBD_COUNTDOWN && nowMs >= countdownEndMs) {
            step = Step.STBD; message = HOLD_TIP
            return send(P.IMUCAL_OP_STBD, nowMs)
        }
        return null
    }

    /** The model could not send [r] (no paired pod, or the Desk Pod is not connected). */
    fun notSent(r: Request?, reason: String) {
        if (if (r == null) busy() else pending != r) return
        pending = null
        step = Step.FAILED; message = reason; failedStatus = null
    }

    /** "Cancel calibration": ends the flow in the app; nothing is sent to the pod. */
    fun cancel() {
        if (step !in FLOW_STEPS) return
        val sentStbd = pending?.op == P.IMUCAL_OP_STBD
        pending = null
        step = Step.IDLE
        message = if (sentStbd) CANCELLED_AFTER_STBD else CANCELLED
    }

    /** "Close": ends a finished result (calibrated, failed, or a check/clear answer). */
    fun close() {
        if (step == Step.CALIBRATED || step == Step.FAILED || step == Step.IDLE) {
            step = Step.IDLE; message = ""; failedStatus = null
        }
    }

    /** The link to the Desk Pod ended: a request or countdown in progress fails. */
    fun linkLost(reason: String) {
        if (!busy()) return
        pending = null
        step = Step.FAILED; message = "The link to the Desk Pod was lost ($reason)."; failedStatus = null
    }

    /** STEER from the paired pod: bit 6 says a saved calibration is in use; it is only set while the IMU is ok. */
    fun onSteer(imuOk: Boolean, imuCal: Boolean) {
        if (!imuOk) return
        calibrated = imuCal; calibratedFromSteer = true
    }

    /** IMU_CAL_ACK from the paired pod. Returns true when it answered the request out. */
    fun onAck(ack: P.ImuCalAck): Boolean {
        calibrated = ack.calibrated; calibratedFromSteer = false
        val p = pending
        if (p == null || ack.tag != p.tag || ack.op != p.op) return false
        pending = null
        lastAck = ack
        levelHeld = ack.levelHeld
        val ok = ack.status == P.IMUCAL_OK
        when (p.op) {
            P.IMUCAL_OP_LEVEL -> if (ok) {
                levelHeld = true
                step = Step.STBD_READY; failedStatus = null
                message = "Level step done (board ${deg(ack.tiltX2)} from flat in the lid). $NEXT_STBD"
            } else fail(p.op, ack.status)
            P.IMUCAL_OP_STBD -> if (ok) {
                step = Step.CALIBRATED; failedStatus = null; levelHeld = false
                calibrated = true
                message = "Calibrated. The pod read roll ${signedDeg(ack.rollX2)} for the tip (pitch ${signedDeg(ack.pitchX2)}). It is saved on the pod."
            } else fail(p.op, ack.status)
            P.IMUCAL_OP_CLEAR -> if (ok) {
                step = Step.IDLE; failedStatus = null; levelHeld = false
                calibrated = false
                message = "Saved calibration cleared. The pod uses the mounting set in its firmware build."
            } else fail(p.op, ack.status)
            else -> if (ok || ack.status == P.IMUCAL_BUSY) {
                step = Step.IDLE; failedStatus = null
                message = describe(ack)
            } else fail(p.op, ack.status)
        }
        return true
    }

    fun view(nowMs: Long): View = View(
        step = step,
        message = message,
        countdownS = if (step == Step.STBD_COUNTDOWN) ((countdownEndMs - nowMs + 999) / 1000).toInt().coerceAtLeast(0) else 0,
        waiting = pending != null,
        canStart = !busy() && step !in FLOW_STEPS && step != Step.CLEARING && step != Step.CHECKING,
        canStartStarboard = !busy() && (step == Step.STBD_READY || (step == Step.FAILED && levelHeld)),
        canCancel = step in FLOW_STEPS,
        canClose = step == Step.CALIBRATED || step == Step.FAILED || (step == Step.IDLE && message.isNotEmpty()),
        canCheckOrClear = !busy() && step in IDLE_STEPS,
        calibrated = calibrated,
        calibratedFromSteer = calibratedFromSteer,
        lastAck = lastAck,
        failedStatus = failedStatus,
    )

    private fun fail(op: Int, status: Int) {
        step = Step.FAILED; failedStatus = status
        message = "${opLabel(op)}: ${statusText(status)}"
    }

    private fun timeout(p: Request) {
        pending = null
        if (p.op == P.IMUCAL_OP_LEVEL) levelHeld = false
        step = Step.FAILED; failedStatus = null
        message = "${opLabel(p.op)}: no answer from the Drone Pod within ${ACK_TIMEOUT_MS / 1000} s. Check the link; IMU calibration needs Drone Pod firmware 0x0102 or later."
    }

    companion object {
        /** Give up on an answer after this long (pod A answers "no IMU" itself after 6 s). */
        const val ACK_TIMEOUT_MS = 8_000L
        /** Countdown before the starboard-down request, as r2c_sim's 3 s. */
        const val STBD_COUNTDOWN_MS = 3_000L

        val FLOW_STEPS = setOf(Step.LEVEL, Step.STBD_READY, Step.STBD_COUNTDOWN, Step.STBD)
        val IDLE_STEPS = setOf(Step.IDLE, Step.CALIBRATED, Step.FAILED)

        const val HOLD_LEVEL = "Hold the pod level and still. Measuring for about 2 seconds…"
        const val NEXT_STBD = "Next: tap Start starboard step, then tip the starboard side down about 20° and hold it still before the countdown ends."
        const val TIP_STBD = "Tip the starboard side down about 20° and hold it still."
        const val HOLD_TIP = "Hold the tip still. Measuring for about 1 second…"
        const val CANCELLED = "Calibration cancelled. Nothing was saved; the pod keeps the calibration it had."
        const val CANCELLED_AFTER_STBD = "Calibration cancelled. The starboard step had already been sent: if it worked, the pod saved it. Tap Check to see."

        fun opLabel(op: Int): String = when (op) {
            P.IMUCAL_OP_LEVEL -> "Level step"
            P.IMUCAL_OP_STBD -> "Starboard step"
            P.IMUCAL_OP_CLEAR -> "Clear"
            P.IMUCAL_OP_QUERY -> "Check"
            else -> "Request $op"
        }

        /** IMU_CAL_ACK status in plain words, with what to do (PROTOCOL.md status table). */
        fun statusText(status: Int): String = when (status) {
            P.IMUCAL_OK -> "OK."
            P.IMUCAL_MOTION -> "The pod moved too much. Hold it still and try again."
            P.IMUCAL_TILT_SMALL -> "The tip was too small (under 8°). Tip the starboard side down further, about 20°, and try again."
            P.IMUCAL_NOT_LEVEL -> "The pod was not level (more than 20° off flat). Set it level and start again."
            P.IMUCAL_NEED_LEVEL -> "The level step is needed first. Start again with the pod level."
            P.IMUCAL_NO_IMU -> "No answer from the motion sensor: the port board or its IMU did not respond. Check the cable between the boards."
            P.IMUCAL_NOT_ROLL -> "That was not a roll: the tip was mostly nose up or down, or diagonal. Tip only the starboard side down and try again."
            P.IMUCAL_BAD_OP -> "The Drone Pod did not accept the request."
            P.IMUCAL_SAVE_FAIL -> "Worked out but not saved: in use until the pod is powered off. Try the starboard step again."
            P.IMUCAL_BUSY -> "The pod is still measuring. Wait a moment and try again."
            else -> "Unknown answer from the pod (status $status)."
        }

        /** The "Check" answer in words. */
        fun describe(a: P.ImuCalAck): String {
            if (a.status == P.IMUCAL_BUSY) return "The pod is still measuring a calibration step."
            val mount = if (a.face == P.IMU_UNKNOWN && a.yPoints == P.IMU_UNKNOWN) "" else
                " Board ${faceWords(a.face)}, its Y arrow pointing ${yWords(a.yPoints)}."
            val cal = if (a.calibrated) "A saved calibration is in use." else "No saved calibration: the pod uses the mounting set in its firmware build."
            return "$cal$mount Roll now ${signedDeg(a.rollX2)}, pitch ${signedDeg(a.pitchX2)}."
        }

        fun faceWords(face: Int): String = when (face) { 0 -> "component side up"; 1 -> "component side down"; else -> "facing unknown" }
        fun yWords(y: Int): String = when (y) { 0 -> "forward"; 1 -> "aft"; 2 -> "to port"; 3 -> "to starboard"; else -> "unknown" }

        fun deg(x2: Int): String = String.format(Locale.US, "%.1f°", x2 / 2.0)
        fun signedDeg(x2: Int): String = String.format(Locale.US, "%+.1f°", x2 / 2.0)

        /** The Desk Pod screen's calibration line. */
        fun calibratedLine(v: View): String = when (v.calibrated) {
            null -> "IMU calibration: not known yet (tap Check)."
            true -> "IMU calibration: a saved calibration is in use (${if (v.calibratedFromSteer) "STEER" else "the pod's answer"})."
            false -> "IMU calibration: none saved; the pod uses the mounting set in its firmware build (${if (v.calibratedFromSteer) "STEER" else "the pod's answer"})."
        }
    }
}
