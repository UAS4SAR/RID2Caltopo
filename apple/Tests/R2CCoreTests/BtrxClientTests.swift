import XCTest
@testable import R2CCore

final class BtrxTestLogger: BtrxLogger {
    var infos: [String] = []
    var warns: [String] = []
    func info(_ message: String) { infos.append(message) }
    func warn(_ message: String) { warns.append(message) }
}

/// Deterministic generator so config ids and packet ids repeat between runs.
struct BtrxTestRandom: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return state
    }
}

/// Swift copy of Android's BtrxClientTest.kt (serial framing) plus the Bluetooth framing.
final class BtrxClientTests: XCTestCase {
    typealias M = MeshtasticPhoneAPI
    typealias P = BtrxProtocol

    final class Recorder: BtrxClientListener {
        var metas: [BtrxRadioMeta] = []
        var refused: [(UInt32, Int)] = []
        var configured: UInt32?
        func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) { metas.append(meta) }
        func onVersionRefused(fromNode: UInt32, version: Int) { refused.append((fromNode, version)) }
        func onConfigured(localNode: UInt32) { configured = localNode }
    }

    let deskNode: UInt32 = 0x0A0B_0C0D
    let starboard: UInt32 = 0x4C8C_125B
    var sent: [[UInt8]] = []
    let log = BtrxTestLogger()
    let recorder = Recorder()
    var now: Int64 = 10_000

    func makeClient(_ framing: BtrxClient.Framing) -> BtrxClient {
        BtrxClient(send: { [unowned self] in self.sent.append($0) }, log: log, listener: recorder, rng: BtrxTestRandom(state: 1),
                   framing: framing, clock: { [unowned self] in self.now })
    }

    func serialMessages() -> [[UInt8]] {
        var out: [[UInt8]] = []
        let d = M.StreamDecoder(onMessage: { out.append($0) })
        sent.forEach { d.feed($0) }
        return out
    }

    func packet(_ from: UInt32, _ frame: [UInt8], port: Int = 256) -> [UInt8] {
        MeshtasticPhoneAPITests.fromRadioPacket(from: from, to: 0xFFFF_FFFF, rssi: -60, snr: 7.5, port: port, payload: frame)
    }

    func configId(_ want: [UInt8]) throws -> UInt32 {
        let r = M.ProtoReader(want); _ = try r.tag(); return UInt32(try r.varint())
    }

    /// Handshake, then the serial quiet gap that ends the connect backlog (and sends ROLE_GET).
    func handshakeSerial(_ c: BtrxClient, endBacklog: Bool = true) throws {
        c.start()
        let id = try configId(serialMessages().first!)
        c.onBytes(M.frame(M.ProtoWriter().bytesField(3, M.ProtoWriter().varint(1, UInt64(deskNode)).bytes()).bytes()))
        c.onBytes(M.frame(M.ProtoWriter().bytesField(10, [8, 0]).bytes()))
        c.onBytes(M.frame(M.ProtoWriter().varint(7, UInt64(id)).bytes()))
        if endBacklog {
            now += BtrxClient.serialQuietGapMs
            c.checkQuietGap()
        }
    }

    func testSerialStartSendsWakeBytesThenWantConfig() {
        let c = makeClient(.serial)
        c.start()
        XCTAssertEqual(32, sent[0].count)
        XCTAssertTrue(sent[0].allSatisfy { $0 == 0xC3 })
        XCTAssertEqual(0x18, serialMessages()[0][0])
        XCTAssertEqual(.configuring, c.state)
    }

    func testRoleGetToTheLocalNodeFollowsTheSerialQuietGap() throws {
        let c = makeClient(.serial)
        try handshakeSerial(c, endBacklog: false)
        XCTAssertEqual(.ready, c.state)
        XCTAssertEqual(deskNode, c.localNode)
        XCTAssertEqual(deskNode, recorder.configured)
        XCTAssertEqual(1, serialMessages().count) // still in the connect burst
        now += BtrxClient.serialQuietGapMs - 1
        c.checkQuietGap()
        XCTAssertEqual(1, serialMessages().count)
        now += 1
        c.checkQuietGap()
        XCTAssertTrue(log.infos.contains { $0.hasPrefix("connect backlog drained after a 300 ms quiet gap: 0 frame(s)") })
        let toRadio = serialMessages().last!
        let r = M.ProtoReader(toRadio)
        XCTAssertTrue(try r.tag() == (1, 2))
        let pr = M.ProtoReader(try r.lengthDelimited())
        var to: UInt32 = 0, frame: [UInt8]?, port = -1
        while pr.hasMore {
            let (f, w) = try pr.tag()
            switch f {
            case 2: to = try pr.fixed32()
            case 4:
                let d = M.ProtoReader(try pr.lengthDelimited())
                while d.hasMore {
                    let (df, dw) = try d.tag()
                    switch df { case 1: port = Int(try d.varint()); case 2: frame = try d.lengthDelimited(); default: try d.skip(dw) }
                }
            default: try pr.skip(w)
            }
        }
        XCTAssertEqual(deskNode, to)
        XCTAssertEqual(256, port)
        XCTAssertEqual(BtrxProtocolTests.example("ROLE_GET"), frame)
    }

    func testStaleConfigCompleteIsIgnored() {
        let c = makeClient(.serial)
        c.start()
        c.onBytes(M.frame(M.ProtoWriter().varint(7, 12345).bytes()))
        XCTAssertEqual(.configuring, c.state)
    }

    func testDeskPodRoleReportIsRecorded() throws {
        let c = makeClient(.serial)
        try handshakeSerial(c)
        let report = P.encodeFrame(P.ROLE_REPORT, seq: 4, payload: P.fromHex("030500000000015b128c4c00000000000000000002"))
        c.onBytes(M.frame(MeshtasticPhoneAPITests.fromRadioPacket(from: deskNode, to: deskNode, rssi: 0, snr: 0, port: 256, payload: report)))
        XCTAssertEqual(P.roleDeskPod, c.localRole?.role)
        XCTAssertTrue(log.infos.contains { $0.hasPrefix("Desk Pod confirmed: role=3 build_hash=0x0100 (supported)") })
    }

    func testEveryFrameIsDecodedIntoTheLogAndCounted() throws {
        let c = makeClient(.serial)
        try handshakeSerial(c)
        for _ in 0..<3 { c.onBytes(M.frame(packet(starboard, BtrxProtocolTests.example("DETECT")))) }
        c.onBytes(M.frame(packet(starboard, BtrxProtocolTests.example("HEALTH"))))
        c.onBytes(M.frame(packet(starboard, [1, 2, 3], port: 1)))
        XCTAssertEqual(3, c.frameCount("DETECT"))
        XCTAssertEqual(1, c.frameCount("HEALTH"))
        XCTAssertTrue(log.infos.contains { $0.hasPrefix("rx from=!4c8c125b rssi=-60 snr=7.5 DETECT seq=201") })
        XCTAssertTrue(c.summary().contains("DETECT=3 HEALTH=1"), c.summary())
        XCTAssertTrue(c.summary().contains("other-port packets=1"))
    }

    func testSerialFramesUntilTheFirstQuietGapAfterConfigCompleteAreBacklog() throws {
        let c = makeClient(.serial)
        c.start()
        let id = try configId(serialMessages().first!)
        let detect = BtrxProtocolTests.example("DETECT")
        let oldReport = P.encodeFrame(P.ROLE_REPORT, seq: 4, payload: P.fromHex("030500000000015b128c4c00000000000000000002"))
        c.onBytes(M.frame(M.ProtoWriter().bytesField(3, M.ProtoWriter().varint(1, UInt64(deskNode)).bytes()).bytes()))
        c.onBytes(M.frame(packet(starboard, detect))) // before config_complete
        c.onBytes(M.frame(M.ProtoWriter().varint(7, UInt64(id)).bytes()))
        now += 120
        c.onBytes(M.frame(packet(starboard, detect))) // queued burst
        now += 250 // gaps inside the burst are shorter than 300 ms
        c.onBytes(M.frame(MeshtasticPhoneAPITests.fromRadioPacket(from: deskNode, to: deskNode, rssi: 0, snr: 0, port: 256, payload: oldReport)))
        XCTAssertNil(c.localRole) // a ROLE_REPORT in the burst is stale
        XCTAssertTrue(log.infos.contains { $0.hasPrefix("stale ROLE_REPORT in the connect backlog ignored") })
        XCTAssertEqual(1, serialMessages().count) // no ROLE_GET yet
        now += 400 // quiet gap: the next bytes are live, and ROLE_GET went out first
        c.onBytes(M.frame(packet(starboard, detect)))
        XCTAssertEqual([true, true, true, false], recorder.metas.map(\.backlog))
        XCTAssertEqual(2, c.backlogCount("DETECT"))
        XCTAssertEqual(1, c.frameCount("DETECT"))
        XCTAssertEqual(2, serialMessages().count)
        XCTAssertTrue(log.infos.contains { $0.hasPrefix("connect backlog drained after a 300 ms quiet gap: 2 frame(s)") })
        XCTAssertTrue(c.summary().contains("frames: DETECT=1 backlog: DETECT=2 ROLE_REPORT=1"), c.summary())
    }

    func testBadCrcIsLoggedAndCountedNotDecoded() throws {
        let c = makeClient(.serial)
        try handshakeSerial(c)
        var bad = BtrxProtocolTests.example("HEALTH")
        bad[15] &+= 1
        c.onBytes(M.frame(packet(starboard, bad)))
        XCTAssertEqual(0, c.frameCount("HEALTH"))
        XCTAssertTrue(log.warns.contains { $0.contains("crc mismatch") })
        XCTAssertTrue(c.summary().contains("CRC_MISMATCH=1"))
    }

    func testVersionTwoIsRefusedAndThatBoardIsIgnored() throws {
        let c = makeClient(.serial)
        try handshakeSerial(c)
        var v2 = BtrxProtocolTests.example("HEALTH")
        v2[2] = 2
        let len = Int(v2[10])
        let crc = P.crc16(v2, from: 2, to: 11 + len)
        v2[11 + len] = UInt8(crc & 0xFF); v2[12 + len] = UInt8(crc >> 8)
        c.onBytes(M.frame(packet(starboard, v2)))
        c.onBytes(M.frame(packet(starboard, BtrxProtocolTests.example("DETECT"))))
        XCTAssertEqual(1, recorder.refused.count)
        XCTAssertEqual(starboard, recorder.refused.first?.0)
        XCTAssertEqual(0, c.frameCount("DETECT"))
        XCTAssertTrue(log.warns.contains { $0.contains("btrx version 2 refused") && $0.contains("newer than this app") })
    }

    func testNoFramesAreDecodedAfterClose() throws {
        let c = makeClient(.serial)
        try handshakeSerial(c)
        c.close()
        c.onBytes(M.frame(packet(starboard, BtrxProtocolTests.example("DETECT"))))
        XCTAssertEqual(0, c.frameCount("DETECT"))
    }

    func testParserSurvivesGarbageProtobuf() throws {
        let c = makeClient(.serial)
        try handshakeSerial(c)
        c.onBytes(M.frame([0x12, 0x7F, 0x01]))
        XCTAssertTrue(log.warns.contains { $0.hasPrefix("serial: undecodable FromRadio") })
    }

    // MARK: Bluetooth framing and the connect backlog

    func packetHandshake(_ c: BtrxClient) throws {
        c.start()
        let id = try configId(sent[0])
        c.onFromRadio(M.ProtoWriter().bytesField(3, M.ProtoWriter().varint(1, UInt64(deskNode)).bytes()).bytes())
        c.onFromRadio(M.ProtoWriter().varint(7, UInt64(id)).bytes())
    }

    func isRoleGet(_ toRadio: [UInt8]) -> Bool {
        toRadio.first == 0x0A && P.hex(toRadio).contains(String(P.hex(P.roleGetFrame(seq: 1)).prefix(8)))
    }

    func testBluetoothFramingSendsBareProtobufsWithoutWakeBytes() throws {
        let c = makeClient(.packet)
        try packetHandshake(c)
        XCTAssertEqual(0x18, sent[0][0])
        XCTAssertEqual(.ready, c.state)
        XCTAssertEqual(1, sent.count) // ROLE_GET waits for the end of the connect backlog
        c.onFromRadio([])
        XCTAssertTrue(isRoleGet(sent.last!))
    }

    func testHeartbeatsAreDistinct() throws {
        let c = makeClient(.packet)
        try packetHandshake(c)
        c.sendHeartbeat(); c.sendHeartbeat()
        // Never nonce 1: Meshtastic answers it by broadcasting node info over LoRa.
        XCTAssertEqual("3a020802", P.hex(sent[sent.count - 2]))
        XCTAssertEqual("3a020803", P.hex(sent.last!))
    }

    func testHeartbeatNonceSkipsZeroAndOneWhenItWraps() {
        XCTAssertEqual(2, BtrxClient.nextHeartbeatNonce(after: 1))
        XCTAssertEqual(0xFFFF_FFFF, BtrxClient.nextHeartbeatNonce(after: 0xFFFF_FFFE))
        XCTAssertEqual(2, BtrxClient.nextHeartbeatNonce(after: 0xFFFF_FFFF))
        var n: UInt32 = 1
        for _ in 0..<1000 { n = BtrxClient.nextHeartbeatNonce(after: n); XCTAssertGreaterThanOrEqual(n, 2) }
    }

    func testEmptyFromRadioReadIsIgnored() {
        let c = makeClient(.packet)
        c.start()
        c.onFromRadio([])
        XCTAssertEqual(.configuring, c.state)
        XCTAssertTrue(log.warns.isEmpty)
    }

    func testFirstDrainAfterConfigCompleteIsBacklogUntilTheFirstEmptyRead() throws {
        let c = makeClient(.packet)
        try packetHandshake(c)
        let detect = BtrxProtocolTests.example("DETECT")
        now += 500; c.onFromRadio(packet(starboard, detect))
        now += 2_500; c.onFromRadio(packet(starboard, detect))
        c.onFromRadio([])
        now += 100; c.onFromRadio(packet(starboard, detect))
        c.onFromRadio([])
        now += 3_000; c.onFromRadio(packet(starboard, detect))
        XCTAssertEqual([true, true, false, false], recorder.metas.map(\.backlog))
        XCTAssertEqual(2, c.backlogCount("DETECT"))
        XCTAssertEqual(2, c.frameCount("DETECT"))
        XCTAssertTrue(log.infos.contains("connect backlog drained at the first empty read: 2 frame(s) queued while R2C was away"))
        XCTAssertEqual(1, sent.filter(isRoleGet).count)
    }

    func testStaleRoleReportInTheBacklogIsNotTaken() throws {
        final class Roles: BtrxClientListener { var got: [BtrxProtocol.RoleReport] = []; func onLocalRoleReport(_ r: BtrxProtocol.RoleReport) { got.append(r) } }
        let roles = Roles()
        let c = BtrxClient(send: { [unowned self] in self.sent.append($0) }, log: log, listener: roles, rng: BtrxTestRandom(state: 4), framing: .packet)
        try packetHandshake(c)
        let report = P.encodeFrame(P.ROLE_REPORT, seq: 4, payload: P.fromHex("030500000000015b128c4c00000000000000000002"))
        let fromDesk = MeshtasticPhoneAPITests.fromRadioPacket(from: deskNode, to: deskNode, rssi: 0, snr: 0, port: 256, payload: report)
        c.onFromRadio(fromDesk)
        XCTAssertNil(c.localRole)
        XCTAssertTrue(roles.got.isEmpty)
        XCTAssertTrue(log.infos.contains { $0.hasPrefix("stale ROLE_REPORT in the connect backlog ignored") })
        c.onFromRadio([])
        c.onFromRadio(fromDesk)
        XCTAssertEqual(P.roleDeskPod, c.localRole?.role)
        XCTAssertEqual(1, roles.got.count)
    }

    func testToRadioMustFitOneBluetoothWrite() throws {
        let c = makeClient(.packet)
        c.setAttMtu(247)
        try packetHandshake(c)
        c.onFromRadio([])
        let before = sent.count
        c.sendFrame(to: starboard, frame: [UInt8](repeating: 0, count: BtrxClient.maxSendFrame + 1))
        XCTAssertEqual(before, sent.count)
        XCTAssertTrue(log.warns.contains { $0.hasPrefix("ERROR: btrx frame of 201 bytes not sent") })
        c.sendFrame(to: starboard, frame: [UInt8](repeating: 0, count: BtrxClient.maxSendFrame))
        XCTAssertEqual(before + 1, sent.count)
        XCTAssertLessThanOrEqual(sent.last!.count, BtrxClient.maxToRadioBLE)
        c.setAttMtu(100)
        c.sendFrame(to: starboard, frame: [UInt8](repeating: 0, count: 150))
        XCTAssertEqual(before + 1, sent.count)
        XCTAssertTrue(log.warns.contains { $0.contains("at most 97 per Bluetooth write") })
        c.setAttMtu(64)
        XCTAssertTrue(log.warns.contains { $0.hasPrefix("ERROR: Bluetooth MTU 64 is too small") })
    }
}
