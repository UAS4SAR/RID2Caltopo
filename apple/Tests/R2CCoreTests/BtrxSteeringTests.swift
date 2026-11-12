import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxSteeringTest.kt: hint derivation per btrx design doc section 41.2.
final class BtrxSteeringTests: XCTestCase {
    typealias P = BtrxProtocol
    typealias H = BtrxSteering.Hint

    var now: Int64 = 1_000_000
    /// The pod's clock (window_end_ms), advancing with `now`.
    var podMs: UInt32 = 50_000
    var seq = 0
    var st = BtrxSteering()

    @discardableResult
    func steer(_ port: Int, _ stbd: Int, peak: Int? = nil, src: Int = 7, gated: Bool = false, imu: Bool = false, rollX2: Int = 0,
               target: Bool = true, devClass: Int = P.DEV_PHONE, muted: Bool = false, hitsPort: Int = 3, hitsStbd: Int = 3) -> H? {
        var f = 0
        if target { f |= P.STEER_F_TARGET }
        if gated { f |= P.STEER_F_GATED }
        if imu { f |= P.STEER_F_IMU_OK }
        if muted { f |= P.STEER_F_MUTED }
        seq += 1
        let s = P.Steer(steerSeq: seq & 0xFF, flags: f, windowEndMs: podMs, ageMs: 100, srcId: target ? src : 0,
                        rssiPort: target ? port : -128, rssiStbd: target ? stbd : -128, peakRssi: target ? (peak ?? max(port, stbd)) : -128,
                        hitsPort: target ? hitsPort : 0, hitsStbd: target ? hitsStbd : 0, companyId: 0x4C, devClass: devClass, addrType: 1,
                        macLsbFirst: [1, 2, 3, 4, 5, 6], rollX2: rollX2, pitchX2: 0)
        st.onSteer(s, nowMs: now)
        return st.view(nowMs: now)?.hint
    }

    func tick(_ ms: Int64 = 1_000) { now += ms; podMs += UInt32(ms) }
    var hint: H? { st.view(nowMs: now)?.hint }
    var vector: BtrxSteering.Vector? { st.view(nowMs: now)?.vector }

    func plain(_ seq: Int, src: Int, windowEndMs: UInt32) -> P.Steer {
        P.Steer(steerSeq: seq, flags: P.STEER_F_TARGET, windowEndMs: windowEndMs, ageMs: 0, srcId: src, rssiPort: -62, rssiStbd: -61,
                peakRssi: -61, hitsPort: 3, hitsStbd: 3, companyId: 0, devClass: P.DEV_PHONE, addrType: 1, macLsbFirst: [0, 0, 0, 0, 0, 0],
                rollX2: 0, pitchX2: 0)
    }

    func testSteerHintMatchesTheSimulatorOnTheFirmwareVector() throws {
        st.onSteer(try P.parseSteer(P.fromHex(BtrxProtocolTests.steerPayload)), nowMs: now)
        XCTAssertEqual(.left, hint)
        XCTAssertEqual(.hardLeft, vector)
    }

    func testEnterAtThreeLeaveOnlyBelowTwo() {
        XCTAssertEqual(.straight, steer(-60, -62))
        tick(); steer(-60, -63); tick(); steer(-60, -63)
        XCTAssertEqual(.left, hint)
        tick(); XCTAssertEqual(.left, steer(-60, -62))
        tick(); XCTAssertEqual(.left, steer(-60, -61))
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

    func testHeardOnOnePatchWithTwoHitsIsThatSideAtOnceAndHard() {
        XCTAssertEqual(.right, steer(P.rssiNotHeard, -80, imu: true, hitsPort: 0, hitsStbd: 2))
        XCTAssertEqual(.hardRight, vector)
        tick(); XCTAssertEqual(.left, steer(-80, P.rssiNotHeard, hitsPort: 2, hitsStbd: 0))
        XCTAssertEqual(.hardLeft, vector)
    }

    func testPortBoardSilentIsNoDirectionNotAHardRight() {
        // A5 bench 10:45:26: port -128, hits 0/7, IMU-ok clear (pod B off the link), stbd -24.
        XCTAssertEqual(.noPort, steer(P.rssiNotHeard, -24, hitsPort: 0, hitsStbd: 7))
        let v = st.view(nowMs: now)!
        XCTAssertTrue(v.gray)
        XCTAssertEqual(.ahead, v.vector) // nothing learnt: no side
        tick(); XCTAssertEqual(.left, steer(-60, -66, imu: true))
        for _ in 0..<3 { tick(); XCTAssertEqual(.noPort, steer(P.rssiNotHeard, -24, hitsPort: 0, hitsStbd: 7)) }
        XCTAssertEqual(.left, st.view(nowMs: now)?.vector)
        tick(); XCTAssertEqual(.left, steer(-60, -66, imu: true))
    }

    func testHeardOnOnePatchWithOneHitNeedsConfirming() {
        steer(-60, -64)
        XCTAssertEqual(.left, hint)
        tick(); XCTAssertEqual(.left, steer(P.rssiNotHeard, -80, imu: true, hitsPort: 0, hitsStbd: 1))
        tick(); XCTAssertEqual(.right, steer(P.rssiNotHeard, -80, imu: true, hitsPort: 0, hitsStbd: 1))
        XCTAssertEqual(.right, vector)
        tick(); steer(P.rssiNotHeard, -80, imu: true, hitsPort: 0, hitsStbd: 2)
        XCTAssertEqual(.hardRight, vector)
    }

    func testRollCorrectionIsOffByDefault() {
        XCTAssertFalse(BtrxSteering.rollCorrectionDefault)
        XCTAssertFalse(st.rollCorrection)
        steer(-60, -62, imu: true, rollX2: 20); tick()
        XCTAssertEqual(.straight, steer(-60, -62, imu: true, rollX2: 20))
    }

    func testRollIsCorrectedWhenEnabledAndOnlyWithTheImuFlag() {
        st.rollCorrection = true
        XCTAssertEqual(.straight, steer(-60, -62, rollX2: 20))
        tick(); steer(-60, -62, imu: true, rollX2: 20); tick()
        XCTAssertEqual(.left, steer(-60, -62, imu: true, rollX2: 20))
        func s(_ roll: Int) -> P.Steer {
            P.Steer(steerSeq: 0, flags: P.STEER_F_TARGET | P.STEER_F_IMU_OK, windowEndMs: 0, ageMs: 0, srcId: 1, rssiPort: -60, rssiStbd: -62,
                    peakRssi: -60, hitsPort: 1, hitsStbd: 1, companyId: 0, devClass: 0, addrType: 0, macLsbFirst: [0, 0, 0, 0, 0, 0], rollX2: roll, pitchX2: 0)
        }
        XCTAssertEqual(4.0, BtrxSteering.deltaDb(s(20), rollCorrection: true), accuracy: 1e-9)
        XCTAssertEqual(0.0, BtrxSteering.deltaDb(s(-20), rollCorrection: true), accuracy: 1e-9)
        XCTAssertEqual(2.0, BtrxSteering.deltaDb(s(20), rollCorrection: false), accuracy: 1e-9)
        XCTAssertEqual(0.2, BtrxSteering.rollDbPerDeg)
    }

    @discardableResult
    func ramp(_ start: Int, _ step: Int, n: Int = 6) -> H? {
        var h: H?
        for i in 0..<n {
            h = steer(-60, -60, peak: start + i * step)
            if i < n - 1 { tick() }
        }
        return h
    }

    func testCloserNeedsSixReportsAndASignificantSlopeAgainstPodTime() {
        steer(-60, -60, peak: -75); tick()
        steer(-60, -60, peak: -74); tick()
        steer(-60, -60, peak: -73); tick()
        steer(-60, -60, peak: -72); tick()
        XCTAssertEqual(.straight, steer(-60, -60, peak: -71))
        tick(); XCTAssertEqual(.closer, steer(-60, -60, peak: -70))
        XCTAssertEqual(.ahead, vector)
    }

    func testFartherPointsBack() {
        XCTAssertEqual(.farther, ramp(-60, -1))
        XCTAssertEqual(.behind, vector)
    }

    func testTheSlopeIsPerSecondOfPodTimeNotPerReport() {
        for i in 0..<6 {
            steer(-60, -60, peak: -75 + i)
            if i < 5 { now += 1_000; podMs += 3_000 }
        }
        XCTAssertEqual(.straight, hint)
    }

    func testANoisySlopeIsNotCalled() throws {
        let peaks = [-72, -62, -72, -62, -70, -63]
        for (i, p) in peaks.enumerated() { steer(-60, -60, peak: p); if i < peaks.count - 1 { tick() } }
        let fit = try XCTUnwrap(BtrxSteering.fit(peaks.enumerated().map { (UInt32($0.offset * 1000), $0.element) }))
        XCTAssertGreaterThanOrEqual(fit.slopeDbPerS, 0.5)
        XCTAssertLessThan(fit.slopeDbPerS, 2 * fit.stdErr)
        XCTAssertEqual(.straight, hint)
    }

    func testCloserClearsBelowAQuarterDbPerSecond() throws {
        XCTAssertEqual(.closer, ramp(-75, 1))
        var h: H? = .closer
        var n = 0
        while h == .closer && n < 15 { tick(); h = steer(-60, -60, peak: -70); n += 1 }
        XCTAssertEqual(.straight, h)
        let fit = try XCTUnwrap(BtrxSteering.fit([(0, -70), (1000, -70), (2000, -70)]))
        XCTAssertEqual(0.0, fit.slopeDbPerS, accuracy: 1e-9)
    }

    func testOnlyTheLastTenSecondsOfPodTimeCount() {
        XCTAssertEqual(.closer, ramp(-75, 1))
        tick(11_000)
        XCTAssertEqual(.straight, steer(-60, -60, peak: -70))
    }

    func testGatedAndEmptyReportsAreLeftOutOfTheFit() {
        steer(-60, -60, peak: -75); tick()
        steer(-60, -60, peak: -74); tick()
        steer(-60, -60, peak: -20, gated: true); tick()
        steer(0, 0, target: false); tick()
        steer(-60, -60, peak: -73); tick()
        steer(-60, -60, peak: -72); tick()
        steer(-60, -60, peak: -71); tick()
        XCTAssertEqual(.closer, steer(-60, -60, peak: -70))
    }

    func testFitGivesSlopeInDbPerSecondAndStandardError() throws {
        let exact = try XCTUnwrap(BtrxSteering.fit([(0, -70), (1000, -69), (2000, -68), (3000, -67)]))
        XCTAssertEqual(1.0, exact.slopeDbPerS, accuracy: 1e-9)
        XCTAssertEqual(0.0, exact.stdErr, accuracy: 1e-9)
        let half = try XCTUnwrap(BtrxSteering.fit([(0, -60), (2000, -61), (4000, -62)]))
        XCTAssertEqual(-0.5, half.slopeDbPerS, accuracy: 1e-9)
        XCTAssertNil(BtrxSteering.fit([(0, -60), (1000, -60)]))
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

    func testNoReportsForTwoSecondsFadesButKeepsTheHint() {
        steer(-60, -66)
        XCTAssertFalse(st.view(nowMs: now)!.faded)
        now += 1_999
        XCTAssertFalse(st.view(nowMs: now)!.faded)
        now += 1
        let v = st.view(nowMs: now)!
        XCTAssertTrue(v.faded)
        XCTAssertEqual(.left, v.hint)
        XCTAssertNotNil(v.vector)
        now += 3_000
        XCTAssertEqual(.lost, hint)
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

    func testPodRestartResetsColorsHintAndMutedFlag() {
        steer(-60, -66, src: 1, muted: true)
        tick(); steer(-60, -66, src: 2, muted: true)
        XCTAssertEqual(1, st.view(nowMs: now)?.colorIndex)
        XCTAssertEqual(true, st.view(nowMs: now)?.muted)
        tick()
        podMs = 3_000
        XCTAssertTrue(st.onSteer(plain(1, src: 2, windowEndMs: podMs), nowMs: now))
        let v = st.view(nowMs: now)!
        XCTAssertEqual(0, v.colorIndex)
        XCTAssertEqual(.straight, v.hint)
        XCTAssertFalse(v.muted)
        XCTAssertEqual(1, st.restarts)
        tick(); XCTAssertFalse(st.onSteer(plain(2, src: 2, windowEndMs: podMs), nowMs: now))
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

    // MARK: which tile (the drone carrying the pod)

    func testHiddenOverlayShowsOnlyTheBtrxHiddenChip() {
        func mode(_ tile: String, _ live: Set<String>, _ chosen: String?, _ hasView: Bool, shown: Bool) -> BtrxSteeringTarget.TileOverlay {
            BtrxSteeringTarget.tileOverlay(tile: tile, live: live, chosen: chosen, hasView: hasView, shown: shown)
        }
        XCTAssertEqual(.card, mode("A", ["A"], nil, true, shown: true))
        XCTAssertEqual(.hiddenChip, mode("A", ["A"], nil, true, shown: false))
        XCTAssertEqual(BtrxSteeringTarget.TileOverlay.none, mode("A", ["A"], nil, false, shown: false))
        XCTAssertEqual(.pick, mode("A", ["A", "B"], nil, true, shown: true))
        XCTAssertEqual(.hiddenChip, mode("A", ["A", "B"], nil, true, shown: false))
        XCTAssertEqual(BtrxSteeringTarget.TileOverlay.none, mode("B", ["A", "B"], "A", true, shown: true))
        XCTAssertEqual(BtrxSteeringTarget.TileOverlay.none, mode("B", ["A", "B"], "A", true, shown: false))
    }

    func testOneLiveStreamShowsTheOverlayWithoutAPick() {
        let live: Set = ["A"]
        XCTAssertTrue(BtrxSteeringTarget.showsOverlay(tile: "A", live: live, chosen: nil))
        XCTAssertFalse(BtrxSteeringTarget.offersPick(tile: "A", live: live, chosen: nil))
        XCTAssertFalse(BtrxSteeringTarget.showsOverlay(tile: "B", live: live, chosen: nil))
    }

    func testSeveralLiveStreamsNeedAPick() {
        let live: Set = ["A", "B"]
        XCTAssertFalse(BtrxSteeringTarget.showsOverlay(tile: "A", live: live, chosen: nil))
        XCTAssertTrue(BtrxSteeringTarget.offersPick(tile: "A", live: live, chosen: nil))
        XCTAssertTrue(BtrxSteeringTarget.offersPick(tile: "B", live: live, chosen: nil))
        XCTAssertTrue(BtrxSteeringTarget.showsOverlay(tile: "B", live: live, chosen: "B"))
        XCTAssertFalse(BtrxSteeringTarget.showsOverlay(tile: "A", live: live, chosen: "B"))
        XCTAssertFalse(BtrxSteeringTarget.offersPick(tile: "A", live: live, chosen: "B"))
    }

    func testTheChoiceEndsWithItsStream() {
        XCTAssertEqual("B", BtrxSteeringTarget.retained("B", live: ["A", "B"]))
        XCTAssertNil(BtrxSteeringTarget.retained("B", live: ["A"]))
        XCTAssertNil(BtrxSteeringTarget.retained(nil, live: ["A"]))
        XCTAssertTrue(BtrxSteeringTarget.showsOverlay(tile: "A", live: ["A"], chosen: BtrxSteeringTarget.retained("B", live: ["A"])))
    }

    // MARK: Arrow (Ken, Nov 12 2026): direction and length instead of words. Same cases as Android.

    typealias V = BtrxSteering.Vector
    var len: Double { st.view(nowMs: now)!.length }

    func testArrowPointsUpForAheadSidewaysForATurnAndFlatForAHardTurn() {
        let ahead = BtrxSteering.arrowGeometry(.ahead, length: 0).tip
        XCTAssertEqual(0, ahead.x, accuracy: 1e-9); XCTAssertLessThan(ahead.y, 0)
        let left = BtrxSteering.arrowGeometry(.left, length: 0).tip
        XCTAssertTrue(left.x < 0 && left.y < 0); XCTAssertEqual(-left.x, -left.y, accuracy: 1e-9)
        let right = BtrxSteering.arrowGeometry(.right, length: 0).tip
        XCTAssertTrue(right.x > 0 && right.y < 0); XCTAssertEqual(right.x, -right.y, accuracy: 1e-9)
        let hardLeft = BtrxSteering.arrowGeometry(.hardLeft, length: 0).tip
        XCTAssertLessThan(hardLeft.x, 0); XCTAssertEqual(0, hardLeft.y, accuracy: 1e-9)
        let hardRight = BtrxSteering.arrowGeometry(.hardRight, length: 0).tip
        XCTAssertGreaterThan(hardRight.x, 0); XCTAssertEqual(0, hardRight.y, accuracy: 1e-9)
    }

    func testBehindIsATurnAroundArrowWhoseHeadPointsDown() {
        let a = BtrxSteering.arrowGeometry(.behind, length: 0.5)
        XCTAssertLessThan(a.shaft[1].y, a.shaft[0].y)
        XCTAssertLessThan(a.shaft.map(\.y).min()!, -BtrxSteering.markCircleR)
        XCTAssertGreaterThan(a.tip.y, a.head[1].y)
        XCTAssertGreaterThan(a.tip.x, 0)
        let all: [V] = [.hardLeft, .left, .ahead, .right, .hardRight, .behind]
        for l in [0.0, 1.0] { for v in all {
            let g = BtrxSteering.arrowGeometry(v, length: l, closer: true)
            for p in g.shaft + g.head + g.chevron { XCTAssertTrue(abs(p.x) <= 1 && abs(p.y) <= 1, "\(v) \(l) \(p)") }
        } }
    }

    func testArrowIsLongerWhenTheSourceIsFarther() {
        XCTAssertEqual(BtrxSteering.arrowMinReach, BtrxSteering.arrowReach(0), accuracy: 1e-9)
        XCTAssertEqual(BtrxSteering.arrowMaxReach, BtrxSteering.arrowReach(1), accuracy: 1e-9)
        for v in [V.hardLeft, .left, .ahead, .right, .hardRight] {
            let near = BtrxSteering.arrowGeometry(v, length: 0).tip, far = BtrxSteering.arrowGeometry(v, length: 1).tip
            XCTAssertGreaterThan(far.x * far.x + far.y * far.y, near.x * near.x + near.y * near.y)
        }
        XCTAssertLessThan(BtrxSteering.arrowGeometry(.behind, length: 1).shaft.map(\.y).min()!,
                          BtrxSteering.arrowGeometry(.behind, length: 0).shaft.map(\.y).min()!)
    }

    func testLengthIsLinearInTheSumSignalBetweenMinus50AndMinus100() {
        XCTAssertEqual(0, BtrxSteering.lengthFor(-40), accuracy: 1e-9)
        XCTAssertEqual(0, BtrxSteering.lengthFor(-50), accuracy: 1e-9)
        XCTAssertEqual(0.5, BtrxSteering.lengthFor(-75), accuracy: 1e-9)
        XCTAssertEqual(1, BtrxSteering.lengthFor(-100), accuracy: 1e-9)
        XCTAssertEqual(1, BtrxSteering.lengthFor(-115), accuracy: 1e-9)
    }

    func testSumSignalIsTheStrongerPatchMean() {
        steer(-70, -80, peak: -60)
        XCTAssertEqual(BtrxSteering.lengthFor(-70), len, accuracy: 1e-9)
        let one = P.Steer(steerSeq: 1, flags: P.STEER_F_TARGET, windowEndMs: 1, ageMs: 0, srcId: 9, rssiPort: -128, rssiStbd: -90,
                          peakRssi: -85, hitsPort: 0, hitsStbd: 2, companyId: 0, devClass: 0, addrType: 1, macLsbFirst: [0, 0, 0, 0, 0, 0],
                          rollX2: 0, pitchX2: 0)
        XCTAssertEqual(-90, BtrxSteering.sumDbm(one)!, accuracy: 1e-9)
        let peakOnly = P.Steer(steerSeq: 1, flags: P.STEER_F_TARGET, windowEndMs: 1, ageMs: 0, srcId: 9, rssiPort: -128, rssiStbd: -128,
                               peakRssi: -85, hitsPort: 0, hitsStbd: 0, companyId: 0, devClass: 0, addrType: 1, macLsbFirst: [0, 0, 0, 0, 0, 0],
                               rollX2: 0, pitchX2: 0)
        XCTAssertEqual(-85, BtrxSteering.sumDbm(peakOnly)!, accuracy: 1e-9)
    }

    func testLengthIsSmoothedAndSlewLimited() {
        steer(-95, -95)
        XCTAssertEqual(0.9, len, accuracy: 1e-9)
        tick(); steer(-50, -50)
        XCTAssertEqual(0.8, len, accuracy: 1e-9)
        tick(); steer(-50, -50)
        XCTAssertEqual(0.7, len, accuracy: 1e-9)
        XCTAssertEqual(0.73, BtrxSteering.smoothLength(0.7, 0.8, dtS: 1), accuracy: 1e-9)
        XCTAssertEqual(0.5, BtrxSteering.smoothLength(nil, 0.5, dtS: 1), accuracy: 1e-9)
    }

    func testLengthFreezesWhileGatedOrFadedAndRestartsWithANewSource() {
        steer(-60, -60)
        let l = len
        tick(); steer(-95, -95, gated: true)
        XCTAssertEqual(l, len, accuracy: 1e-9)
        tick(); steer(-95, -95, target: false)
        XCTAssertEqual(l, len, accuracy: 1e-9)
        tick(); steer(-95, -95, src: 8)
        XCTAssertEqual(0.9, len, accuracy: 1e-9)
    }

    func testCloserShowsTheChevronAndFartherTheTurnAround() {
        XCTAssertEqual(.closer, ramp(-75, 1))
        let v = st.view(nowMs: now)!
        XCTAssertTrue(v.closer)
        XCTAssertEqual(3, BtrxSteering.arrowGeometry(v.vector!, length: v.length, closer: v.closer).chevron.count)
        XCTAssertTrue(BtrxSteering.arrowGeometry(.ahead, length: 0.5).chevron.isEmpty)
        st.reset()
        tick(); XCTAssertEqual(.farther, ramp(-60, -1))
        XCTAssertFalse(st.view(nowMs: now)!.closer)
        XCTAssertEqual(.behind, vector)
    }

    func testPortStrongerPointsTheArrowLeft() {
        // Bench role check: the Desk Pod's BLE right in front of the port patch.
        steer(-45, -72, hitsPort: 8, hitsStbd: 3)
        XCTAssertEqual(.left, hint)
        let v = st.view(nowMs: now)!
        XCTAssertLessThan(BtrxSteering.arrowGeometry(v.vector!, length: v.length).tip.x, 0)
        XCTAssertEqual(.hardLeft, v.vector)
        XCTAssertEqual(0, v.length, accuracy: 1e-9)
    }
}

