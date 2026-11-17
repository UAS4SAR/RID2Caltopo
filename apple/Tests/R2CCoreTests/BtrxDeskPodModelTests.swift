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

    func deskPair(_ open: Int, seconds: Int = 60, backlog: Bool = false, seq: Int? = nil, source: Int = 1) {
        let s: Int
        if let seq { s = seq } else { deskSeq += 1; s = deskSeq }
        let frame = try! P.decodeFrame(P.encodeFrame(P.DESK_PAIR, seq: s, payload: [UInt8(open), UInt8(source), UInt8(seconds & 0xFF), UInt8(seconds >> 8)]))
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
        XCTAssertTrue(log.debugs.contains { $0.contains("no Desk Pod window open") })
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

    private func role(_ build: Int) -> P.RoleReport {
        P.RoleReport(role: P.roleDeskPod, source: 0, confirmed: 1, flags: 0, buildHash: build,
                     devId: 1, peerId: 0, podId: 0, imuWhoami: 0, txPolicy: 0)
    }

    func testLinkDropLogsTheLastHealthUptimes() throws {
        model.detach(sender, reason: "first")
        XCTAssertTrue(log.debugs.contains("link drop (first): no Desk Pod HEALTH this session; no Drone Pod HEALTH"), "\(log.debugs)")
        model.attach(sender)
        model.onConfigured(localNode: desk)
        var h34 = [UInt8](repeating: 0, count: 34)
        P.putU32(&h34, 0, 500); h34[6] = 3
        model.onFrame(try P.decodeFrame(P.encodeFrame(P.HEALTH, payload: h34)), meta: meta(desk))
        model.onFrame(try P.decodeFrame(BtrxProtocolTests.example("HEALTH")), meta: meta(pod))
        let podUptime = try XCTUnwrap(model.snapshot().dronePodHealth).health.uptimeS
        now += 12_000
        model.detach(sender, reason: "disconnected (status 8)")
        let line = "link drop (disconnected (status 8)): Desk Pod last HEALTH uptime_s=500, 12 s ago; "
            + "Drone Pod last HEALTH uptime_s=\(podUptime), 12 s ago"
        XCTAssertTrue(log.debugs.contains(line), "\(log.debugs)")
    }

    func testDeskPodOwnHealthShowsPhoneLostAndIsNotDronePodHealth() throws {
        model.onLocalRoleReport(role(0x0104))
        var s = model.snapshot()
        XCTAssertTrue(s.roleSupported)
        XCTAssertEqual("Firmware build: 0x0104 (supported)", BtrxDeskPodText.firmwareLine(s))
        var h34 = [UInt8](repeating: 0, count: 34)
        P.putU32(&h34, 0, 500); h34[6] = 3; P.putU32(&h34, 30, 17)
        let frame = try P.decodeFrame(P.encodeFrame(P.HEALTH, payload: h34))
        model.onFrame(frame, meta: meta(desk, backlog: true))
        XCTAssertNil(model.snapshot().deskPhoneLost) // backlog: not current
        model.onFrame(frame, meta: meta(desk))
        s = model.snapshot()
        XCTAssertEqual(s.deskPhoneLost, 17)
        XCTAssertNil(s.dronePodHealth)
        XCTAssertEqual(s.otherPodNodes, 0)
        XCTAssertTrue(BtrxDeskPodText.healthLines(s).contains("Desk Pod to app: 17 message(s) dropped since the Desk Pod started (queue full)"),
                      "\(BtrxDeskPodText.healthLines(s))")
        model.onLocalRoleReport(role(0x00FF))
        XCTAssertEqual("Firmware build: 0x00ff (older than this app supports; update to 0x0100 or newer)", BtrxDeskPodText.firmwareLine(model.snapshot()))
    }

    final class ModelBridge: BtrxClientListener {
        let model: Model
        init(_ model: Model) { self.model = model }
        func onConfigured(localNode: UInt32) { model.onConfigured(localNode: localNode) }
        func onLocalRoleReport(_ report: BtrxProtocol.RoleReport) { model.onLocalRoleReport(report) }
        func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) { model.onFrame(frame, meta: meta) }
        func onVersionRefused(fromNode: UInt32, version: Int) {}
        func onPacketDropped(encrypted: Bool) { model.onPacketDropped(encrypted: encrypted) }
    }

    func testDroppedPacketsShowOnTheLiveFramesLineAndRestartWithTheSession() throws {
        XCTAssertEqual("Live frames: none", BtrxDeskPodText.liveFramesLine(model.snapshot()))
        model.onPacketDropped(encrypted: true); model.onPacketDropped(encrypted: true); model.onPacketDropped(encrypted: false)
        model.onFrame(try P.decodeFrame(P.encodeFrame(P.HEALTH, payload: [UInt8](repeating: 0, count: 30))), meta: meta(desk))
        let s = model.snapshot()
        XCTAssertEqual(2, s.droppedEncrypted); XCTAssertEqual(1, s.droppedOtherPort)
        XCTAssertEqual("Live frames: HEALTH=1; dropped: encrypted=2 other-port=1", BtrxDeskPodText.liveFramesLine(s))
        model.attach(FakeSender())
        XCTAssertEqual("Live frames: none", BtrxDeskPodText.liveFramesLine(model.snapshot()))
    }

    func testLinkLineShowsReconnectAttemptsAndTheLastFailure() {
        func line(_ link: Model.LinkStatus) -> String { model.setLink(link); return BtrxDeskPodText.linkLine(model.snapshot()) }
        XCTAssertEqual("Bluetooth: connecting", line(Model.LinkStatus(kind: .bluetooth, phase: .connecting)))
        XCTAssertEqual("Bluetooth: waiting to retry; reconnecting, attempt 2 (last failure: connection failed (status 8))",
                       line(Model.LinkStatus(kind: .bluetooth, phase: .retryWait, failedAttempts: 1, lastFailure: "connection failed (status 8)")))
        XCTAssertEqual("Bluetooth: connecting; reconnecting, attempt 10 (last failure: connection failed (status 147))",
                       line(Model.LinkStatus(kind: .bluetooth, phase: .connecting, failedAttempts: 9, lastFailure: "connection failed (status 147)")))
        XCTAssertEqual("Bluetooth: connected", line(Model.LinkStatus(kind: .bluetooth, phase: .ready)))
        XCTAssertEqual("Bluetooth: off", line(Model.LinkStatus(kind: .bluetooth, phase: .off, failedAttempts: 3, lastFailure: "x")))
    }

    /// Field, Nov 16 (BT RX Desk Pod serial log): Drone Pod A !018968be sends 40-byte STEER (two
    /// extra sources) and 34-byte HEALTH (build 0x0104) through Desk Pod !66c842ca. The whole path
    /// (Meshtastic FromRadio -> BtrxClient -> model) must keep them for the paired pod. Same as Android.
    func testBuild0104FramesFromPairedPod018968beReachTheScreenThroughTheClient() throws {
        typealias M = MeshtasticPhoneAPI
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971))
        model.setLink(Model.LinkStatus(kind: .usb, phase: .ready))
        var out: [[UInt8]] = []
        let clientLog = BtrxTestLogger()
        let bridge = ModelBridge(model)
        let client = BtrxClient(send: { out.append($0) }, log: clientLog, listener: bridge, rng: BtrxTestRandom(state: 1),
                                framing: .serial, clock: { [unowned self] in self.now })
        client.start()
        var msgs: [[UInt8]] = []
        let d = M.StreamDecoder(onMessage: { msgs.append($0) })
        out.forEach { d.feed($0) }
        let r = M.ProtoReader(msgs[0]); _ = try r.tag(); let configId = try r.varint()
        func feed(_ proto: [UInt8]) { client.onBytes(M.frame(proto)) }
        feed(M.ProtoWriter().bytesField(3, M.ProtoWriter().varint(1, UInt64(desk)).bytes()).bytes())
        feed(M.ProtoWriter().varint(7, configId).bytes())
        now += BtrxClient.serialQuietGapMs
        client.checkQuietGap()

        let steer27 = P.steerPayload(P.Steer(steerSeq: 9, flags: P.STEER_F_TARGET | P.STEER_F_IMU_OK, windowEndMs: 145_298, ageMs: 61,
                                             srcId: 1, rssiPort: -37, rssiStbd: -30, peakRssi: -25, hitsPort: 7, hitsStbd: 7, companyId: 0,
                                             devClass: 0, addrType: 0, macLsbFirst: [1, 2, 3, 4, 5, 6], rollX2: 5, pitchX2: 6))
        let steer40 = steer27 + [0x12] + [2, 0, UInt8(bitPattern: -50), UInt8(bitPattern: -55), 0x33, 1 << 2]
            + [3, 0, UInt8(bitPattern: -70), UInt8(bitPattern: -128), 0x01, 2 << 2]
        XCTAssertEqual(steer40.count, 40)
        var health34 = [UInt8](repeating: 0, count: 34)
        P.putU32(&health34, 0, 300); health34[4] = 0x04; health34[5] = 0x01; health34[6] = 2; health34[8] = 1; health34[29] = 1
        func fromPod(_ type: Int, _ payload: [UInt8], _ seq: Int) -> [UInt8] {
            MeshtasticPhoneAPITests.fromRadioPacket(from: pod, to: 0xFFFF_FFFF, rssi: -68, snr: 13, port: P.privateAppPort,
                                                    payload: P.encodeFrame(type, seq: seq, podId: 0x45D3_5AF0, payload: payload))
        }
        feed(fromPod(P.STEER, steer40, 1))
        feed(fromPod(P.HEALTH, health34, 2))
        feed(fromPod(P.DETECT, example("DETECT").payload, 3))

        XCTAssertEqual(client.frameCount("STEER"), 1)
        XCTAssertEqual(client.frameCount("HEALTH"), 1)
        XCTAssertFalse(client.summary().contains("rejected"), client.summary())
        XCTAssertTrue(clientLog.warns.isEmpty, "\(clientLog.warns)")
        let s = model.snapshot()
        XCTAssertEqual(s.liveCounts["STEER"], 1); XCTAssertEqual(s.liveCounts["HEALTH"], 1); XCTAssertEqual(s.liveCounts["DETECT"], 1)
        XCTAssertEqual(s.dronePodHealth?.node, pod)
        XCTAssertEqual(s.dronePodHealth?.health.buildHash, 0x0104)
        XCTAssertEqual(s.detectsLastMinute, 1)
        XCTAssertEqual(s.pairedPodIdMismatches, 0)
        XCTAssertEqual(s.otherPodNodes, 0)
        XCTAssertNotNil(s.lastSteer)
    }

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

    // MARK: one Drone Pod per app

    func testPairingIsNotStartedWhileADronePodIsPaired() {
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971))
        model.startPairing()
        XCTAssertEqual(.idle, step)
        XCTAssertTrue(log.debugs.contains { $0.contains("is paired; use Replace Drone Pod") })
    }

    func testReplaceUnpairsTheCurrentPodThenRunsTheWholePairingFlow() {
        model.restorePairedPod(Model.PairedPod(node: 0x0BAD_F00D, podId: 0x1111_1111, devA: 0x4C8C_125B, devB: 0x351C_2971))
        model.replacePairedPod()
        XCTAssertNil(model.snapshot().pairedPod) // unpaired first
        XCTAssertEqual(.waitButton, step)
        XCTAssertTrue(log.debugs.contains { $0.contains("Drone Pod !0badf00d pod_id=11111111 unpaired in this app to replace it") })
        XCTAssertTrue(sender.sent.isEmpty) // firmware 0x0102 has no unpair message to send
        deskPair(1)
        pairReq()
        model.confirmPod()
        pairAck(1, 1)
        let done = model.snapshot()
        XCTAssertEqual(.paired, done.pairing.step)
        XCTAssertEqual(pod, done.pairedPod?.node)
        XCTAssertEqual(1, paired.count)
        // The old pod keeps sending with its old pod_id: ignored now.
        podSeq += 1
        let old = try! P.decodeFrame(P.encodeFrame(P.STEER, seq: podSeq, podId: 0x1111_1111, payload: P.fromHex(BtrxProtocolTests.steerPayload)))
        model.onFrame(old, meta: meta(0x0BAD_F00D))
        XCTAssertNil(model.snapshot().lastSteer)
    }

    func testReplaceWithNothingPairedJustStartsPairing() {
        model.replacePairedPod()
        XCTAssertEqual(.waitButton, step)
    }

    func testReplaceTextNamesThePodAndWhatThePodStillHolds() {
        model.restorePairedPod(Model.PairedPod(node: pod, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971))
        let msg = BtrxDeskPodText.replacePodMessage(model.snapshot())
        XCTAssertTrue(msg.hasPrefix("Drone Pod !018968be (pod_id 45d35af0) will be unpaired from this app"), msg)
        XCTAssertTrue(msg.contains("keeps its own pairing"))
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

    // MARK: IMU calibration (IMU_CAL / IMU_CAL_ACK)

    func imuAck(_ op: Int, _ tag: Int, status: Int = P.IMUCAL_OK, flags: Int = P.IMUCAL_F_IMU_OK | P.IMUCAL_F_LEVEL_HELD, podId: UInt32? = nil) -> P.Frame {
        podSeq += 1
        let payload = P.imuCalAckPayload(P.ImuCalAck(op: op, tag: tag, status: status, flags: flags, rollX2: 40, pitchX2: 0, tiltX2: 4, face: 0, yPoints: 0, gyroBiasX10: [0, 0, 0]))
        return try! P.decodeFrame(P.encodeFrame(P.IMU_CAL_ACK, seq: podSeq, podId: podId ?? pairedPodId, flags: P.F_IS_ACK, payload: payload))
    }
    func lastImuCal() -> (UInt32, P.Frame) { let l = sender.sent.last!; return (l.0, try! P.decodeFrame(l.1)) }
    var imu: BtrxImuCal.View { model.snapshot().imuCal! }
    var imuCalSent: Bool { sender.sent.contains { (try? P.decodeFrame($0.1))?.type == P.IMU_CAL } }

    func testImuCalGoesToThePairedPodNodeAndOnlyItsAnswerCounts() {
        ready()
        restorePaired()
        model.startImuCal()
        let (to, f) = lastImuCal()
        XCTAssertEqual(pod, to)
        XCTAssertEqual(P.IMU_CAL, f.type)
        XCTAssertEqual(pairedPodId, f.podId)
        XCTAssertEqual("0101", P.hex(f.payload)) // level, tag 1
        XCTAssertEqual(.LEVEL, imu.step)
        model.onFrame(imuAck(1, 1), meta: meta(0x2222_3333))               // another node
        model.onFrame(imuAck(1, 1, podId: 0x1234_5678), meta: meta(pod))   // another pod_id
        model.onFrame(imuAck(1, 1), meta: meta(pod, backlog: true))        // connect backlog
        XCTAssertEqual(.LEVEL, imu.step)
        model.onFrame(imuAck(1, 2), meta: meta(pod))                        // another tag
        XCTAssertEqual(.LEVEL, imu.step)
        XCTAssertTrue(log.debugs.contains { $0.contains("matches no request out; ignored") })
        model.onFrame(imuAck(1, 1), meta: meta(pod))
        XCTAssertEqual(.STBD_READY, imu.step)
        model.startImuCalStarboard()
        XCTAssertEqual(.STBD_COUNTDOWN, imu.step)
        let before = sender.sent.count
        advance(1_000)
        XCTAssertEqual(before, sender.sent.count)
        advance(2_000)
        let (to2, f2) = lastImuCal()
        XCTAssertEqual(pod, to2)
        XCTAssertEqual(pairedPodId, f2.podId)
        XCTAssertEqual("0202", P.hex(f2.payload)) // starboard, tag 2
        model.onFrame(imuAck(2, 2, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_CALIBRATED), meta: meta(pod))
        XCTAssertEqual(.CALIBRATED, imu.step)
        XCTAssertEqual(true, imu.calibrated)
    }

    func testImuCalAnswerBeforePairingIsIgnored() {
        ready()
        model.onFrame(imuAck(3, 1, flags: P.IMUCAL_F_IMU_OK | P.IMUCAL_F_CALIBRATED, podId: 0), meta: meta(pod))
        XCTAssertNil(imu.calibrated)
    }

    func testImuCalIsRefusedWithoutAPairingOrLink() {
        ready()
        model.startImuCal()
        XCTAssertFalse(imuCalSent)
        XCTAssertEqual(.FAILED, imu.step)
        XCTAssertEqual("No Drone Pod is paired. Pair it first.", imu.message)
        restorePaired()
        sender.isReady = false
        model.checkImuCal()
        XCTAssertFalse(imuCalSent)
        XCTAssertEqual("The Desk Pod is not connected.", imu.message)
    }

    func testImuCalTimesOutCheckClearAndSteerBit6() {
        ready()
        restorePaired()
        var p = P.fromHex(BtrxProtocolTests.steerPayload)
        p[1] = UInt8(P.STEER_F_IMU_OK | P.STEER_F_IMU_CAL)
        podSeq += 1
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.STEER, seq: podSeq, podId: pairedPodId, payload: p)), meta: meta(pod))
        XCTAssertEqual(true, imu.calibrated)
        XCTAssertTrue(imu.calibratedFromSteer)
        model.checkImuCal()
        XCTAssertEqual("0301", P.hex(lastImuCal().1.payload))
        advance(BtrxImuCal.ackTimeoutMs)
        XCTAssertEqual(.FAILED, imu.step)
        model.clearImuCal()
        XCTAssertEqual("0002", P.hex(lastImuCal().1.payload))
        model.onFrame(imuAck(0, 2, flags: P.IMUCAL_F_IMU_OK), meta: meta(pod))
        XCTAssertEqual(false, imu.calibrated)
        model.closeImuCalResult()
        XCTAssertEqual("", imu.message)
    }

    func testImuCalCancelLinkLossAndForget() {
        ready()
        restorePaired()
        model.startImuCal()
        let n = sender.sent.count
        model.cancelImuCal()
        XCTAssertEqual(n, sender.sent.count) // cancel sends nothing
        XCTAssertEqual(BtrxImuCal.cancelled, imu.message)
        model.startImuCal()
        model.detach(sender, reason: "test")
        XCTAssertEqual(.FAILED, imu.step)
        XCTAssertTrue(imu.message.hasPrefix("The link to the Desk Pod was lost"))
        model.forgetPairedPod()
        XCTAssertEqual(.IDLE, imu.step)
        XCTAssertNil(imu.calibrated)
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

    func testLearnMoreLinkMatchesAndroid() {
        XCTAssertEqual(BtrxDeskPodText.learnMore, "Learn more about BTRX")
        XCTAssertEqual(BtrxDeskPodText.learnMoreURL, "https://rid2caltopo.com/btrx")
        XCTAssertEqual(URL(string: BtrxDeskPodText.learnMoreURL)?.scheme, "https")
    }

    func testBtrxOpensFromDeveloperToolsNotTheMainMenu() throws {
        let appleRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let content = try String(contentsOf: appleRoot.appendingPathComponent("App/ContentView.swift"), encoding: .utf8)
        let settings = try String(contentsOf: appleRoot.appendingPathComponent("App/CaltopoSettingsView.swift"), encoding: .utf8)
        XCTAssertFalse(content.contains("Button(BtrxDeskPodText.title"))
        XCTAssertFalse(content.contains("showDeskPod"))
        let tools = try XCTUnwrap(settings.range(of: "private struct AppleDeveloperToolsView"))
        let body = String(settings[tools.lowerBound...])
        let entry = try XCTUnwrap(body.range(of: "Button(BtrxDeskPodText.title"))
        let title = try XCTUnwrap(body.range(of: ".navigationTitle(\"Developer Tools\")"))
        XCTAssertTrue(entry.lowerBound < title.lowerBound)
        XCTAssertTrue(body.contains("DeskPodView(center: deskPod, onDismiss: { showDeskPod = false })"))
    }

    // MARK: firmware 0x0106: re-pair, own aircraft (saved), HEALTH 44 (same as Android)

    var savedLists: [[Int]] = []
    let pod0106 = BtrxDeskPodModel.PairedPod(node: 0x0189_68BE, podId: 0x45D3_5AF0, devA: 0x4C8C_125B, devB: 0x351C_2971)

    private func useSavedListModel() {
        model = Model(log: log, clock: { [unowned self] in self.now }, onPaired: { [unowned self] in self.paired.append($0) },
                      onSavedIgnores: { [unowned self] in self.savedLists.append($0) })
        model.attach(sender)
        model.onConfigured(localNode: desk)
    }

    private func podHealth(_ build: Int, size: Int = 44, _ ext: ((inout [UInt8]) -> Void)? = nil) {
        var p = [UInt8](repeating: 0, count: size)
        P.putU32(&p, 0, 500); p[4] = UInt8(build & 0xFF); p[5] = UInt8(build >> 8); p[6] = UInt8(P.roleStarboard); p[8] = 1
        ext?(&p)
        podSeq += 1
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.HEALTH, seq: podSeq, podId: pod0106.podId, payload: p)), meta: meta(pod))
    }

    /// A 6-byte FILTER_ACK (build 0x0106), or the 8-byte one (0x0107) when `srcId` is given.
    private func filterAck(op: Int, ok: Int, stored: Int, from: UInt32? = nil, srcId: Int? = nil) {
        podSeq += 1
        let f = P.encodeFrame(P.FILTER_ACK, seq: podSeq, podId: pod0106.podId, flags: ok == 0 ? P.F_ERROR : 0,
                              payload: [1, UInt8(op), UInt8(ok), 0x34, 0x12, UInt8(stored)]
                                + (srcId.map { [UInt8($0 & 0xFF), UInt8($0 >> 8)] } ?? []))
        model.onFrame(try! P.decodeFrame(f), meta: meta(from ?? pod))
    }

    func testRepairSendsPairOpenAndDeskPairThenPairsTheSamePodAgain() throws {
        model.restorePairedPod(pod0106)
        podHealth(0x0106)
        sender.sent.removeAll()
        model.repairDronePod()
        XCTAssertEqual(sender.sent.count, 2)
        XCTAssertEqual(sender.sent[0].0, pod)
        let f1 = try P.decodeFrame(sender.sent[0].1)
        XCTAssertEqual(f1.type, P.PAIR_OPEN); XCTAssertEqual(f1.podId, pod0106.podId); XCTAssertTrue(f1.payload.isEmpty)
        XCTAssertEqual(sender.sent[1].0, desk)
        XCTAssertEqual(try P.decodeFrame(sender.sent[1].1).type, P.DESK_PAIR)
        let p0 = model.snapshot().pairing
        XCTAssertEqual(p0.step, .waitButton); XCTAssertTrue(p0.repair)
        XCTAssertEqual(BtrxDeskPodText.steps(p0)[0].title, "Desk Pod opens its pairing window")
        XCTAssertEqual(model.snapshot().pairedPod, pod0106)
        deskPair(1)
        XCTAssertEqual(step, .waitNod)
        XCTAssertEqual(BtrxDeskPodText.steps(model.snapshot().pairing)[1].title, "Drone Pod asks to pair")
        pairReq(from: 0x0BAD_BEEF)
        XCTAssertEqual(step, .waitNod)
        pairReq()
        XCTAssertEqual(step, .podFound)
        model.confirmPod()
        XCTAssertEqual(step, .confirming)
        pairAck()
        XCTAssertEqual(step, .paired)
        XCTAssertEqual(model.snapshot().pairing.message, "Drone Pod re-paired.")
        XCTAssertEqual(model.snapshot().pairedPod?.node, pod)
    }

    func testRepairFailureKeepsThePodPaired() {
        model.restorePairedPod(pod0106)
        model.repairDronePod()
        advance(Model.repairWindowWaitMs + 1000)
        let p = model.snapshot().pairing
        XCTAssertEqual(p.step, .failed)
        XCTAssertTrue(p.message.hasSuffix(Model.repairKept), p.message)
        XCTAssertEqual(model.snapshot().pairedPod, pod0106)
        model.repairDronePod()
        deskPair(1)
        model.cancelPairing()
        XCTAssertEqual(model.snapshot().pairedPod, pod0106)
    }

    func testRepairNeedsAPairedPodAndALink() {
        sender.sent.removeAll()
        model.repairDronePod()
        XCTAssertEqual(step, .failed)
        XCTAssertEqual(model.snapshot().pairing.message, Model.repairNotPaired)
        model.clearPairingResult()
        model.restorePairedPod(pod0106)
        sender.isReady = false
        model.repairDronePod()
        XCTAssertEqual(step, .failed)
        XCTAssertTrue(sender.sent.isEmpty)
        sender.isReady = true
        model.clearPairingResult()
        model.startPairing()
        XCTAssertEqual(step, .idle)
    }

    func testRepairOfAnOlderPodSendsOnlyTheDeskWindowAndAsksForANod() throws {
        model.restorePairedPod(pod0106)
        podHealth(0x0105, size: 34)
        sender.sent.removeAll()
        model.repairDronePod()
        XCTAssertEqual(try sender.sent.map { try P.decodeFrame($0.1).type }, [P.DESK_PAIR])
        deskPair(1)
        XCTAssertEqual(BtrxDeskPodText.steps(model.snapshot().pairing)[1].title, "Nod the drone pod twice")
    }

    func testOwnAircraftSavedOnBuild0106() throws {
        useSavedListModel()
        model.restorePairedPod(pod0106)
        model.setLink(Model.LinkStatus(kind: .usb, phase: .ready))
        XCTAssertFalse(model.snapshot().savedIgnoreSupported)
        sender.sent.removeAll()
        model.ignoreSourceSaved(0x148C)
        XCTAssertTrue(sender.sent.isEmpty, "older or unknown build: nothing sent")
        XCTAssertTrue(model.snapshot().filterMessage!.contains("0x0106"))
        podHealth(0x0105, size: 34)
        XCTAssertFalse(model.snapshot().savedIgnoreSupported)
        podHealth(0x0106)
        XCTAssertTrue(model.snapshot().savedIgnoreSupported)
        model.ignoreSourceSaved(0xF1A3)
        XCTAssertEqual(sender.sent.count, 1)
        XCTAssertEqual(sender.sent[0].0, pod)
        let f = try P.decodeFrame(sender.sent[0].1)
        XCTAssertEqual(f.type, P.STEER_MUTE)
        XCTAssertEqual(f.payload, [3, 0xA3, 0xF1])
        XCTAssertEqual(model.snapshot().filterPending.map(\.srcId), [0xF1A3])
        XCTAssertTrue(model.snapshot().filterPendingFor(0xF1A3))
        model.ignoreSourceSaved(0xF1A3)
        XCTAssertEqual(sender.sent.count, 1, "one at a time per source")
        filterAck(op: P.FILTER_OP_REMOVE, ok: 1, stored: 1)
        XCTAssertTrue(model.snapshot().savedIgnores.isEmpty)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 1, from: 0x0BAD_BEEF)
        XCTAssertTrue(model.snapshot().savedIgnores.isEmpty)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 1)
        XCTAssertEqual(model.snapshot().savedIgnores, [0xF1A3])
        XCTAssertTrue(model.snapshot().filterPending.isEmpty)
        XCTAssertEqual(savedLists, [[0xF1A3]])
        XCTAssertTrue(model.snapshot().filterMessage!.hasPrefix("Saved source F1A3 on the Drone Pod"))
        model.ignoreSourceSaved(0x0002)
        filterAck(op: P.FILTER_OP_ADD, ok: 0, stored: 1)
        XCTAssertEqual(model.snapshot().savedIgnores, [0xF1A3])
        XCTAssertTrue(model.snapshot().filterMessage!.contains("did not save"))
        model.unignoreSourceSaved(0xF1A3)
        XCTAssertEqual(try P.decodeFrame(sender.sent.last!.1).payload, [4, 0xA3, 0xF1])
        filterAck(op: P.FILTER_OP_REMOVE, ok: 1, stored: 0)
        XCTAssertTrue(model.snapshot().savedIgnores.isEmpty)
        XCTAssertEqual(savedLists.last, [])
        model.ignoreSourceSaved(0x0003)
        advance(Model.filterAckTimeoutMs + 1000)
        XCTAssertTrue(model.snapshot().filterPending.isEmpty)
        XCTAssertEqual(model.snapshot().filterMessage, "No answer from the Drone Pod for source 0003. Try again.")
    }

    private func savedIgnoreReady(_ saved: [Int] = [], build: Int = 0x0106) {
        useSavedListModel()
        model.restorePairedPod(pod0106, savedIgnoreIds: saved)
        model.setLink(Model.LinkStatus(kind: .usb, phase: .ready))
        podHealth(build)
        sender.sent.removeAll()
    }

    func testSixByteAcksMatchTheOldestWaitingRequestOfThatOp() {
        savedIgnoreReady([0x0003])
        model.ignoreSourceSaved(0x0001)
        model.ignoreSourceSaved(0x0002)
        model.unignoreSourceSaved(0x0003)
        XCTAssertEqual(sender.sent.count, 3)
        XCTAssertEqual(model.snapshot().filterPending.map(\.srcId), [1, 2, 3])
        filterAck(op: P.FILTER_OP_REMOVE, ok: 1, stored: 0)   // the op 4 answer, though sent last
        XCTAssertEqual(model.snapshot().filterPending.map(\.srcId), [1, 2])
        XCTAssertTrue(model.snapshot().savedIgnores.isEmpty)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 1)
        XCTAssertEqual(model.snapshot().savedIgnores, [1])
        filterAck(op: P.FILTER_OP_ADD, ok: 0, stored: 1)
        XCTAssertEqual(model.snapshot().savedIgnores, [1])
        XCTAssertTrue(model.snapshot().filterMessage!.contains("did not save source 0002"))
        XCTAssertTrue(model.snapshot().filterPending.isEmpty)
    }

    func testEightByteAcksMatchByEchoedSrcId() {
        savedIgnoreReady(build: 0x0107)
        model.ignoreSourceSaved(0x0001)
        model.ignoreSourceSaved(0x0002)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 1, srcId: 0x0002)   // out of order
        XCTAssertEqual(model.snapshot().savedIgnores, [2])
        XCTAssertEqual(model.snapshot().filterPending.map(\.srcId), [1])
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 2, srcId: 0)        // a FILTER_LIST answer: not ours
        filterAck(op: P.FILTER_OP_REPLACE, ok: 1, stored: 2, srcId: 1)    // op 0: not ours
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 2, srcId: 9)        // no request for 9
        filterAck(op: P.FILTER_OP_REMOVE, ok: 1, stored: 2, srcId: 1)     // wrong op
        XCTAssertEqual(model.snapshot().filterPending.map(\.srcId), [1])
        XCTAssertEqual(model.snapshot().savedIgnores, [2])
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 2, srcId: 1)
        XCTAssertEqual(model.snapshot().savedIgnores, [1, 2])
        XCTAssertTrue(model.snapshot().filterPending.isEmpty)
        // 0x0107: op 3 for an already-saved id answers ok and it stays saved
        model.ignoreSourceSaved(0x0001)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 2, srcId: 1)
        XCTAssertEqual(model.snapshot().savedIgnores, [1, 2])
    }

    func testPendingRequestsAreCappedAndTimeOutEachOnItsOwn() {
        savedIgnoreReady()
        for id in 1...(Model.maxPendingFilters + 1) { model.ignoreSourceSaved(id) }
        XCTAssertEqual(sender.sent.count, Model.maxPendingFilters)
        XCTAssertEqual(model.snapshot().filterMessage, "Waiting for the Drone Pod to answer earlier changes.")
        advance(5_000)
        model.ignoreSourceSaved(0x0100)                   // still full
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 1)
        model.ignoreSourceSaved(0x0100)
        XCTAssertEqual(sender.sent.count, Model.maxPendingFilters + 1)
        advance(Model.filterAckTimeoutMs - 4_000)         // the first three expire, 0x0100 does not
        XCTAssertEqual(model.snapshot().filterPending.map(\.srcId), [0x0100])
        model.detach(sender, reason: "test")
        XCTAssertTrue(model.snapshot().filterPending.isEmpty)
    }

    func testRemoveOfASavedButAbsentSourceOnBuild0106KeepsTheRow() throws {
        savedIgnoreReady([0x148C])                        // saved earlier; no longer heard
        model.unignoreSourceSaved(0x148C)
        XCTAssertEqual(sender.sent.count, 1)
        XCTAssertEqual(try P.decodeFrame(sender.sent[0].1).payload, [4, 0x8C, 0x14], "reuses the src_id op 3 was sent with")
        filterAck(op: P.FILTER_OP_REMOVE, ok: 0, stored: 1)  // 0x0106: not in the live table
        XCTAssertEqual(model.snapshot().savedIgnores, [0x148C])
        XCTAssertTrue(savedLists.isEmpty)
        let msg = model.snapshot().filterMessage!
        XCTAssertTrue(msg.contains("no longer tracks source 148C") && msg.contains("0x0107") && msg.contains("stays in this list"), msg)
        XCTAssertTrue(model.snapshot().filterPending.isEmpty)
    }

    func testRemoveOfASavedButAbsentSourceOnBuild0107() {
        savedIgnoreReady([0x148C, 0x2000], build: 0x0107)
        model.unignoreSourceSaved(0x148C)
        filterAck(op: P.FILTER_OP_REMOVE, ok: 1, stored: 1, srcId: 0x148C)
        XCTAssertEqual(model.snapshot().savedIgnores, [0x2000])
        XCTAssertTrue(model.snapshot().filterMessage!.hasPrefix("Removed source 148C"))
        model.unignoreSourceSaved(0x2000)
        filterAck(op: P.FILTER_OP_REMOVE, ok: 0, stored: 0, srcId: 0x2000)  // nothing under that id on the pod
        XCTAssertTrue(model.snapshot().savedIgnores.isEmpty)
        XCTAssertEqual(savedLists, [[0x2000], []])
        XCTAssertTrue(model.snapshot().filterMessage!.contains("had nothing saved under source 2000"))
    }

    func testDeskPairSourceIsTheDeskPodPairingState() {
        XCTAssertTrue(BtrxDeskPodText.healthLines(model.snapshot()).contains("Desk Pod pairing window: closed"))
        deskPair(1, seconds: 60, source: P.deskSrcLink)
        XCTAssertEqual(model.snapshot().windowSource, P.deskSrcLink)
        XCTAssertTrue(BtrxDeskPodText.healthLines(model.snapshot()).contains("Desk Pod pairing window: open, 60 s left (phone/USB-opened)"))
        deskPair(1, seconds: 60, source: P.deskSrcPairOpen)
        XCTAssertTrue(BtrxDeskPodText.healthLines(model.snapshot()).contains("Desk Pod pairing window: open, 60 s left (app-opened)"))
        deskPair(0)
        XCTAssertEqual(model.snapshot().windowSource, 0)
        XCTAssertEqual(BtrxDeskPodText.deskPairSourceText(P.deskSrcButton), "button")
    }

    func testSavedIgnoresRestoreWithThePodAndClearWhenItIsReplaced() {
        useSavedListModel()
        model.restorePairedPod(pod0106, savedIgnoreIds: [0x148C, 0, 0x10000, 7])
        XCTAssertEqual(model.snapshot().savedIgnores, [7, 0x148C])
        model.forgetPairedPod()
        XCTAssertTrue(model.snapshot().savedIgnores.isEmpty)
        XCTAssertEqual(savedLists.last, [])
        model.restorePairedPod(nil, savedIgnoreIds: [5])
        XCTAssertTrue(model.snapshot().savedIgnores.isEmpty)
    }

    func testHealth44LinesForBothPods() {
        model.restorePairedPod(pod0106)
        podHealth(0x0106) { p in
            P.putU32(&p, 34, 0); p[38] = UInt8(P.pairSrcNod); p[39] = 38; p[41] = 12
            p[43] = UInt8(P.EXCL_OWN | P.EXCL_OTHER_BOARD | P.EXCL_DJI)
        }
        var desk44 = [UInt8](repeating: 0, count: 44)
        desk44[4] = 0x06; desk44[5] = 0x01; desk44[6] = UInt8(P.roleDeskPod); P.putU32(&desk44, 34, 2)
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.HEALTH, payload: desk44)), meta: meta(desk))
        let lines = BtrxDeskPodText.healthLines(model.snapshot())
        XCTAssertTrue(lines.contains("Drone Pod last reset: power on or brown-out"), "\(lines)")
        XCTAssertTrue(lines.contains("Drone Pod pairing window last opened by: a nod (not paired yet); nods ignored while paired: 38"), "\(lines)")
        XCTAssertTrue(lines.contains("Drone Pod leaves out: its own board, the other board, DJI (not yet: the Desk Pod); 12 ad(s) left out per second"), "\(lines)")
        XCTAssertTrue(lines.contains("Desk Pod last reset: watchdog (0x2)"), "\(lines)")
        XCTAssertEqual(model.snapshot().deskHealthExt?.resetReason, 2)
        XCTAssertEqual(BtrxDeskPodText.excludeText(0), "nothing yet (its own board, the other board, the Desk Pod, DJI not known)")
        XCTAssertEqual(BtrxDeskPodText.resetReasonText(0x10004), "software reset, wake from pin (0x10004)")
    }

    // MARK: Desk Pod button confirmation (firmware 0x0108). Same as Android.

    private func confirmReq(_ action: Int, _ token: Int, _ state: Int, timeoutS: Int? = nil, backlog: Bool = false, version: Int = 1, from: UInt32? = nil) {
        deskSeq += 1
        let t = timeoutS ?? (state == P.CFM_ASK ? 15 : 0)
        let payload: [UInt8] = [UInt8(version), UInt8(action), UInt8(token & 0xFF), UInt8(token >> 8), UInt8(t), UInt8(state)]
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.CONFIRM_REQ, seq: deskSeq, payload: payload)), meta: meta(from ?? desk, backlog: backlog))
    }

    /// CONFIRM_RSP frames sent to the Desk Pod, as (token, answer).
    private func confirmRsps() -> [[Int]] {
        sender.sent.filter { $0.0 == desk }.compactMap { try? P.decodeFrame($0.1) }.filter { $0.type == P.CONFIRM_RSP }
            .map { let r = try! P.parseConfirmRsp($0.payload); return [r.token, r.answer] }
    }

    private func buttonReady(_ build: Int = 0x0108) {
        model.setLink(Model.LinkStatus(kind: .bluetooth, phase: .ready))
        model.onLocalRoleReport(role(build))
        model.setButtonPanelAvailable(true)
        sender.sent.removeAll()
    }

    func testKeepaliveEvery5sOnlyWhileReadyIn0108AndInFront() throws {
        buttonReady()
        advance(1_000)
        XCTAssertEqual(confirmRsps(), [[0, P.CFM_KEEPALIVE]])
        XCTAssertEqual(try P.decodeFrame(sender.sent[0].1).payload, [1, 0, 0, 3])
        XCTAssertEqual(try P.decodeFrame(sender.sent[0].1).podId, 0)
        for _ in 0..<4 { advance(1_000) }
        XCTAssertEqual(confirmRsps().count, 1, "4 s after: not yet")
        advance(1_000)
        XCTAssertEqual(confirmRsps().count, 2)
        for _ in 0..<10 { advance(1_000) }
        XCTAssertEqual(confirmRsps().count, 4)
        XCTAssertEqual(model.snapshot().buttonConfirmLink, .on)
        model.setButtonPanelAvailable(false)
        for _ in 0..<12 { advance(1_000) }
        XCTAssertEqual(confirmRsps().count, 4)
        XCTAssertEqual(model.snapshot().buttonConfirmLink, .notInFront)
        model.setButtonPanelAvailable(true)
        advance(1_000)
        XCTAssertEqual(confirmRsps().count, 5, "at once when back in front")
        model.setLink(Model.LinkStatus(kind: .bluetooth, phase: .retryWait))
        for _ in 0..<12 { advance(1_000) }
        XCTAssertEqual(confirmRsps().count, 5)
        XCTAssertEqual(model.snapshot().buttonConfirmLink, .noLink)
    }

    func testNoKeepaliveToADeskPodBefore0108() {
        buttonReady(0x0107)
        for _ in 0..<12 { advance(1_000) }
        XCTAssertTrue(confirmRsps().isEmpty)
        XCTAssertEqual(model.snapshot().buttonConfirmLink, .oldFirmware)
        XCTAssertEqual(BtrxDeskPodText.buttonConfirmLine(model.snapshot()), "Desk Pod button: acts at once (confirmation needs Desk Pod firmware 0x0108)")
        model.attach(sender); model.onConfigured(localNode: desk)
        XCTAssertEqual(model.snapshot().buttonConfirmLink, .oldFirmware)
        var desk44 = [UInt8](repeating: 0, count: 44)
        desk44[4] = 0x08; desk44[5] = 0x01; desk44[6] = UInt8(P.roleDeskPod)
        model.onFrame(try! P.decodeFrame(P.encodeFrame(P.HEALTH, payload: desk44)), meta: meta(desk))
        advance(1_000)
        XCTAssertEqual(confirmRsps(), [[0, P.CFM_KEEPALIVE]])
        model.attach(sender); model.onConfigured(localNode: desk); model.onLocalRoleReport(role(0x0107)); sender.sent.removeAll()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 9, P.CFM_ASK)
        advance(1_000)
        XCTAssertEqual(confirmRsps(), [[9, P.CFM_SHOWN], [0, P.CFM_KEEPALIVE]])
    }

    func testAskShowsThePanelAndAnswersShownAtOnce() throws {
        buttonReady()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x1234, P.CFM_ASK)
        XCTAssertEqual(confirmRsps(), [[0x1234, P.CFM_SHOWN]], "before any tick: well inside 3 s")
        XCTAssertEqual(try P.decodeFrame(sender.sent[0].1).payload, [1, 0x34, 0x12, 2])
        let v = try XCTUnwrap(model.snapshot().buttonConfirm)
        XCTAssertEqual(v.token, 0x1234); XCTAssertFalse(v.factoryReset); XCTAssertEqual(v.secondsLeft, 15)
        XCTAssertEqual(BtrxDeskPodText.confirmTitle(v), BtrxDeskPodText.confirmPairTitle)
        model.answerButtonConfirm(true)
        XCTAssertEqual(confirmRsps().last, [0x1234, P.CFM_CONFIRM])
        XCTAssertTrue(model.snapshot().buttonConfirm!.confirmSent)
        model.answerButtonConfirm(true)
        model.answerButtonConfirm(false)
        XCTAssertEqual(confirmRsps().count, 2)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x1234, P.CFM_DONE)
        XCTAssertNil(model.snapshot().buttonConfirm)
        XCTAssertEqual(model.snapshot().buttonNotice?.text, "Desk Pod button: pairing opened (confirmed).")
        advance(BtrxButtonConfirm.noticeMs)
        XCTAssertNil(model.snapshot().buttonNotice)
        XCTAssertNotNil(model.snapshot().buttonLastNotice)
    }

    func testCancelAndDroppedAndDoneWithoutAskingClose() {
        buttonReady()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 7, P.CFM_ASK)
        model.answerButtonConfirm(false)
        XCTAssertEqual(confirmRsps().last, [7, P.CFM_CANCEL])
        XCTAssertNil(model.snapshot().buttonConfirm)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 7, P.CFM_DROPPED)
        XCTAssertEqual(model.snapshot().buttonNotice?.text, "Desk Pod button: cancelled; nothing was changed.")
        confirmReq(P.CFM_ACTION_DESK_PAIR, 8, P.CFM_ASK)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 8, P.CFM_DROPPED)
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 9, P.CFM_ASK)
        XCTAssertEqual(model.snapshot().buttonConfirm?.token, 9)
        XCTAssertEqual(model.snapshot().buttonConfirm?.factoryReset, true)
        XCTAssertEqual(BtrxDeskPodText.confirmTitle(model.snapshot().buttonConfirm!), BtrxDeskPodText.confirmResetTitle)
        model.answerButtonConfirm(false)
        model.setButtonPanelAvailable(false)
        sender.sent.removeAll()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 10, P.CFM_ASK)
        XCTAssertNil(model.snapshot().buttonConfirm)
        XCTAssertTrue(confirmRsps().isEmpty, "not in front: no shown")
        confirmReq(P.CFM_ACTION_DESK_PAIR, 10, P.CFM_DONE_NO_APP)
        XCTAssertEqual(model.snapshot().buttonNotice?.text, "Desk Pod button: pairing opened.")
        XCTAssertTrue(log.infos.contains { $0.contains("desk button: action=desk-pair token=10 state=done-no-app") })
    }

    func testPanelClosesAfter15sAndUnknownTokensAreIgnored() {
        buttonReady()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_ASK)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0202, P.CFM_DONE)
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 0x0303, P.CFM_DONE_NO_APP)
        XCTAssertEqual(model.snapshot().buttonConfirm?.token, 0x0101)
        XCTAssertNil(model.snapshot().buttonNotice)
        XCTAssertNil(model.snapshot().deskResetAtMs)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_ASK, version: 2)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0404, P.CFM_ASK, backlog: true)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0505, P.CFM_ASK, from: pod)
        XCTAssertEqual(model.snapshot().buttonConfirm?.token, 0x0101)
        XCTAssertEqual(confirmRsps().filter { $0[0] != 0 }, [[0x0101, P.CFM_SHOWN]])
        advance(14_000)
        XCTAssertEqual(model.snapshot().buttonConfirm?.secondsLeft, 1)
        advance(1_000)
        XCTAssertNil(model.snapshot().buttonConfirm)
        XCTAssertEqual(model.snapshot().buttonNotice?.text, "Desk Pod button: no answer in 15 s; nothing was changed.")
        model.answerButtonConfirm(true)
        XCTAssertFalse(confirmRsps().contains { $0[1] == P.CFM_CONFIRM })
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_DROPPED)
        XCTAssertEqual(model.snapshot().buttonLastNotice?.text, "Desk Pod button: request dropped (no answer in time, or replaced); nothing was changed.")
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_DROPPED)
        XCTAssertEqual(log.infos.filter { $0.contains("token=257 state=dropped:") }.count, 1)
    }

    func testConfirmedFactoryResetAsksTheRoleAndSaysTheDeskPodIsUnpaired() throws {
        model.restorePairedPod(pod0106)
        buttonReady()
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 0x2222, P.CFM_ASK)
        model.answerButtonConfirm(true)
        sender.sent.removeAll()
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 0x2222, P.CFM_DONE)
        let s = model.snapshot()
        XCTAssertNil(s.buttonConfirm)
        XCTAssertEqual(s.deskResetAtMs, now)
        XCTAssertEqual(s.buttonNotice?.text, BtrxButtonConfirm.resetDone)
        XCTAssertEqual(try sender.sent.filter { $0.0 == desk }.map { try P.decodeFrame($0.1).type }, [P.ROLE_GET])
        XCTAssertEqual(s.pairedPod, pod0106, "the app's record stays: Re-pair uses it")
        advance(BtrxButtonConfirm.noticeMs + 1_000)
        XCTAssertNotNil(model.snapshot().buttonNotice, "the reset notice stays longer")
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x2223, P.CFM_ASK)
        model.detach(sender, reason: "test")
        XCTAssertNil(model.snapshot().buttonConfirm)
        XCTAssertNotNil(model.snapshot().deskResetAtMs)
    }

    // MARK: "Ignore all except this" (Nov 17). Same cases as Android.

    private func sentMutes() -> [(Int, Int)] {
        sender.sent.compactMap { try? P.decodeFrame($0.1) }.filter { $0.type == P.STEER_MUTE }
            .map { (Int($0.payload[0]), Int($0.payload[1]) | Int($0.payload[2]) << 8) }
    }

    private func readyPod0109() {
        useSavedListModel()
        model.restorePairedPod(pod0106)
        model.setLink(Model.LinkStatus(kind: .usb, phase: .ready))
        podHealth(0x0109)
        sender.sent.removeAll()
    }

    func testIgnoreAllExceptIsPacedRecordedAndRestored() {
        readyPod0109()
        model.ignoreSourceSaved(0x0100)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 1, srcId: 0x0100)
        sender.sent.removeAll()
        let ids = (1...10).map { 0x1000 + $0 }
        model.ignoreAllExcept(ids + [0x0100], keepId: "QBXFvg", localCount: 27)
        XCTAssertEqual(sentMutes().map(\.0), Array(repeating: P.MUTE_IGNORE, count: 4))
        XCTAssertEqual(sentMutes().map(\.1), Array(ids.prefix(Model.maxPendingFilters)))
        XCTAssertTrue(log.debugs.contains { $0.hasPrefix("ignore all except QBXFvg: 27 ignored in the app; STEER_MUTE ignore queued for 10 src_id(s)") })
        var st = model.snapshot().ignoreAll!
        XCTAssertEqual(st.queued, 6); XCTAssertEqual(st.waiting, 4); XCTAssertTrue(st.active)
        var stored = 1
        for id in ids {
            if id == 0x1003 { filterAck(op: P.FILTER_OP_ADD, ok: 0, stored: stored, srcId: id) }
            else { stored += 1; filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: stored, srcId: id) }
        }
        XCTAssertEqual(sentMutes().count, 10)
        st = model.snapshot().ignoreAll!
        XCTAssertFalse(st.active)
        XCTAssertEqual(st.saved, ids.filter { $0 != 0x1003 })
        XCTAssertEqual(st.refused, 1)
        XCTAssertEqual(model.snapshot().podListStored, 10)
        XCTAssertTrue(model.snapshot().filterMessage!.hasPrefix("Ignore all except: 9 saved on the Drone Pod, 1 refused"))
        sender.sent.removeAll()
        model.restoreIgnoreAll()
        let kept = ids.filter { $0 != 0x1003 }
        XCTAssertEqual(sentMutes().map(\.1), Array(kept.prefix(4)))
        for id in kept { stored -= 1; filterAck(op: P.FILTER_OP_REMOVE, ok: 1, stored: stored, srcId: id) }
        XCTAssertEqual(sentMutes().map(\.1), kept)
        XCTAssertTrue(sentMutes().allSatisfy { $0.0 == P.MUTE_UNIGNORE && $0.1 != 0x0100 })
        XCTAssertEqual(model.snapshot().savedIgnores, [0x0100])
        st = model.snapshot().ignoreAll!
        XCTAssertTrue(st.saved.isEmpty); XCTAssertEqual(st.removed, 9); XCTAssertTrue(st.restoring)
        XCTAssertEqual(model.snapshot().filterMessage, "Restore ignored: removed 9 from the Drone Pod.")
    }

    func testIgnoreAllExceptStopsAtThePodsListLimit() {
        readyPod0109()
        model.ignoreSourceSaved(0x0100)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 30, srcId: 0x0100)
        sender.sent.removeAll()
        model.ignoreAllExcept((1...6).map { 0x2000 + $0 }, keepId: "QBXFvg", localCount: 6)
        XCTAssertEqual(sentMutes().map(\.1), [0x2001, 0x2002])
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 31, srcId: 0x2001)
        filterAck(op: P.FILTER_OP_ADD, ok: 1, stored: 32, srcId: 0x2002)
        let st = model.snapshot().ignoreAll!
        XCTAssertEqual(sentMutes().count, 2)
        XCTAssertEqual(st.overLimit, 4)
        XCTAssertFalse(st.active)
        XCTAssertTrue(model.snapshot().filterMessage!.contains("4 left off (its list holds 32)"))
        XCTAssertTrue(log.debugs.contains("ignore all except: the Drone Pod's list is full (32 entries); 4 not sent"))
    }

    func testIgnoreAllExceptTimeoutsAndLinkLoss() {
        readyPod0109()
        model.ignoreAllExcept((1...6).map { 0x3000 + $0 }, keepId: "QBXFvg", localCount: 6)
        XCTAssertEqual(sentMutes().count, 4)
        advance(Model.filterAckTimeoutMs + 1000)
        XCTAssertEqual(sentMutes().count, 6)
        XCTAssertEqual(model.snapshot().ignoreAll!.refused, 4)
        model.detach(sender, reason: "test")
        XCTAssertFalse(model.snapshot().ignoreAll!.active)
        XCTAssertTrue(log.warns.contains { $0.hasPrefix("ignore all except: stopped, 2 request(s) dropped: the link to the Desk Pod was lost") })
    }
}
