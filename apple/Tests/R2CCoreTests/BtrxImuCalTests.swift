import XCTest
@testable import R2CCore

/// The IMU calibration flow (design doc section 29.3). Swift copy of Android's BtrxImuCalTest.kt.
final class BtrxImuCalTests: XCTestCase {
    typealias P = BtrxProtocol
    typealias Step = BtrxImuCal.Step

    var now: Int64 = 50_000
    var cal = BtrxImuCal()
    var v: BtrxImuCal.View { cal.view(nowMs: now) }

    override func setUp() { super.setUp(); now = 50_000; cal = BtrxImuCal() }

    func ack(_ r: BtrxImuCal.Request, status: Int = P.IMUCAL_OK, flags: Int = P.IMUCAL_F_IMU_OK, roll: Int = 0, pitch: Int = 0,
             tilt: Int = 6, face: Int = 0, y: Int = 0, tag: Int? = nil, op: Int? = nil) -> P.ImuCalAck {
        P.ImuCalAck(op: op ?? r.op, tag: tag ?? r.tag, status: status, flags: flags, rollX2: roll, pitchX2: pitch, tiltX2: tilt,
                    face: face, yPoints: y, gyroBiasX10: [0, 0, 0])
    }

    /// Level OK, Start starboard step, countdown; returns the starboard request.
    func throughCountdown() -> BtrxImuCal.Request {
        let level = cal.start(nowMs: now)!
        XCTAssertTrue(cal.onAck(ack(level, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_LEVEL_HELD)))
        XCTAssertTrue(cal.startStarboard(nowMs: now))
        now += BtrxImuCal.stbdCountdownMs
        return cal.tick(nowMs: now)!
    }

    func testHappyPathLevelThenStarboardIsCalibrated() {
        XCTAssertTrue(v.canStart); XCTAssertFalse(v.canStartStarboard); XCTAssertTrue(v.canCheckOrClear)
        XCTAssertNil(v.calibrated)
        let level = cal.start(nowMs: now)!
        XCTAssertEqual(P.IMUCAL_OP_LEVEL, level.op)
        XCTAssertEqual(1, level.tag)
        XCTAssertEqual(.LEVEL, v.step)
        XCTAssertTrue(v.message.hasPrefix("Hold the pod level and still"))
        XCTAssertTrue(v.waiting); XCTAssertTrue(v.canCancel); XCTAssertFalse(v.canStart); XCTAssertFalse(v.canCheckOrClear)
        XCTAssertNil(cal.start(nowMs: now)) // one request at a time
        XCTAssertTrue(cal.onAck(ack(level, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_LEVEL_HELD, tilt: 7)))
        XCTAssertEqual(.STBD_READY, v.step)
        XCTAssertTrue(v.message.hasPrefix("Level step done (board 3.5° from flat in the lid)."), v.message)
        XCTAssertTrue(v.canStartStarboard)
        XCTAssertTrue(cal.startStarboard(nowMs: now))
        XCTAssertEqual(.STBD_COUNTDOWN, v.step)
        XCTAssertTrue(v.message.hasPrefix("Tip the starboard side down about 20° and hold"))
        XCTAssertEqual(3, v.countdownS)
        now += 1_000
        XCTAssertNil(cal.tick(nowMs: now))
        XCTAssertEqual(2, v.countdownS)
        now += 2_000
        let stbd = cal.tick(nowMs: now)!
        XCTAssertEqual(P.IMUCAL_OP_STBD, stbd.op)
        XCTAssertEqual(2, stbd.tag)
        XCTAssertEqual(.STBD, v.step)
        XCTAssertTrue(cal.onAck(ack(stbd, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_CALIBRATED, roll: 41, pitch: -1)))
        XCTAssertEqual(.CALIBRATED, v.step)
        XCTAssertEqual("Calibrated. The pod read roll +20.5° for the tip (pitch -0.5°). It is saved on the pod.", v.message)
        XCTAssertEqual(true, v.calibrated)
        XCTAssertFalse(v.calibratedFromSteer)
        XCTAssertTrue(v.canClose); XCTAssertFalse(v.canCancel); XCTAssertFalse(v.canStartStarboard)
        cal.close()
        XCTAssertEqual(.IDLE, v.step)
        XCTAssertEqual("", v.message)
        XCTAssertEqual(true, v.calibrated) // closing keeps what is known
    }

    func testAnswersWithAnotherTagOrOpAreIgnored() {
        let level = cal.start(nowMs: now)!
        XCTAssertFalse(cal.onAck(ack(level, tag: level.tag + 1)))
        XCTAssertFalse(cal.onAck(ack(level, op: P.IMUCAL_OP_QUERY)))
        XCTAssertEqual(.LEVEL, v.step)
        XCTAssertTrue(v.waiting)
        XCTAssertTrue(cal.onAck(ack(level)))
        XCTAssertFalse(cal.onAck(ack(level))) // a repeat after the answer
        XCTAssertEqual(.STBD_READY, v.step)
    }

    func testTagsRunOneTo255AndWrap() {
        var last = 0
        for _ in 0..<256 {
            let r = cal.check(nowMs: now)!
            XCTAssertTrue((1...255).contains(r.tag))
            XCTAssertTrue(cal.onAck(ack(r)))
            last = r.tag
        }
        XCTAssertEqual(1, last) // 255 then 1 (0 is never used)
    }

    func testNoAnswerTimesOutAfterEightSeconds() {
        let level = cal.start(nowMs: now)!
        now += BtrxImuCal.ackTimeoutMs - 1
        _ = cal.tick(nowMs: now)
        XCTAssertEqual(.LEVEL, v.step)
        now += 1
        XCTAssertNil(cal.tick(nowMs: now))
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertTrue(v.message.hasPrefix("Level step: no answer from the Drone Pod within 8 s."), v.message)
        XCTAssertTrue(v.message.contains("0x0102"))
        XCTAssertFalse(v.canStartStarboard) // no level held
        XCTAssertTrue(v.canStart); XCTAssertTrue(v.canClose)
        XCTAssertFalse(cal.onAck(ack(level))) // a late answer is not ours any more
        XCTAssertEqual(.FAILED, v.step)
    }

    func testStarboardTimeoutOffersRetryBecauseTheLevelIsKept() {
        _ = throughCountdown()
        now += BtrxImuCal.ackTimeoutMs
        _ = cal.tick(nowMs: now)
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertTrue(v.message.hasPrefix("Starboard step: no answer"))
        XCTAssertTrue(v.canStartStarboard)
    }

    func testFailedStarboardWithLevelHeldIsRetried() {
        let stbd = throughCountdown()
        XCTAssertTrue(cal.onAck(ack(stbd, status: P.IMUCAL_TILT_SMALL, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_LEVEL_HELD, roll: 9)))
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertEqual(P.IMUCAL_TILT_SMALL, v.failedStatus)
        XCTAssertEqual("Starboard step: " + BtrxImuCal.statusText(P.IMUCAL_TILT_SMALL), v.message)
        XCTAssertTrue(v.canStartStarboard)
        XCTAssertTrue(cal.startStarboard(nowMs: now))
        now += BtrxImuCal.stbdCountdownMs
        let again = cal.tick(nowMs: now)!
        XCTAssertEqual(P.IMUCAL_OP_STBD, again.op)
        XCTAssertTrue(cal.onAck(ack(again, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_CALIBRATED, roll: 40)))
        XCTAssertEqual(.CALIBRATED, v.step)
    }

    func testFailedStarboardWithoutLevelNeedsStartAgain() {
        let stbd = throughCountdown()
        cal.onAck(ack(stbd, status: P.IMUCAL_NEED_LEVEL, flags: P.IMUCAL_F_IMU_OK))
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertFalse(v.canStartStarboard)
        XCTAssertFalse(cal.startStarboard(nowMs: now))
        XCTAssertTrue(v.canStart)
    }

    func testSaveFailIsAFailureWithRetry() {
        let stbd = throughCountdown()
        cal.onAck(ack(stbd, status: P.IMUCAL_SAVE_FAIL, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_LEVEL_HELD))
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertTrue(v.canStartStarboard)
        XCTAssertTrue(v.message.contains("not saved"))
    }

    func testFailedLevelShowsWhy() {
        let level = cal.start(nowMs: now)!
        cal.onAck(ack(level, status: P.IMUCAL_NOT_LEVEL))
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertEqual("Level step: " + BtrxImuCal.statusText(P.IMUCAL_NOT_LEVEL), v.message)
        XCTAssertFalse(v.canStartStarboard)
        let l2 = cal.start(nowMs: now)!
        cal.onAck(ack(l2, status: P.IMUCAL_NO_IMU, flags: 0))
        XCTAssertTrue(v.message.contains("motion sensor"))
    }

    func testCancelEndsTheFlowAndSendsNothing() {
        _ = cal.start(nowMs: now)!
        cal.cancel()
        XCTAssertEqual(.IDLE, v.step)
        XCTAssertEqual(BtrxImuCal.cancelled, v.message)
        XCTAssertFalse(v.waiting)
        XCTAssertTrue(v.canStart); XCTAssertTrue(v.canClose)
        // Cancel during the countdown: the starboard request never goes out.
        let level = cal.start(nowMs: now)!
        cal.onAck(ack(level, flags: P.IMUCAL_F_LEVEL_HELD))
        cal.startStarboard(nowMs: now)
        cal.cancel()
        now += BtrxImuCal.stbdCountdownMs
        XCTAssertNil(cal.tick(nowMs: now))
        XCTAssertEqual(BtrxImuCal.cancelled, v.message)
        // After the starboard step was sent: it may still save on the pod.
        _ = throughCountdown()
        cal.cancel()
        XCTAssertEqual(BtrxImuCal.cancelledAfterStbd, v.message)
        cal.close()
        XCTAssertEqual("", v.message)
        XCTAssertFalse(v.canClose)
    }

    func testCancelOutsideTheFlowDoesNothing() {
        let r = cal.check(nowMs: now)!
        cal.cancel()
        XCTAssertEqual(.CHECKING, v.step)
        XCTAssertTrue(cal.onAck(ack(r, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_CALIBRATED, face: 1, y: 1)))
        XCTAssertEqual(.IDLE, v.step)
    }

    func testCheckDescribesTheSavedCalibration() {
        let r = cal.check(nowMs: now)!
        XCTAssertEqual(P.IMUCAL_OP_QUERY, r.op)
        XCTAssertFalse(v.canCheckOrClear); XCTAssertFalse(v.canStart)
        cal.onAck(ack(r, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_CALIBRATED, roll: 1, pitch: -3, face: 1, y: 1))
        XCTAssertEqual(.IDLE, v.step)
        XCTAssertEqual("A saved calibration is in use. Board component side down, its Y arrow pointing aft. Roll now +0.5°, pitch -1.5°.", v.message)
        XCTAssertEqual(true, v.calibrated)
        XCTAssertTrue(v.canClose)
        let r2 = cal.check(nowMs: now)!
        cal.onAck(ack(r2, flags: P.IMUCAL_F_IMU_OK, face: 255, y: 255))
        XCTAssertTrue(v.message.hasPrefix("No saved calibration: the pod uses the mounting set in its firmware build. Roll now"))
        XCTAssertEqual(false, v.calibrated)
        let r3 = cal.check(nowMs: now)!
        cal.onAck(ack(r3, status: P.IMUCAL_BUSY))
        XCTAssertEqual(.IDLE, v.step) // busy is information, not a failure
        XCTAssertEqual("The pod is still measuring a calibration step.", v.message)
    }

    func testClearGoesBackToTheBuildSetting() {
        cal.onSteer(imuOk: true, imuCal: true)
        let r = cal.clear(nowMs: now)!
        XCTAssertEqual(P.IMUCAL_OP_CLEAR, r.op)
        XCTAssertEqual(.CLEARING, v.step)
        cal.onAck(ack(r, flags: P.IMUCAL_F_IMU_OK))
        XCTAssertEqual(.IDLE, v.step)
        XCTAssertEqual(false, v.calibrated)
        XCTAssertTrue(v.message.hasPrefix("Saved calibration cleared."))
    }

    func testCheckAndClearWaitForTheFlow() {
        _ = cal.start(nowMs: now)
        XCTAssertNil(cal.check(nowMs: now))
        XCTAssertNil(cal.clear(nowMs: now))
    }

    func testSteerBit6CountsOnlyWhileTheImuIsOk() {
        cal.onSteer(imuOk: false, imuCal: false)
        XCTAssertNil(v.calibrated)
        cal.onSteer(imuOk: true, imuCal: true)
        XCTAssertEqual(true, v.calibrated)
        XCTAssertTrue(v.calibratedFromSteer)
        XCTAssertEqual("IMU calibration: a saved calibration is in use (STEER).", BtrxImuCal.calibratedLine(v))
        cal.onSteer(imuOk: false, imuCal: false) // IMU dropped out: keep the last known
        XCTAssertEqual(true, v.calibrated)
        cal.onSteer(imuOk: true, imuCal: false)
        XCTAssertEqual(false, v.calibrated)
        // An ACK (even an unmatched one) is the newest word.
        XCTAssertFalse(cal.onAck(P.ImuCalAck(op: 3, tag: 99, status: 0, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_CALIBRATED, rollX2: 0, pitchX2: 0,
                                            tiltX2: 0, face: 0, yPoints: 0, gyroBiasX10: [0, 0, 0])))
        XCTAssertEqual(true, v.calibrated)
        XCTAssertFalse(v.calibratedFromSteer)
        XCTAssertEqual("IMU calibration: a saved calibration is in use (the pod's answer).", BtrxImuCal.calibratedLine(v))
        cal.reset()
        XCTAssertEqual("IMU calibration: not known yet (tap Check).", BtrxImuCal.calibratedLine(v))
    }

    func testLinkLostAndNotSentFailTheStep() {
        _ = cal.start(nowMs: now)
        cal.linkLost(reason: "Bluetooth off")
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertEqual("The link to the Desk Pod was lost (Bluetooth off).", v.message)
        XCTAssertFalse(v.waiting)
        cal.linkLost(reason: "again") // nothing out: no change
        XCTAssertEqual("The link to the Desk Pod was lost (Bluetooth off).", v.message)
        let r = cal.check(nowMs: now)!
        cal.notSent(BtrxImuCal.Request(op: r.op, tag: r.tag + 1), reason: "other") // not the one out
        XCTAssertEqual(.CHECKING, v.step)
        cal.notSent(nil, reason: "busy guard") // a request is out: not touched
        XCTAssertEqual(.CHECKING, v.step)
        cal.notSent(r, reason: "The Desk Pod is not connected.")
        XCTAssertEqual(.FAILED, v.step)
        XCTAssertEqual("The Desk Pod is not connected.", v.message)
        cal.close()
        cal.notSent(nil, reason: "No Drone Pod is paired. Pair it first.")
        XCTAssertEqual(.FAILED, v.step)
    }

    func testStartStarboardOnlyAfterLevel() {
        XCTAssertFalse(cal.startStarboard(nowMs: now))
        XCTAssertEqual(.IDLE, v.step)
    }

    func testEveryStatusHasPlainWords() {
        let texts = (0...9).map { BtrxImuCal.statusText($0) }
        XCTAssertEqual("OK.", texts[0])
        XCTAssertEqual(10, Set(texts).count)
        XCTAssertTrue(texts.dropFirst().allSatisfy { $0.hasSuffix(".") && $0.count > 20 })
        XCTAssertTrue(texts[P.IMUCAL_MOTION].contains("moved too much"))
        XCTAssertTrue(texts[P.IMUCAL_TILT_SMALL].contains("too small"))
        XCTAssertTrue(texts[P.IMUCAL_NOT_ROLL].contains("not a roll"))
        XCTAssertEqual("Unknown answer from the pod (status 42).", BtrxImuCal.statusText(42))
        XCTAssertEqual("Level step", BtrxImuCal.opLabel(P.IMUCAL_OP_LEVEL))
        XCTAssertEqual("Starboard step", BtrxImuCal.opLabel(P.IMUCAL_OP_STBD))
        XCTAssertFalse(BtrxImuCal.faceWords(0).isEmpty)
        XCTAssertEqual("to starboard", BtrxImuCal.yWords(3))
    }

    /// The same words as Android (BtrxImuCal.kt): spot-check a few shared strings.
    func testWordsMatchAndroid() {
        XCTAssertEqual("Hold the pod level and still. Measuring for about 2 seconds…", BtrxImuCal.holdLevel)
        XCTAssertEqual("Tip the starboard side down about 20° and hold it still.", BtrxImuCal.tipStbd)
        XCTAssertEqual(8_000, BtrxImuCal.ackTimeoutMs)
        XCTAssertEqual(3_000, BtrxImuCal.stbdCountdownMs)
    }
}
