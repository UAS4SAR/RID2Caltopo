import Foundation

/// The Drone Pod's IMU calibration flow (btrx design doc section 29.3, firmware/PROTOCOL.md
/// "IMU_CAL", firmware 0x0102). Owned by ``BtrxDeskPodModel``, which sends the requests this
/// returns (IMU_CAL to the paired pod's node, like STEER_MUTE) and feeds back the paired
/// pod's IMU_CAL_ACK and STEER frames. Same as Android's BtrxImuCal.kt; keep them matching.
/// Not thread-safe: the model's queue confines it.
///
/// Flow: Calibrate sends level (op 1) at once: the pod must already be level and still; pod
/// B averages about 2 s. A good level step offers the starboard step: Start runs a
/// ``stbdCountdownMs`` countdown while the pilot tips the starboard side down about 20 deg,
/// then sends starboard down (op 2); pod B averages about 1 s and saves on success. A failed
/// starboard step keeps the level step on the pod (ACK flags bit 1), so it can be retried
/// without redoing level. Check sends status (op 3); Clear sends clear (op 0).
///
/// One request at a time, matched by its tag (and op): an answer with another tag (late, or
/// for another requester) is ignored. No answer within ``ackTimeoutMs`` fails the step; pod A
/// itself answers "no IMU" after 6 s when pod B is silent. Cancel ends the flow in the app
/// and sends nothing (a step already sent still runs on the pod). "Calibrated" comes from the
/// newest of STEER flags bit 6 (only meaningful while the IMU is ok) and an ACK's bit 0.
public final class BtrxImuCal {
    private typealias P = BtrxProtocol

    public enum Step: String, Sendable {
        case IDLE
        /// Level request out; the pod must be held level and still.
        case LEVEL
        /// Level step done; waiting for the operator to start the starboard step.
        case STBD_READY
        /// Counting down while the operator tips the starboard side down.
        case STBD_COUNTDOWN
        /// Starboard-down request out; hold the tip still.
        case STBD
        case CALIBRATED
        case FAILED
        case CLEARING
        case CHECKING
    }

    public struct Request: Sendable, Equatable {
        public let op: Int, tag: Int
        public init(op: Int, tag: Int) { self.op = op; self.tag = tag }
    }

    public struct View: Sendable, Equatable {
        public let step: Step
        public let message: String
        /// Seconds left in ``Step/STBD_COUNTDOWN``.
        public let countdownS: Int
        /// A request is out and no answer has come yet.
        public let waiting: Bool
        /// "Calibrate" (or "Start again"): sends the level step.
        public let canStart: Bool
        /// "Start starboard step" (after level) or "Retry starboard step" (after a failed one, level kept).
        public let canStartStarboard: Bool
        /// "Cancel calibration": ends the flow.
        public let canCancel: Bool
        /// "Close": ends a finished result.
        public let canClose: Bool
        /// "Check" and "Clear saved calibration".
        public let canCheckOrClear: Bool
        /// Nil when not known yet.
        public let calibrated: Bool?
        /// Where ``calibrated`` came from.
        public let calibratedFromSteer: Bool
        public let lastAck: BtrxProtocol.ImuCalAck?
        public let failedStatus: Int?
    }

    /// Give up on an answer after this long (pod A answers "no IMU" itself after 6 s).
    public static let ackTimeoutMs: Int64 = 8_000
    /// Countdown before the starboard-down request, as r2c_sim's 3 s.
    public static let stbdCountdownMs: Int64 = 3_000

    public static let flowSteps: Set<Step> = [.LEVEL, .STBD_READY, .STBD_COUNTDOWN, .STBD]
    public static let idleSteps: Set<Step> = [.IDLE, .CALIBRATED, .FAILED]

    public static let holdLevel = "Hold the pod level and still. Measuring for about 2 seconds…"
    public static let nextStbd = "Next: tap Start starboard step, then tip the starboard side down about 20° and hold it still before the countdown ends."
    public static let tipStbd = "Tip the starboard side down about 20° and hold it still."
    public static let holdTip = "Hold the tip still. Measuring for about 1 second…"
    public static let cancelled = "Calibration cancelled. Nothing was saved; the pod keeps the calibration it had."
    public static let cancelledAfterStbd = "Calibration cancelled. The starboard step had already been sent: if it worked, the pod saved it. Tap Check to see."

    private var step: Step = .IDLE
    private var message = ""
    private var pending: Request?
    private var pendingSentMs: Int64 = 0
    private var countdownEndMs: Int64 = 0
    private var lastTag = 0
    private var lastAck: BtrxProtocol.ImuCalAck?
    private var failedStatus: Int?
    /// The pod holds a good level step (ACK flags bit 1), so the starboard step can be (re)tried.
    private var levelHeld = false
    private var calibrated: Bool?
    private var calibratedFromSteer = false

    public init() {}

    public var currentStep: Step { step }

    /// Forget everything (the paired pod was forgotten or replaced).
    public func reset() {
        step = .IDLE; message = ""; pending = nil; countdownEndMs = 0
        lastAck = nil; failedStatus = nil; levelHeld = false
        calibrated = nil; calibratedFromSteer = false
    }

    private func busy() -> Bool { pending != nil || step == .STBD_COUNTDOWN }

    private func send(_ op: Int, _ nowMs: Int64) -> Request {
        lastTag = lastTag % 255 + 1 // 1..255
        let r = Request(op: op, tag: lastTag)
        pending = r
        pendingSentMs = nowMs
        return r
    }

    /// "Calibrate" / "Start again": the level step. Nil while another request or the countdown runs.
    public func start(nowMs: Int64) -> Request? {
        if busy() { return nil }
        step = .LEVEL; message = Self.holdLevel; failedStatus = nil; levelHeld = false
        return send(P.IMUCAL_OP_LEVEL, nowMs)
    }

    /// "Start starboard step" / "Retry starboard step": begins the countdown.
    @discardableResult
    public func startStarboard(nowMs: Int64) -> Bool {
        if busy() || !(step == .STBD_READY || (step == .FAILED && levelHeld)) { return false }
        step = .STBD_COUNTDOWN; message = Self.tipStbd; failedStatus = nil
        countdownEndMs = nowMs + Self.stbdCountdownMs
        return true
    }

    /// "Check": status (op 3).
    public func check(nowMs: Int64) -> Request? {
        if busy() || !Self.idleSteps.contains(step) { return nil }
        step = .CHECKING; message = "Asking the Drone Pod which calibration it uses…"; failedStatus = nil
        return send(P.IMUCAL_OP_QUERY, nowMs)
    }

    /// "Clear saved calibration": clear (op 0); the pod goes back to its build setting.
    public func clear(nowMs: Int64) -> Request? {
        if busy() || !Self.idleSteps.contains(step) { return nil }
        step = .CLEARING; message = "Clearing the saved calibration on the Drone Pod…"; failedStatus = nil
        return send(P.IMUCAL_OP_CLEAR, nowMs)
    }

    /// About once a second: the countdown (returns the starboard request when it ends) and the answer timeout.
    public func tick(nowMs: Int64) -> Request? {
        if let p = pending {
            if nowMs - pendingSentMs >= Self.ackTimeoutMs { timeout(p) }
            return nil
        }
        if step == .STBD_COUNTDOWN && nowMs >= countdownEndMs {
            step = .STBD; message = Self.holdTip
            return send(P.IMUCAL_OP_STBD, nowMs)
        }
        return nil
    }

    /// The model could not send `r` (no paired pod, or the Desk Pod is not connected).
    public func notSent(_ r: Request?, reason: String) {
        if let r { if pending != r { return } } else if busy() { return }
        pending = nil
        step = .FAILED; message = reason; failedStatus = nil
    }

    /// "Cancel calibration": ends the flow in the app; nothing is sent to the pod.
    public func cancel() {
        guard Self.flowSteps.contains(step) else { return }
        let sentStbd = pending?.op == P.IMUCAL_OP_STBD
        pending = nil
        step = .IDLE
        message = sentStbd ? Self.cancelledAfterStbd : Self.cancelled
    }

    /// "Close": ends a finished result (calibrated, failed, or a check/clear answer).
    public func close() {
        if step == .CALIBRATED || step == .FAILED || step == .IDLE {
            step = .IDLE; message = ""; failedStatus = nil
        }
    }

    /// The link to the Desk Pod ended: a request or countdown in progress fails.
    public func linkLost(reason: String) {
        guard busy() else { return }
        pending = nil
        step = .FAILED; message = "The link to the Desk Pod was lost (\(reason))."; failedStatus = nil
    }

    /// STEER from the paired pod: bit 6 says a saved calibration is in use; it is only set while the IMU is ok.
    public func onSteer(imuOk: Bool, imuCal: Bool) {
        guard imuOk else { return }
        calibrated = imuCal; calibratedFromSteer = true
    }

    /// IMU_CAL_ACK from the paired pod. Returns true when it answered the request out.
    @discardableResult
    public func onAck(_ ack: BtrxProtocol.ImuCalAck) -> Bool {
        calibrated = ack.calibrated; calibratedFromSteer = false
        guard let p = pending, ack.tag == p.tag, ack.op == p.op else { return false }
        pending = nil
        lastAck = ack
        levelHeld = ack.levelHeld
        let ok = ack.status == P.IMUCAL_OK
        switch p.op {
        case P.IMUCAL_OP_LEVEL:
            if ok {
                levelHeld = true
                step = .STBD_READY; failedStatus = nil
                message = "Level step done (board \(Self.deg(ack.tiltX2)) from flat in the lid). \(Self.nextStbd)"
            } else { fail(p.op, ack.status) }
        case P.IMUCAL_OP_STBD:
            if ok {
                step = .CALIBRATED; failedStatus = nil; levelHeld = false
                calibrated = true
                message = "Calibrated. The pod read roll \(Self.signedDeg(ack.rollX2)) for the tip (pitch \(Self.signedDeg(ack.pitchX2))). It is saved on the pod."
            } else { fail(p.op, ack.status) }
        case P.IMUCAL_OP_CLEAR:
            if ok {
                step = .IDLE; failedStatus = nil; levelHeld = false
                calibrated = false
                message = "Saved calibration cleared. The pod uses the mounting set in its firmware build."
            } else { fail(p.op, ack.status) }
        default:
            if ok || ack.status == P.IMUCAL_BUSY {
                step = .IDLE; failedStatus = nil
                message = Self.describe(ack)
            } else { fail(p.op, ack.status) }
        }
        return true
    }

    public func view(nowMs: Int64) -> View {
        View(
            step: step,
            message: message,
            countdownS: step == .STBD_COUNTDOWN ? max(0, Int((countdownEndMs - nowMs + 999) / 1000)) : 0,
            waiting: pending != nil,
            canStart: !busy() && !Self.flowSteps.contains(step) && step != .CLEARING && step != .CHECKING,
            canStartStarboard: !busy() && (step == .STBD_READY || (step == .FAILED && levelHeld)),
            canCancel: Self.flowSteps.contains(step),
            canClose: step == .CALIBRATED || step == .FAILED || (step == .IDLE && !message.isEmpty),
            canCheckOrClear: !busy() && Self.idleSteps.contains(step),
            calibrated: calibrated,
            calibratedFromSteer: calibratedFromSteer,
            lastAck: lastAck,
            failedStatus: failedStatus
        )
    }

    private func fail(_ op: Int, _ status: Int) {
        step = .FAILED; failedStatus = status
        message = "\(Self.opLabel(op)): \(Self.statusText(status))"
    }

    private func timeout(_ p: Request) {
        pending = nil
        if p.op == P.IMUCAL_OP_LEVEL { levelHeld = false }
        step = .FAILED; failedStatus = nil
        message = "\(Self.opLabel(p.op)): no answer from the Drone Pod within \(Self.ackTimeoutMs / 1000) s. Check the link; IMU calibration needs Drone Pod firmware 0x0102 or later."
    }

    public static func opLabel(_ op: Int) -> String {
        switch op {
        case P.IMUCAL_OP_LEVEL: return "Level step"
        case P.IMUCAL_OP_STBD: return "Starboard step"
        case P.IMUCAL_OP_CLEAR: return "Clear"
        case P.IMUCAL_OP_QUERY: return "Check"
        default: return "Request \(op)"
        }
    }

    /// IMU_CAL_ACK status in plain words, with what to do (PROTOCOL.md status table).
    public static func statusText(_ status: Int) -> String {
        switch status {
        case P.IMUCAL_OK: return "OK."
        case P.IMUCAL_MOTION: return "The pod moved too much. Hold it still and try again."
        case P.IMUCAL_TILT_SMALL: return "The tip was too small (under 8°). Tip the starboard side down further, about 20°, and try again."
        case P.IMUCAL_NOT_LEVEL: return "The pod was not level (more than 20° off flat). Set it level and start again."
        case P.IMUCAL_NEED_LEVEL: return "The level step is needed first. Start again with the pod level."
        case P.IMUCAL_NO_IMU: return "No answer from the motion sensor: the port board or its IMU did not respond. Check the cable between the boards."
        case P.IMUCAL_NOT_ROLL: return "That was not a roll: the tip was mostly nose up or down, or diagonal. Tip only the starboard side down and try again."
        case P.IMUCAL_BAD_OP: return "The Drone Pod did not accept the request."
        case P.IMUCAL_SAVE_FAIL: return "Worked out but not saved: in use until the pod is powered off. Try the starboard step again."
        case P.IMUCAL_BUSY: return "The pod is still measuring. Wait a moment and try again."
        default: return "Unknown answer from the pod (status \(status))."
        }
    }

    /// The "Check" answer in words.
    public static func describe(_ a: BtrxProtocol.ImuCalAck) -> String {
        if a.status == P.IMUCAL_BUSY { return "The pod is still measuring a calibration step." }
        let mount = (a.face == P.IMU_UNKNOWN && a.yPoints == P.IMU_UNKNOWN) ? "" :
            " Board \(faceWords(a.face)), its Y arrow pointing \(yWords(a.yPoints))."
        let cal = a.calibrated ? "A saved calibration is in use." : "No saved calibration: the pod uses the mounting set in its firmware build."
        return "\(cal)\(mount) Roll now \(signedDeg(a.rollX2)), pitch \(signedDeg(a.pitchX2))."
    }

    public static func faceWords(_ face: Int) -> String {
        switch face { case 0: return "component side up"; case 1: return "component side down"; default: return "facing unknown" }
    }

    public static func yWords(_ y: Int) -> String {
        switch y { case 0: return "forward"; case 1: return "aft"; case 2: return "to port"; case 3: return "to starboard"; default: return "unknown" }
    }

    public static func deg(_ x2: Int) -> String { String(format: "%.1f°", locale: Locale(identifier: "en_US_POSIX"), Double(x2) / 2.0) }
    public static func signedDeg(_ x2: Int) -> String { String(format: "%+.1f°", locale: Locale(identifier: "en_US_POSIX"), Double(x2) / 2.0) }

    /// The Desk Pod screen's calibration line.
    public static func calibratedLine(_ v: View) -> String {
        let src = v.calibratedFromSteer ? "STEER" : "the pod's answer"
        switch v.calibrated {
        case nil: return "IMU calibration: not known yet (tap Check)."
        case true?: return "IMU calibration: a saved calibration is in use (\(src))."
        case false?: return "IMU calibration: none saved; the pod uses the mounting set in its firmware build (\(src))."
        }
    }
}
