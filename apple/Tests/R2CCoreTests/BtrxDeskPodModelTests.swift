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

    func testWindowRunningOutBeforeConfirmFails() {
        model.startPairing(); deskPair(1, seconds: 10); pairReq()
        advance(10_000)
        let p = model.snapshot().pairing
        XCTAssertEqual(.failed, p.step)
        XCTAssertEqual(.podFound, p.failedAt)
        XCTAssertTrue(p.message.hasPrefix("The pairing window ran out"))
        model.confirmPod()
        XCTAssertTrue(sender.sent.isEmpty)
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
}
