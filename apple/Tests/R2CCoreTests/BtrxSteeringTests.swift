import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxSteeringTest.kt: hint derivation per btrx design doc section 41.2.
final class BtrxSteeringTests: XCTestCase {
    typealias P = BtrxProtocol
    typealias H = BtrxSteering.Hint

    var now: Int64 = 1_000_000
    var seq = 0
    var st = BtrxSteering()

    @discardableResult
    func steer(_ port: Int, _ stbd: Int, peak: Int? = nil, src: Int = 7, gated: Bool = false, imu: Bool = false, rollX2: Int = 0,
               target: Bool = true, devClass: Int = P.DEV_PHONE, muted: Bool = false) -> H? {
        var f = 0
        if target { f |= P.STEER_F_TARGET }
        if gated { f |= P.STEER_F_GATED }
        if imu { f |= P.STEER_F_IMU_OK }
        if muted { f |= P.STEER_F_MUTED }
        seq += 1
        let s = P.Steer(steerSeq: seq & 0xFF, flags: f, windowEndMs: 0, ageMs: 100, srcId: target ? src : 0,
                        rssiPort: target ? port : -128, rssiStbd: target ? stbd : -128, peakRssi: target ? (peak ?? max(port, stbd)) : -128,
                        hitsPort: 3, hitsStbd: 3, companyId: 0x4C, devClass: devClass, addrType: 1, macLsbFirst: [1, 2, 3, 4, 5, 6],
                        rollX2: rollX2, pitchX2: 0)
        st.onSteer(s, nowMs: now)
        return st.view(nowMs: now)?.hint
    }

    func tick(_ ms: Int64 = 1_000) { now += ms }
    var hint: H? { st.view(nowMs: now)?.hint }
    var vector: BtrxSteering.Vector? { st.view(nowMs: now)?.vector }

    func testSteerHintMatchesTheSimulatorOnTheFirmwareVector() throws {
        // test_protocol.py STEER_PAYLOAD: port -61, stbd -70 -> r2c_sim steer_hint "left".
        st.onSteer(try P.parseSteer(P.fromHex(BtrxProtocolTests.steerPayload)), nowMs: now)
        XCTAssertEqual(.left, hint)
        XCTAssertEqual(.hardLeft, vector) // 9 dB >= 8: hard left
    }

    func testEnterAtThreeLeaveOnlyBelowTwo() {
        XCTAssertEqual(.straight, steer(-60, -62))
        tick(); steer(-60, -63); tick(); steer(-60, -63)
        XCTAssertEqual(.left, hint)
        tick(); XCTAssertEqual(.left, steer(-60, -62)) // dead band holds LEFT
        tick(); XCTAssertEqual(.left, steer(-60, -61)) // 1 dB once: not yet
        tick(); XCTAssertEqual(.straight, steer(-60, -61))
    }

    func testASingleReportDoesNotFlipButTwoInARowDo() {
        steer(-60, -64); tick(); steer(-60, -64)
        XCTAssertEqual(.left, hint)
        tick(); XCTAssertEqual(.left, steer(-64, -60))
        tick(); XCTAssertEqual(.left, steer(-60, -64))
        tick(); XCTAssertEqual(.left, steer(-64, -60))
        tick(); XCTAssertEqual(.right, steer(-64, -60))
    }

    func testAStrongDifferenceFlipsAtOnce() {
        steer(-60, -64)
        XCTAssertEqual(.left, hint)
        tick(); XCTAssertEqual(.right, steer(-66, -60))
        XCTAssertEqual(.right, vector)
        tick(); XCTAssertEqual(.right, steer(-70, -60))
        XCTAssertEqual(.hardRight, vector)
    }

    func testHeardOnOnePatchOnlyIsThatSide() {
        XCTAssertEqual(.right, steer(P.rssiNotHeard, -80))
        XCTAssertEqual(.hardRight, vector)
        tick(); XCTAssertEqual(.left, steer(-80, P.rssiNotHeard))
    }

    func testRollIsCorrectedOnlyWithTheImuFlag() {
        XCTAssertEqual(.straight, steer(-60, -62, rollX2: 20))
        tick(); steer(-60, -62, imu: true, rollX2: 20); tick()
        XCTAssertEqual(.left, steer(-60, -62, imu: true, rollX2: 20))
        func s(_ roll: Int) -> P.Steer {
            P.Steer(steerSeq: 0, flags: P.STEER_F_TARGET | P.STEER_F_IMU_OK, windowEndMs: 0, ageMs: 0, srcId: 1, rssiPort: -60, rssiStbd: -62,
                    peakRssi: -60, hitsPort: 1, hitsStbd: 1, companyId: 0, devClass: 0, addrType: 0, macLsbFirst: [0, 0, 0, 0, 0, 0], rollX2: roll, pitchX2: 0)
        }
        XCTAssertEqual(3.2, BtrxSteering.deltaDb(s(20)), accuracy: 1e-9)
        XCTAssertEqual(0.8, BtrxSteering.deltaDb(s(-20)), accuracy: 1e-9)
    }

    func testCloserAndFartherComeFromThePeakSlopeWhileStraight() {
        steer(-70, -70, peak: -70); tick()
        steer(-69, -69, peak: -69); tick()
        XCTAssertEqual(.closer, steer(-68, -68, peak: -68))
        XCTAssertEqual(.ahead, vector)
        tick(); XCTAssertEqual(.closer, steer(-68, -68, peak: -68))
        tick(); steer(-68, -68, peak: -68)
        tick(); steer(-68, -68, peak: -68)
        tick(); XCTAssertEqual(.straight, steer(-68, -68, peak: -68))
        tick(); steer(-69, -69, peak: -69); tick(); steer(-70, -70, peak: -70); tick()
        XCTAssertEqual(.farther, steer(-71, -71, peak: -71))
        XCTAssertEqual(.behind, vector)
    }

    func testSlopeIsLeastSquares() {
        XCTAssertEqual(1.0, BtrxSteering.slope([-70, -69, -68]), accuracy: 1e-9)
        XCTAssertEqual(-0.5, BtrxSteering.slope([-60, -60, -61, -61, -62]), accuracy: 1e-9)
        XCTAssertEqual(0.0, BtrxSteering.slope([-60]), accuracy: 1e-9)
    }

    func testGatedShowsLevelInGrayAndHoldsTheLastHint() {
        steer(-60, -66)
        XCTAssertEqual(.left, hint)
        tick(); XCTAssertEqual(.level, steer(-66, -60, gated: true))
        let v = st.view(nowMs: now)!
        XCTAssertTrue(v.gray)
        XCTAssertEqual(.left, v.vector)
        tick(); XCTAssertEqual(.left, steer(-60, -64))
    }

    func testLostAfterFiveSecondsWithoutATarget() {
        steer(-60, -66)
        tick(); XCTAssertEqual(.left, steer(0, 0, target: false))
        XCTAssertTrue(st.view(nowMs: now)!.faded)
        tick(3_999); XCTAssertEqual(.left, hint)
        tick(1); XCTAssertEqual(.lost, hint)
        let v = st.view(nowMs: now)!
        XCTAssertNil(v.vector)
        XCTAssertTrue(v.faded)
        XCTAssertFalse(v.canMute)
        tick(BtrxSteering.showMs - 4_000 + 1)
        XCTAssertNil(st.view(nowMs: now))
        XCTAssertEqual(.lost, st.lastView(nowMs: now)?.hint)
    }

    func testANewSourceStartsFresh() {
        steer(-60, -66, src: 7)
        XCTAssertEqual(.left, hint)
        tick(); XCTAssertEqual(.straight, steer(-62, -61, src: 9))
        XCTAssertEqual(9, st.view(nowMs: now)?.srcId)
        tick(); XCTAssertEqual(.straight, steer(-60, -63, src: 9))
    }

    func testColorsAreStableAndWrapAfterEight() {
        let ids = [5, 9, 11, 12, 40, 41, 42, 43, 44]
        let first = ids.map { st.colorFor($0) }
        XCTAssertEqual([0, 1, 2, 3, 4, 5, 6, 7, 0], first)
        XCTAssertEqual(first, ids.map { st.colorFor($0) })
        XCTAssertNil(st.colorFor(0))
        XCTAssertEqual(8, BtrxSteering.palette.count)
        XCTAssertFalse(BtrxSteering.palette.contains(BtrxSteering.gray))
        st.reset()
        XCTAssertEqual(0, st.colorFor(44))
    }

    func testTheViewCarriesKindMutedAndColor() {
        steer(-60, -60, src: 3, devClass: P.DEV_TRACKER, muted: true)
        let v = st.view(nowMs: now)!
        XCTAssertEqual(P.DEV_TRACKER, v.devClass)
        XCTAssertTrue(v.muted)
        XCTAssertEqual(0, v.colorIndex)
        XCTAssertTrue(v.canMute)
    }
}
