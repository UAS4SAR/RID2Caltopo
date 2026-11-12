import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxDeskPodModelTest.kt: the desk window (backlog open=1 inside
/// its window, expired, open=0), the "Pair drone pod" flow and link health.
final class BtrxDeskPodModelTests: XCTestCase {
    typealias P = BtrxProtocol
    typealias Model = BtrxDeskPodModel

    final class FakeSender: BtrxFrameSender {
        var isReady = true
        var seq = 0
        var sent: [(UInt32, [UInt8])] = []
        func nextSeq() -> Int { seq += 1; return seq }
        func sendFrame(to: UInt32, frame: [UInt8]) { sent.append((to, frame)) }
    }

    var now: Int64 = 100_000
    let log = BtrxTestLogger()
    var paired: [Model.PairedPod] = []
    var model: Model!
    let sender = FakeSender()
    let desk: UInt32 = 0x66C8_42CA
    let pod: UInt32 = 0x0189_68BE
    var deskSeq = 0
    var podSeq = 0

    override func setUp() {
        super.setUp()
        model = Model(log: log, clock: { [unowned self] in self.now }, onPaired: { [unowned self] in self.paired.append($0) })
        model.attach(sender)
        model.onConfigured(localNode: desk)
    }

    func meta(_ from: UInt32, backlog: Bool = false) -> BtrxRadioMeta {
        BtrxRadioMeta(fromNode: from, toNode: from == desk ? desk : 0xFFFF_FFFF, rxRssi: -45, rxSnr: 9.5, arrivalMs: now, backlog: backlog)
    }

    func deskPair(_ open: Int, seconds: Int = 60, backlog: Bool = false, seq: Int? = nil) {
        let s: Int
        if let seq { s = seq } else { deskSeq += 1; s = deskSeq }
        let frame = try! P.decodeFrame(P.encodeFrame(P.DESK_PAIR, seq: s, payload: [UInt8(open), 1, UInt8(seconds & 0xFF), UInt8(seconds >> 8)]))
        model.onFrame(frame, meta: meta(desk, backlog: backlog))
    }

    func pairReq(nonce: String = "570b73e9475dfe3f", from: UInt32? = nil, backlog: Bool = false, imuOk: UInt8 = 1, devB: UInt32 = 0x351C_2971) {
        var p = [UInt8](repeating: 0, count: 27)
        P.putU32(&p, 4, 0x4C8C_125B); P.putU32(&p, 8, devB)
        p[13] = 1; p[15] = 1
        p[16] = imuOk; p[17] = 2; p[18] = devB == 0 ? 0 : 1
        p.replaceSubrange(19..<27, with: P.fromHex(nonce))
        podSeq += 1
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.PAIR_REQ, seq: podSeq, payload: p)), meta: meta(from ?? pod, backlog: backlog))
    }

    func pairAck(_ a: UInt8 = 1, _ b: UInt8 = 1) {
        podSeq += 1
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.PAIR_ACK, seq: podSeq, flags: P.F_IS_ACK, payload: [a, b, 0, 0])), meta: meta(pod))
    }

    func advance(_ ms: Int64) { now += ms; model.tick() }
    var step: Model.PairStep { model.snapshot().pairing.step }
    func example(_ name: String) -> P.Frame { try! P.decodeFrame(BtrxProtocolTests.example(name)) }

    // MARK: the desk window

    func testBacklogOpenInsideItsWindowCounts() {
        deskPair(1, backlog: true)
        advance(5_000)
        model.startPairing()
        XCTAssertEqual(.waitNod, step)
        XCTAssertEqual(55, model.snapshot().pairing.windowSecondsLeft)
        XCTAssertTrue(model.snapshot().windowFromBacklog)
    }

    func testBacklogOpenFollowedByBacklogCloseIsClosed() {
        deskPair(1, backlog: true)
        deskPair(0, backlog: true)
        model.startPairing()
        XCTAssertEqual(.waitButton, step)
        XCTAssertFalse(model.snapshot().windowOpen)
    }

    func testExpiredWindowIsClosed() {
        deskPair(1, seconds: 60)
        advance(60_000)
        XCTAssertFalse(model.snapshot().windowOpen)
        model.startPairing()
        XCTAssertEqual(.waitButton, step)
    }

    func testRepeatOfTheSameOpenFrameDoesNotExtendTheWindow() {
        deskPair(1, seq: 40)
        advance(30_000)
        deskPair(1, seq: 40)
        XCTAssertEqual(30, model.snapshot().windowSecondsLeft)
        deskPair(1, seq: 41)
        XCTAssertEqual(60, model.snapshot().windowSecondsLeft)
    }

    func testSecondsZeroMeansSixty() {
        var w = BtrxDeskWindow()
        w.onDeskPair(seq: 1, P.DeskPair(open: 1, source: 1, secondsLeft: 0), arrivalMs: 0, backlog: false)
        XCTAssertEqual(60, w.secondsLeft(nowMs: 0))
        XCTAssertTrue(w.isOpen(nowMs: 59_999))
        XCTAssertFalse(w.isOpen(nowMs: 60_000))
        XCTAssertEqual(.expired, w.lastClose)
    }

    // MARK: the pairing flow

    func testFullPairingFlowSendsPairConfirmWithANonZeroPodId() throws {
        model.startPairing()
        XCTAssertEqual(.waitButton, step)
        deskPair(1)
        XCTAssertEqual(.waitNod, step)
        pairReq()
        let s = model.snapshot().pairing
        XCTAssertEqual(.podFound, s.step)
        XCTAssertEqual(pod, s.candidate?.node)
        XCTAssertTrue(sender.sent.isEmpty)
        model.confirmPod()
        XCTAssertEqual(.confirming, step)
        XCTAssertEqual(1, sender.sent.count)
        let (to, frame) = sender.sent[0]
        XCTAssertEqual(pod, to)
        let f = try P.decodeFrame(frame)
        XCTAssertEqual(P.PAIR_CONFIRM, f.type)
        XCTAssertEqual(0x45D3_5AF0, f.podId)
        XCTAssertEqual(0x45D3_5AF0, P.u32(f.payload, 0))
        XCTAssertEqual("570b73e9475dfe3f", P.hex(Array(f.payload[4..<12])))
        XCTAssertEqual(1, f.payload[14])
        XCTAssertEqual(0, f.payload[15])
        XCTAssertEqual(0, f.payload[16])
        pairAck(1, 1)
        let done = model.snapshot()
        XCTAssertEqual(.paired, done.pairing.step)
        XCTAssertEqual("Drone Pod paired.", done.pairing.message)
        XCTAssertEqual(Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971), done.pairedPod)
        XCTAssertEqual(1, paired.count)
        XCTAssertTrue(BtrxDeskPodText.steps(done.pairing).allSatisfy { $0.status == .done })
    }

    func testPortBoardMissingIsShownAfterPairing() {
        model.startPairing(); deskPair(1); pairReq(devB: 0)
        XCTAssertTrue(model.snapshot().pairing.candidate!.warnings.contains { $0.contains("port board") })
        model.confirmPod(); pairAck(1, 0)
        XCTAssertTrue(model.snapshot().pairing.message.hasPrefix("Paired, but the port board did not answer"))
    }

    func testPairReqWithoutAnOpenWindowIsIgnored() {
        pairReq()
        model.startPairing()
        pairReq()
        XCTAssertEqual(.waitButton, step)
        XCTAssertTrue(log.infos.contains { $0.contains("no Desk Pod window open") })
    }

    func testBacklogPairReqIsNeverActedOn() {
        model.startPairing(); deskPair(1)
        pairReq(backlog: true)
        XCTAssertEqual(.waitNod, step)
        pairReq()
        XCTAssertEqual(.podFound, step)
    }

    func testDeskPairCloseEndsTheNodWaitWithAFailure() {
        model.startPairing(); deskPair(1)
        deskPair(0)
        let p = model.snapshot().pairing
        XCTAssertEqual(.failed, p.step)
        XCTAssertEqual(.waitNod, p.failedAt)
        XCTAssertTrue(p.message.contains("closed its pairing window"))
        XCTAssertEqual([.done, .failed, .pending, .pending], BtrxDeskPodText.steps(p).map(\.status))
    }

    func testDeskWindowEndingDoesNotFailWhileThePodKeepsAsking() {
        model.startPairing(); deskPair(1, seconds: 10); pairReq()
        for _ in 0..<6 { advance(2_000); pairReq() }
        deskPair(0)
        XCTAssertEqual(.podFound, step)
        XCTAssertFalse(model.snapshot().pairing.podStale)
        model.confirmPod()
        XCTAssertEqual(.confirming, step)
        XCTAssertEqual(1, sender.sent.count)
    }

    func testPodThatStopsAskingIsMarkedStaleAndNotConfirmed() throws {
        model.startPairing(); deskPair(1); pairReq()
        advance(6_000) // three missed 2 s repeats are not stale yet (threshold 7 s)
        XCTAssertFalse(model.snapshot().pairing.podStale)
        advance(Model.podRequestStaleMs - 6_000 + 1_000)
        var p = model.snapshot().pairing
        XCTAssertEqual(.podFound, p.step)
        XCTAssertTrue(p.podStale)
        XCTAssertEqual(Model.staleMessage, p.message)
        XCTAssertTrue(BtrxDeskPodText.steps(p)[2].detail.hasSuffix(BtrxDeskPodText.podStaleHint))
        model.confirmPod()
        XCTAssertTrue(sender.sent.isEmpty)
        XCTAssertEqual(.podFound, step)
        pairReq(nonce: "0102030405060708")
        p = model.snapshot().pairing
        XCTAssertFalse(p.podStale)
        model.confirmPod()
        XCTAssertEqual(1, sender.sent.count)
        XCTAssertEqual("0102030405060708", P.hex(Array(try P.decodeFrame(sender.sent[0].1).payload[4..<12])))
    }

    func testConfirmRightAfterTheRequestsStopIsRefusedBeforeTheTick() {
        model.startPairing(); deskPair(1); pairReq()
        now += Model.podRequestStaleMs + 1
        model.confirmPod()
        XCTAssertTrue(sender.sent.isEmpty)
        XCTAssertTrue(model.snapshot().pairing.podStale)
    }

    func testOnlyANewDeskPodTapLetsAnotherPodReplaceAStaleCandidate() {
        model.startPairing(); deskPair(1); pairReq()
        advance(Model.podRequestStaleMs + 1_000)
        pairReq(from: 0x1111_2222)
        var p = model.snapshot().pairing
        XCTAssertEqual(pod, p.candidate?.node) // a different pod cannot revive it
        XCTAssertTrue(p.podStale)
        deskPair(1, backlog: true, seq: 90) // not a live tap
        XCTAssertEqual(.podFound, step)
        deskPair(1) // a new live tap: back to the nod
        p = model.snapshot().pairing
        XCTAssertEqual(.waitNod, p.step)
        XCTAssertNil(p.candidate)
        pairReq(from: 0x1111_2222)
        p = model.snapshot().pairing
        XCTAssertEqual(.podFound, p.step)
        XCTAssertEqual(0x1111_2222, p.candidate?.node)
        XCTAssertFalse(p.podStale)
    }

    func testStaleCandidateEndsTheFlowOnceTheDeskWindowHasClosed() {
        model.startPairing(); deskPair(1, seconds: 10); pairReq()
        advance(8_000)
        XCTAssertTrue(model.snapshot().pairing.podStale)
        XCTAssertEqual(.podFound, step) // window still open: another nod can revive it
        advance(3_000)
        let p = model.snapshot().pairing
        XCTAssertEqual(.failed, p.step)
        XCTAssertEqual(.podFound, p.failedAt)
        XCTAssertEqual(Model.staleEndedMessage, p.message)
        XCTAssertTrue(p.message.contains("Tap the Desk Pod button and nod the drone pod twice again"))
    }

    func testStaleCandidateEndsTheFlowTwoMinutesAfterItWasFound() {
        model.startPairing(); deskPair(1); pairReq()
        var t: Int64 = 0
        while t < 116_000 {
            advance(2_000); t += 2_000
            pairReq()
            if t % 50_000 == 0 { deskPair(1) }
        }
        XCTAssertEqual(.podFound, step)
        deskPair(1)
        advance(Model.podRequestStaleMs + 1_000)
        XCTAssertTrue(model.snapshot().windowOpen)
        let p = model.snapshot().pairing
        XCTAssertEqual(.failed, p.step)
        XCTAssertEqual(Model.staleEndedMessage, p.message)
    }

    func testRepeatedPairReqIsAnsweredAgainUpToEightTimes() {
        model.startPairing(); deskPair(1); pairReq(); model.confirmPod()
        for _ in 0..<10 { pairReq() }
        XCTAssertEqual(Model.maxConfirmTries, sender.sent.count)
        pairReq(nonce: "0102030405060708")
        XCTAssertEqual(Model.maxConfirmTries, sender.sent.count)
    }

    func testNoPairAckTimesOut() {
        model.startPairing(); deskPair(1); pairReq(); model.confirmPod()
        advance(Model.ackTimeoutMs)
        XCTAssertEqual(.failed, step)
        XCTAssertEqual(.confirming, model.snapshot().pairing.failedAt)
    }

    func testAOkZeroIsAFailure() {
        model.startPairing(); deskPair(1); pairReq(); model.confirmPod()
        pairAck(0, 0)
        XCTAssertEqual(.failed, step)
        XCTAssertNil(model.snapshot().pairedPod)
    }

    func testButtonNotTappedTimesOut() {
        model.startPairing()
        advance(Model.buttonWaitMs)
        XCTAssertEqual(.failed, step)
        XCTAssertEqual(.waitButton, model.snapshot().pairing.failedAt)
    }

    func testCancelEndsTheFlowAndSendsNothing() {
        model.startPairing(); deskPair(1); pairReq()
        model.cancelPairing()
        XCTAssertEqual(.cancelled, step)
        model.confirmPod()
        XCTAssertTrue(sender.sent.isEmpty)
        model.clearPairingResult()
        XCTAssertEqual(.idle, step)
    }

    func testAnotherPodAskingDuringConfirmationIsIgnored() {
        model.startPairing(); deskPair(1); pairReq()
        pairReq(from: 0x1111_2222)
        XCTAssertEqual(pod, model.snapshot().pairing.candidate?.node)
    }

    func testLinkLossFailsAPairingInProgress() {
        model.startPairing(); deskPair(1)
        model.detach(sender, reason: "disconnected")
        XCTAssertEqual(.failed, step)
        XCTAssertTrue(model.snapshot().pairing.message.contains("link to the Desk Pod was lost"))
    }

    func testPairingNeedsAReadyLink() {
        sender.isReady = false
        model.startPairing()
        XCTAssertEqual(.failed, step)
    }

    func testNewTapWhileWaitingForTheNodRestartsTheCountdown() {
        model.startPairing(); deskPair(1)
        advance(40_000)
        deskPair(1)
        XCTAssertEqual(60, model.snapshot().pairing.windowSecondsLeft)
        XCTAssertEqual(.waitNod, step)
    }

    // MARK: link health

    func testHealthAndDetectRateComeFromLiveFrames() {
        let health = example("HEALTH"), detect = example("DETECT")
        model.onFrame(detect, meta: meta(pod, backlog: true))
        for _ in 0..<5 { now += 3_000; model.onFrame(detect, meta: meta(pod)) }
        model.onFrame(health, meta: meta(pod))
        var s = model.snapshot()
        XCTAssertEqual(5, s.detectsLastMinute)
        XCTAssertEqual(1, s.backlogCounts["DETECT"])
        XCTAssertEqual(5, s.liveCounts["DETECT"])
        XCTAssertEqual(pod, s.dronePodHealth?.node)
        XCTAssertEqual(1, s.dronePodHealth?.health.confirmed)
        let lines = BtrxDeskPodText.healthLines(s)
        XCTAssertTrue(lines[0].contains("confirmed, listening"), "\(lines)")
        XCTAssertTrue(lines.contains("Drone Pod hears Desk Pod: RSSI -30 dBm, SNR 12 dB"), "\(lines)")
        XCTAssertTrue(lines.contains("Desk Pod hears Drone Pod: RSSI -45 dBm, SNR 9.5 dB"), "\(lines)")
        now += 61_000
        s = model.snapshot()
        XCTAssertEqual(0, s.detectsLastMinute)
    }

    func testOnlyThePairedPodsHealthIsShownOnceKnown() {
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 1, devA: 2, devB: 3))
        let health = example("HEALTH")
        model.onFrame(health, meta: meta(0x2222_3333))
        XCTAssertNil(model.snapshot().dronePodHealth)
        XCTAssertEqual(1, model.snapshot().otherPodNodes)
        model.onFrame(health, meta: meta(pod))
        XCTAssertNotNil(model.snapshot().dronePodHealth)
    }

    func testNewSessionResetsCountersButKeepsTheWindow() {
        deskPair(1)
        model.onFrame(example("DETECT"), meta: meta(pod))
        let next = FakeSender()
        model.attach(next)
        let s = model.snapshot()
        XCTAssertTrue(s.liveCounts.isEmpty)
        XCTAssertTrue(s.windowOpen)
    }

    func testPairedPodRoundTripsThroughCodable() throws {
        let p = Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971)
        XCTAssertEqual(p, try JSONDecoder().decode(Model.PairedPod.self, from: JSONEncoder().encode(p)))
    }

    // MARK: backlog and paired-pod filtering

    func podFrame(_ name: String, podId: UInt32 = 0) -> P.Frame {
        let ex = example(name)
        return try! P.decodeFrame(P.encodeFrame(ex.type, seq: ex.seq, podId: podId, payload: ex.payload))
    }

    func testBacklogHealthAndRssiAreNotShownAsCurrent() {
        model.onFrame(podFrame("HEALTH"), meta: meta(pod, backlog: true))
        model.onFrame(podFrame("DETECT"), meta: meta(0x2222_3333, backlog: true))
        let s = model.snapshot()
        XCTAssertNil(s.dronePodHealth)
        XCTAssertNil(s.lastPacketRssi)
        XCTAssertEqual(0, s.otherPodNodes)
        XCTAssertEqual(0, s.detectsLastMinute)
        XCTAssertEqual(1, s.backlogCounts["HEALTH"])
    }

    func testOncePairedOnlyThePairedNodeCounts() {
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971))
        model.onFrame(podFrame("DETECT"), meta: meta(0x2222_3333))
        model.onFrame(podFrame("DETECT"), meta: meta(pod))
        model.onFrame(podFrame("DETECT", podId: 0x45D3_5AF0), meta: meta(pod))
        model.onFrame(podFrame("DETECT", podId: 0x1234_5678), meta: meta(pod))
        model.onFrame(podFrame("HEALTH", podId: 0x1234_5678), meta: meta(pod))
        let s = model.snapshot()
        XCTAssertEqual(2, s.detectsLastMinute)
        XCTAssertEqual(1, s.otherPodNodes)
        XCTAssertNil(s.dronePodHealth)
        model.onFrame(podFrame("HEALTH", podId: 0x45D3_5AF0), meta: meta(pod))
        XCTAssertEqual(pod, model.snapshot().dronePodHealth?.node)
    }

    func testRssiComesOnlyFromThePairedPod() {
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 1, devA: 2, devB: 3))
        model.onFrame(podFrame("DETECT"), meta: BtrxRadioMeta(fromNode: 0x2222_3333, toNode: 0xFFFF_FFFF, rxRssi: -90, rxSnr: 1, arrivalMs: now, backlog: false))
        XCTAssertNil(model.snapshot().lastPacketRssi)
        model.onFrame(podFrame("DETECT"), meta: meta(pod))
        XCTAssertEqual(-45, model.snapshot().lastPacketRssi)
    }

    func testPairedNodeWithAnUnexpectedPodIdIsCountedLoggedAndShown() {
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971))
        for _ in 0..<3 { model.onFrame(podFrame("DETECT", podId: 0x1234_5678), meta: meta(pod)) }
        let s = model.snapshot()
        XCTAssertEqual(3, s.pairedPodIdMismatches)
        XCTAssertEqual(0x1234_5678, s.lastMismatchPodId)
        XCTAssertEqual(1, log.warns.filter { $0.hasPrefix("paired node !018968be sending pod_id 12345678") && $0.hasSuffix("re-pair") }.count)
        XCTAssertEqual(["Paired Drone Pod is sending pod_id 12345678 (3 frame(s) ignored); re-pair it."], BtrxDeskPodText.pairedPodWarnings(s))
        model.forgetPairedPod()
        XCTAssertEqual(0, model.snapshot().pairedPodIdMismatches)
    }

    func testQuietPairedPodShowsARepairHint() {
        model.setLink(Model.LinkStatus(kind: .bluetooth, phase: .ready))
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971))
        now += 60_000
        model.onFrame(podFrame("HEALTH", podId: 0x45D3_5AF0), meta: meta(pod))
        now += Model.pairedQuietMs
        XCTAssertFalse(model.snapshot().pairedPodQuiet)
        now += 1
        let s = model.snapshot()
        XCTAssertTrue(s.pairedPodQuiet)
        XCTAssertEqual([BtrxDeskPodText.pairedPodQuiet], BtrxDeskPodText.pairedPodWarnings(s))
        model.setLink(Model.LinkStatus(kind: .bluetooth, phase: .retryWait))
        XCTAssertFalse(model.snapshot().pairedPodQuiet) // not while the link is down
    }

    func testNoHintBeforeTheFirstTwoMinutesOfASession() {
        model.setLink(Model.LinkStatus(kind: .bluetooth, phase: .ready))
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 1, devA: 2, devB: 3))
        now += 100_000
        let next = FakeSender()
        model.attach(next) // reconnect restarts the wait
        now += 100_000
        XCTAssertFalse(model.snapshot().pairedPodQuiet)
        now += 30_000
        XCTAssertTrue(model.snapshot().pairedPodQuiet)
        withExtendedLifetime(next) {}
    }

    // MARK: steering (STEER / STEER_MUTE)

    let pairedPodId: UInt32 = 0x45D3_5AF0
    func ready() { model.setLink(Model.LinkStatus(kind: .bluetooth, phase: .ready)) }
    func steerFrame(podId: UInt32? = nil, src: Int = 0xBEEF) -> P.Frame {
        var p = P.fromHex(BtrxProtocolTests.steerPayload)
        p[8] = UInt8(src & 0xFF); p[9] = UInt8(src >> 8)
        podSeq += 1
        return try! P.decodeFrame(P.encodeFrame(P.STEER, seq: podSeq, podId: podId ?? pairedPodId, payload: p))
    }
    func restorePaired() { model.restorePairedPod(Model.PairedPod(node: pod, podId: pairedPodId, devA: 0x4C8C_125B, devB: 0x351C_2971)) }

    func testSteerOverlayShowsOnlyWhilePairedLinkedAndArriving() {
        ready()
        model.onFrame(steerFrame(podId: 0), meta: meta(pod))
        XCTAssertNil(model.snapshot().steerOverlay) // not paired yet: ignored
        XCTAssertNil(model.snapshot().lastSteer)
        restorePaired()
        model.onFrame(steerFrame(), meta: meta(pod))
        let v = model.snapshot().steerOverlay
        XCTAssertEqual(.left, v?.hint)
        XCTAssertEqual(0xBEEF, v?.srcId)
        XCTAssertEqual(0, v?.colorIndex)
        model.setLink(Model.LinkStatus(kind: .bluetooth, phase: .retryWait))
        XCTAssertNil(model.snapshot().steerOverlay) // link down: hidden
        XCTAssertNotNil(model.snapshot().lastSteer) // the Desk Pod screen keeps the last one
        ready()
        XCTAssertNotNil(model.snapshot().steerOverlay)
        now += BtrxSteering.showMs + 1
        XCTAssertNil(model.snapshot().steerOverlay) // STEER stopped arriving
        XCTAssertEqual(.lost, model.snapshot().lastSteer?.hint)
    }

    func testSteerIsFilteredByPairedNodeAndPodId() {
        ready()
        restorePaired()
        model.onFrame(steerFrame(), meta: meta(0x2222_3333))            // another node
        model.onFrame(steerFrame(podId: 0x1234_5678), meta: meta(pod))  // another pod_id
        model.onFrame(steerFrame(), meta: meta(pod, backlog: true))     // connect backlog
        XCTAssertNil(model.snapshot().lastSteer)
        model.onFrame(steerFrame(), meta: meta(pod))
        XCTAssertNotNil(model.snapshot().steerOverlay)
    }

    func testMuteAndClearMutesGoToThePairedPodNode() throws {
        ready()
        restorePaired()
        model.muteSteeringSource()
        XCTAssertTrue(sender.sent.isEmpty) // no source yet
        XCTAssertTrue(log.warns.contains { $0.hasPrefix("steering: Mute not sent") })
        model.onFrame(steerFrame(src: 0x0102), meta: meta(pod))
        model.muteSteeringSource()
        let (to, bytes) = try XCTUnwrap(sender.sent.last)
        XCTAssertEqual(pod, to)
        let f = try P.decodeFrame(bytes)
        XCTAssertEqual(P.STEER_MUTE, f.type)
        XCTAssertEqual(pairedPodId, f.podId)
        XCTAssertEqual("010201", P.hex(f.payload)) // op 1, src 0x0102
        model.clearSteeringMutes()
        XCTAssertEqual("000000", P.hex(try P.decodeFrame(sender.sent.last!.1).payload))
        XCTAssertEqual(pod, sender.sent.last!.0)
    }

    func testMuteIsRefusedWithoutAPairing() {
        ready()
        model.clearSteeringMutes()
        model.muteSteeringSource()
        XCTAssertFalse(sender.sent.contains { (try? P.decodeFrame($0.1))?.type == P.STEER_MUTE })
    }

    func testForgettingThePodClearsSteering() {
        ready()
        restorePaired()
        model.onFrame(steerFrame(), meta: meta(pod))
        model.forgetPairedPod()
        XCTAssertNil(model.snapshot().lastSteer)
        XCTAssertNil(model.snapshot().steerOverlay)
    }

    func testAPodRestartIsLoggedAndResetsSteering() {
        ready()
        restorePaired()
        model.onFrame(steerFrame(src: 5), meta: meta(pod))
        model.onFrame(steerFrame(src: 6), meta: meta(pod))
        XCTAssertEqual(1, model.snapshot().steerOverlay?.colorIndex)
        var p = P.fromHex(BtrxProtocolTests.steerPayload)
        P.putU32(&p, 2, 1_000); p[8] = 6; p[9] = 0 // window_end_ms went back
        podSeq += 1
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.STEER, seq: podSeq, podId: pairedPodId, payload: p)), meta: meta(pod))
        XCTAssertEqual(0, model.snapshot().steerOverlay?.colorIndex)
        XCTAssertTrue(log.infos.contains { $0.hasPrefix("steering: window_end_ms went back to 1000: the Drone Pod restarted") })
    }

    func testRollCorrectionIsOffUntilSwitchedOn() {
        XCTAssertFalse(model.snapshot().steeringRollCorrection)
        model.setSteeringRollCorrection(true)
        XCTAssertTrue(model.snapshot().steeringRollCorrection)
    }
}
