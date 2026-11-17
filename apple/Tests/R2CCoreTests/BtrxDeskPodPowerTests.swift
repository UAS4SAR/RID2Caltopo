import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxDeskPodPowerTest.kt: Desk Pod power diagnostics (Ken, Nov 17):
/// went silent, restarted, DeviceMetrics, sent-frame log.
final class BtrxDeskPodPowerTests: XCTestCase {
    typealias P = BtrxProtocol
    typealias M = MeshtasticPhoneAPI
    typealias Model = BtrxDeskPodModel
    typealias Power = BtrxDeskPodPower
    typealias DM = MeshtasticPhoneAPI.DeviceMetrics

    // MARK: classification: the Nov 16 training reasons

    func testTrainingDropsAreSilent() {
        for r in ["a ToRadio write failed (status 133)", "a FromRadio read failed (status 133)", "disconnected (status 8)",
                  "a Bluetooth operation timed out", "connection timed out", "connection failed (status 147)",
                  "no connection after 20 s", "a FromRadio read got no answer in 10 s", "disconnected",
                  "disconnected (The connection has timed out unexpectedly.)"] {
            XCTAssertTrue(Power.isSilentDrop(r), r)
        }
    }

    func testDeliberateOrSetupEndsAreNotSilent() {
        for r in ["patient connect replaced by a direct connect", "USB debug link turned off", "iPadOS refused a Bluetooth operation",
                  "not a Desk Pod (role=1)", "the device does not offer the Meshtastic phone API (service missing)",
                  "connection failed: not paired. Enter the PIN", "disconnected: the Desk Pod lost its pairing. Forget it",
                  "disconnected (status 19)", "Bluetooth state 4", "first"] {
            XCTAssertFalse(Power.isSilentDrop(r), r)
        }
    }

    // MARK: silent and restart

    func testSilentUntilTheNextSession() {
        let p = Power()
        XCTAssertEqual(p.onDrop(reason: "a FromRadio read failed (status 133)", nowMs: 1_000),
                       "Desk Pod went silent — check its power (a FromRadio read failed (status 133))")
        XCTAssertEqual(p.silentSinceMs, 1_000)
        p.onSessionStarted()
        XCTAssertNil(p.silentSinceMs)
        XCTAssertNil(p.onDrop(reason: "USB detached", nowMs: 2_000))
        XCTAssertNil(p.silentSinceMs)
    }

    func testLowUptimeAfterADropIsARestart() {
        let p = Power()
        _ = p.onDrop(reason: "a ToRadio write failed (status 133)", nowMs: 1_000_000)
        let line = p.onDeskHealth(uptimeS: 16, resetReason: 0, arrivalMs: 1_000_000 + 262_990)
        XCTAssertEqual(line, "Desk Pod restarted (power-on): HEALTH uptime_s=16, 262 s after the link dropped; "
                       + "it came back about 246 s after the link dropped")
        XCTAssertEqual(p.restartNotice(nowMs: 1_000_000 + 262_990), "Desk Pod restarted (power-on)")
        XCTAssertEqual(p.restartNotice(nowMs: 1_000_000 + 262_990 + 60_000), "Desk Pod restarted (power-on)")
        XCTAssertNil(p.restartNotice(nowMs: 1_000_000 + 262_990 + 60_001))
        XCTAssertEqual(p.restart?.downMs, 246_990)
        XCTAssertNil(p.onDeskHealth(uptimeS: 28, resetReason: 0, arrivalMs: 1_000_000 + 275_000))
    }

    func testNoRestartWithoutADropOrWithHighUptimeOrLateHealth() {
        let p = Power()
        XCTAssertNil(p.onDeskHealth(uptimeS: 12, resetReason: 0, arrivalMs: 5_000))
        _ = p.onDrop(reason: "disconnected (status 8)", nowMs: 10_000)
        XCTAssertNil(p.onDeskHealth(uptimeS: 60, resetReason: 0, arrivalMs: 20_000))
        XCTAssertNil(p.onDeskHealth(uptimeS: 5, resetReason: 0, arrivalMs: 10_000 + Power.restartAfterDropMs + 1))
        XCTAssertNil(p.restart)
    }

    func testRestartTextNamesTheResetReason() {
        XCTAssertEqual(Power.restartText(0), "Desk Pod restarted (power-on)")
        XCTAssertEqual(Power.restartText(2), "Desk Pod restarted (watchdog)")
        XCTAssertEqual(Power.restartText(4), "Desk Pod restarted (soft-reset)")
        XCTAssertEqual(Power.restartText(nil), "Desk Pod restarted")
    }

    func testMetricsLines() {
        XCTAssertEqual(Power.metricsLine(DM(batteryLevel: 101, voltage: 5.02, uptimeSeconds: nil)), "Desk Pod power: external (battery_level 101), 5.02 V")
        XCTAssertEqual(Power.metricsLine(DM(batteryLevel: 87, voltage: 3.95, uptimeSeconds: 10)), "Desk Pod battery: 87%, 3.95 V")
        XCTAssertEqual(Power.metricsLine(DM(batteryLevel: 40, voltage: nil, uptimeSeconds: nil)), "Desk Pod battery: 40%")
        XCTAssertEqual(Power.metricsLine(DM(batteryLevel: nil, voltage: 4.1, uptimeSeconds: nil)), "Desk Pod voltage: 4.10 V")
        XCTAssertNil(Power.metricsLine(DM(batteryLevel: nil, voltage: nil, uptimeSeconds: 5)))
    }

    // MARK: map chip

    private func ble(_ phase: Model.LinkPhase, _ failed: Int = 0) -> Model.LinkStatus {
        Model.LinkStatus(kind: .bluetooth, phase: phase, failedAttempts: failed)
    }

    private func chip(_ link: Model.LinkStatus, silent: Bool = false, notice: String? = nil, bluetoothOff: Bool = false) -> BtrxMapStatus.Status {
        BtrxMapStatus.status(setUp: true, link: link, bluetoothOff: bluetoothOff, pausedForUSB: false, dronePodReporting: true,
                             deskPodSilent: silent, deskPodNotice: notice)!
    }

    func testChipSaysTheDeskPodWentSilent() {
        let silent = "BTRX: Inactive - Desk Pod went silent — check its power"
        XCTAssertEqual(chip(ble(.retryWait, 1), silent: true).text, silent)
        XCTAssertEqual(chip(ble(.scanning, 2), silent: true).text, silent)
        XCTAssertEqual(chip(ble(.connecting), silent: true).text, silent)
        XCTAssertEqual(chip(ble(.retryWait, 1)).text, "BTRX: Inactive - BT Down")
        XCTAssertEqual(chip(ble(.configuring), silent: true).text, "BTRX: Inactive - Connecting")
        XCTAssertEqual(chip(ble(.retryWait, 1), silent: true, bluetoothOff: true).text, "BTRX: Inactive - Bluetooth off")
        XCTAssertEqual(chip(Model.LinkStatus(kind: .none, phase: .off), silent: true).text, "BTRX: Inactive - Link off")
        XCTAssertEqual(chip(Model.LinkStatus(kind: .usb, phase: .configuring), silent: true).text, "BTRX: Inactive - Connecting")
        XCTAssertFalse(chip(ble(.retryWait), silent: true).active)
    }

    func testChipWarnsOfARestartWhileActive() {
        let s = chip(ble(.ready), notice: "Desk Pod restarted (power-on)")
        XCTAssertEqual(s.text, "BTRX: Active - Desk Pod restarted (power-on)")
        XCTAssertTrue(s.active); XCTAssertTrue(s.warning)
        XCTAssertEqual(chip(ble(.ready)).text, "BTRX: Active")
    }

    // MARK: the model

    final class FakeSender: BtrxFrameSender {
        var isReady = true
        func nextSeq() -> Int { 1 }
        func sendFrame(to: UInt32, frame: [UInt8]) {}
    }

    var now: Int64 = 1_000_000
    let desk: UInt32 = 0x66C8_42CA

    private func deskHealth(_ model: Model, uptime: UInt32, reset: UInt32 = 0) throws {
        var h = [UInt8](repeating: 0, count: P.healthExtLen)
        P.putU32(&h, 0, uptime); h[6] = 3; P.putU32(&h, 34, reset)
        model.onFrame(try P.decodeFrame(P.encodeFrame(P.HEALTH, payload: h)),
                      meta: BtrxRadioMeta(fromNode: desk, toNode: desk, rxRssi: 0, rxSnr: 0, arrivalMs: now, backlog: false))
    }

    func testModelReplaysTheTrainingDrop() throws {
        let log = BtrxTestLogger()
        let model = Model(log: log, clock: { [unowned self] in self.now })
        let first = FakeSender()
        model.attach(first); model.onConfigured(localNode: desk)
        try deskHealth(model, uptime: 12)
        now += 22_800
        model.detach(first, reason: "a ToRadio write failed (status 133)")
        var s = model.snapshot()
        XCTAssertTrue(s.deskSilent)
        XCTAssertTrue(log.warns.contains("Desk Pod went silent — check its power (a ToRadio write failed (status 133))"), "\(log.warns)")
        XCTAssertTrue(BtrxDeskPodText.deskPowerLines(s).contains("Desk Pod went silent — check its power (link lost: a ToRadio write failed (status 133))"))
        now += 259_000
        let second = FakeSender()
        model.attach(second); model.onConfigured(localNode: desk)
        XCTAssertFalse(model.snapshot().deskSilent)
        try deskHealth(model, uptime: 16)
        s = model.snapshot()
        XCTAssertEqual(s.deskRestartNotice, "Desk Pod restarted (power-on)")
        XCTAssertTrue(log.warns.contains { $0.hasPrefix("Desk Pod restarted (power-on): HEALTH uptime_s=16, 259 s after the link dropped") }, "\(log.warns)")
        XCTAssertEqual(BtrxDeskPodText.deskPowerLines(s), ["Desk Pod restarted (power-on) 0 s ago (uptime 16 s; back about 243 s after the link dropped)"])
        now += 61_000
        XCTAssertNil(model.snapshot().deskRestartNotice)
        XCTAssertNotNil(model.snapshot().deskRestart)
    }

    func testModelKeepsTheDeskPodsDeviceMetrics() {
        let model = Model(log: BtrxTestLogger(), clock: { [unowned self] in self.now })
        model.onDeskDeviceMetrics(DM(batteryLevel: 101, voltage: 5.01, uptimeSeconds: 30), source: "node_info")
        now += 5_000
        let s = model.snapshot()
        XCTAssertEqual(s.deskDeviceMetrics, DM(batteryLevel: 101, voltage: 5.01, uptimeSeconds: 30))
        XCTAssertEqual(BtrxDeskPodText.deskPowerLines(s), ["Desk Pod power: external (battery_level 101), 5.01 V, 5 s ago"])
    }

    // MARK: phone API: standard DeviceMetrics

    private func deviceMetrics(_ level: Int?, _ volts: Float?, _ uptime: UInt64?) -> [UInt8] {
        let w = M.ProtoWriter()
        if let level { w.varint(1, UInt64(level)) }
        if let volts { w.float32(2, volts) }
        w.float32(3, 1.5) // channel_utilization: skipped
        if let uptime { w.varint(5, uptime) }
        return w.bytes()
    }

    private func nodeInfo(_ num: UInt32, _ metrics: [UInt8]?) -> [UInt8] {
        let n = M.ProtoWriter().varint(1, UInt64(num)).bytesField(2, [10, 2, 0x68, 0x69])
        if let metrics { n.bytesField(6, metrics) }
        return M.ProtoWriter().bytesField(4, n.bytes()).bytes()
    }

    func testNodeInfoCarriesDeviceMetrics() throws {
        XCTAssertEqual(try M.parseFromRadio(nodeInfo(desk, deviceMetrics(101, 4.98, 120))),
                       .nodeInfo(nodeNum: desk, metrics: DM(batteryLevel: 101, voltage: 4.98, uptimeSeconds: 120)))
        XCTAssertEqual(try M.parseFromRadio(nodeInfo(desk, nil)), .nodeInfo(nodeNum: desk, metrics: nil))
        XCTAssertEqual(DM(batteryLevel: 101, voltage: 4.98, uptimeSeconds: 120).logText(), "battery_level=101 voltage=4.98 uptime_s=120")
        XCTAssertEqual(DM(batteryLevel: nil, voltage: nil, uptimeSeconds: 9).logText(), "battery_level=- voltage=- uptime_s=9")
    }

    func testTelemetryDeviceMetricsOnly() throws {
        let tel = M.ProtoWriter().fixed32(1, 1234).bytesField(2, deviceMetrics(55, 3.8, nil)).bytes()
        XCTAssertEqual(try M.parseTelemetryDeviceMetrics(tel), DM(batteryLevel: 55, voltage: 3.8, uptimeSeconds: nil))
        let env = M.ProtoWriter().fixed32(1, 1234).bytesField(3, [0x0d, 0, 0, 0, 0]).bytes()
        XCTAssertNil(try M.parseTelemetryDeviceMetrics(env))
    }

    // MARK: the client

    final class MetricsRecorder: BtrxClientListener {
        var seen: [(DM, String)] = []
        func onDeskDeviceMetrics(_ metrics: DM, source: String) { seen.append((metrics, source)) }
    }

    var sent: [[UInt8]] = []

    private func makeClient(_ log: BtrxTestLogger, _ rec: MetricsRecorder) throws -> BtrxClient {
        let c = BtrxClient(send: { [unowned self] in self.sent.append($0) }, log: log, listener: rec, rng: BtrxTestRandom(state: 1),
                           framing: .serial, clock: { [unowned self] in self.now })
        c.start()
        var out: [[UInt8]] = []
        let d = M.StreamDecoder(onMessage: { out.append($0) })
        sent.forEach { d.feed($0) }
        let r = M.ProtoReader(out.first!); _ = try r.tag(); let id = UInt32(try r.varint())
        c.onBytes(M.frame(M.ProtoWriter().bytesField(3, M.ProtoWriter().varint(1, UInt64(desk)).bytes()).bytes()))
        c.onBytes(M.frame(nodeInfo(0x1111_1111, deviceMetrics(20, 3.6, nil))))
        c.onBytes(M.frame(nodeInfo(desk, deviceMetrics(101, 5.0, 40))))
        c.onBytes(M.frame(M.ProtoWriter().varint(7, UInt64(id)).bytes()))
        return c
    }

    func testClientPassesOnlyTheDeskPodsMetrics() throws {
        let log = BtrxTestLogger(), rec = MetricsRecorder()
        let c = try makeClient(log, rec)
        let tel = M.ProtoWriter().fixed32(1, 1).bytesField(2, deviceMetrics(100, 4.2, 60)).bytes()
        c.onBytes(M.frame(MeshtasticPhoneAPITests.fromRadioPacket(from: desk, to: 0xFFFF_FFFF, rssi: 0, snr: 0, port: M.telemetryAppPort, payload: tel)))
        let other = M.ProtoWriter().fixed32(1, 1).bytesField(2, deviceMetrics(10, 3.4, 60)).bytes()
        c.onBytes(M.frame(MeshtasticPhoneAPITests.fromRadioPacket(from: 0x2222_2222, to: 0xFFFF_FFFF, rssi: 0, snr: 0, port: M.telemetryAppPort, payload: other)))
        XCTAssertEqual(rec.seen.map { $0.0 }, [DM(batteryLevel: 101, voltage: 5.0, uptimeSeconds: 40), DM(batteryLevel: 100, voltage: 4.2, uptimeSeconds: 60)])
        XCTAssertEqual(rec.seen.map { $0.1 }, ["node_info", "telemetry"])
        XCTAssertTrue(log.debugs.contains("Desk Pod device metrics (node_info): battery_level=101 voltage=5.00 uptime_s=40"))
        XCTAssertTrue(log.debugs.contains("Desk Pod device metrics (telemetry): battery_level=100 voltage=4.20 uptime_s=60"))
    }

    func testSentFramesAreLoggedAndTheKeepaliveIsRateLimited() throws {
        let log = BtrxTestLogger(), rec = MetricsRecorder()
        let c = try makeClient(log, rec)
        let keepalive = P.confirmRspFrame(seq: 1, token: 0, answer: P.CFM_KEEPALIVE)
        for _ in 0..<5 { c.sendFrame(to: desk, frame: keepalive); now += 5_000 }
        let ka = "tx to=!66c842ca \(BtrxFrameLog.format(try P.decodeFrame(keepalive)))"
        XCTAssertEqual(log.debugs.filter { $0 == ka }.count, 3)
        XCTAssertEqual(log.infos.filter { $0 == ka }.count, 2)
        now += BtrxClient.txLogIntervalMs
        c.sendFrame(to: desk, frame: keepalive)
        XCTAssertTrue(log.debugs.contains("\(ka) (+2 like it not logged since the last one)"), "\(log.debugs)")
        let confirm = P.confirmRspFrame(seq: 2, token: 7, answer: P.CFM_CONFIRM)
        for _ in 0..<4 { c.sendFrame(to: desk, frame: confirm) }
        XCTAssertEqual(log.debugs.filter { $0 == "tx to=!66c842ca \(BtrxFrameLog.format(try! P.decodeFrame(confirm)))" }.count, 4)
        let ping = P.timePingFrame(seq: 3, podId: 0x45D3_5AF0, pingSeq: 9, t1Ms: 123_456)
        for _ in 0..<4 { c.sendFrame(to: 0x0189_68BE, frame: ping) }
        let pingLine = "tx to=!018968be \(BtrxFrameLog.format(try P.decodeFrame(ping)))"
        XCTAssertEqual(log.debugs.filter { $0 == pingLine }.count, 3)
        XCTAssertTrue(pingLine.contains("TIME_PING") && pingLine.contains("ping_seq=9"), pingLine)
        XCTAssertTrue(ka.contains("CONFIRM_RSP") && ka.contains("answer=keepalive"), ka)
    }
}
