import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxProtocolTest.kt: vectors from the btrx brief (section 5.3,
/// checked with protocol.py) and firmware/tools/r2c_sim/test_protocol.py.
final class BtrxProtocolTests: XCTestCase {
    typealias P = BtrxProtocol

    static let examples: [(String, String)] = [
        ("ROLE_GET", "b7520120010000000000005830"),
        ("DESK_PAIR", "b75201152500000000000401013c0090ff"),
        ("PAIR_REQ", "b7520110ee00000000001b000000005b128c4c71291c3500010001010201570b73e9475dfe3fbd48"),
        ("PAIR_CONFIRM", "b75201110600000000003d00000000570b73e9475dfe3f02010100000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000005f98"),
        ("PAIR_ACK", "b7520112f900000000020401010000e857"),
        ("TIME_PING", "b75201300200000000001001007856341200000000000000000000fa86"),
        ("START", "b75201400300000000000907000000000000000076b1"),
        ("DETECT", "b7520170c9000000000028aa70050004fa010002000152030c66554433221185b9c0094c005a0300044c00100500000000000090c5"),
        ("HEALTH", "b7520180af00000000001e4c010000000102020140000000011805000600e20c000000000000020001d190"),
        ("HELLO", "b752010101d4c3b2a1000c425452580102000144332211ccbb"),
    ]
    static func example(_ name: String) -> [UInt8] { P.fromHex(examples.first { $0.0 == name }!.1) }
    static let pairConfirmFirmware = "b75201110309000000003d" + "0900000001020304050607080201010110" +
        "abababababababababababababababab" + "00000000000000000000000000000000" + "627472780000000000000000" + "74fe"
    static let pairConfirmPodId = "b752011107f05ad345003df05ad345570b73e9475dfe3f0201010000000000000000000000000000" +
        "00000000000000000000000000000000000000000000000000000000000000009498"

    private func rejection(_ buf: [UInt8], file: StaticString = #filePath, line: UInt = #line) -> P.Rejection? {
        do { _ = try P.decodeFrame(buf); XCTFail("frame was accepted: \(P.hex(buf))", file: file, line: line); return nil } catch let e as P.FrameError { return e.reason } catch { return nil }
    }

    func testCrc16CcittFalseCheckValue() {
        XCTAssertEqual(0x29B1, P.crc16(Array("123456789".utf8)))
    }

    func testAllBriefExamplesDecodeAndReEncode() throws {
        for (name, hex) in Self.examples {
            let f = try P.decodeFrame(P.fromHex(hex))
            XCTAssertEqual(1, f.version, name)
            XCTAssertEqual(name, f.typeName)
            if name != "HELLO" { XCTAssertEqual(0, f.podId, name) }
            XCTAssertEqual(hex, P.hex(P.encodeFrame(f.type, seq: f.seq, podId: f.podId, flags: f.flags, payload: f.payload)), name)
        }
    }

    func testRoleGetEncodesToTheBriefExample() {
        XCTAssertEqual(P.hex(Self.example("ROLE_GET")), P.hex(P.roleGetFrame(seq: 1)))
    }

    func testAnyOneByteChangeIsRejected() {
        for (name, hex) in Self.examples {
            let good = P.fromHex(hex)
            for i in good.indices {
                var bad = good
                bad[i] ^= 0x01
                guard let reason = rejection(bad) else { continue }
                let allowed: Set<P.Rejection>
                switch i {
                case 0, 1: allowed = [.BAD_PREAMBLE]
                case 2: allowed = [.UNSUPPORTED_VERSION]
                case 10: allowed = [.CRC_MISMATCH, .TRUNCATED]
                default: allowed = [.CRC_MISMATCH]
                }
                XCTAssertTrue(allowed.contains(reason), "\(name) byte \(i) -> \(reason)")
            }
        }
    }

    func testVersionTwoIsRefusedEvenWithAValidCrc() {
        var frame = Self.example("DETECT")
        frame[2] = 2
        let len = Int(frame[10])
        let crc = P.crc16(frame, from: 2, to: 11 + len)
        frame[11 + len] = UInt8(crc & 0xFF); frame[12 + len] = UInt8(crc >> 8)
        do { _ = try P.decodeFrame(frame); XCTFail("version 2 accepted") } catch let e as P.FrameError {
            XCTAssertEqual(.UNSUPPORTED_VERSION, e.reason)
            XCTAssertEqual(2, e.version)
        } catch { XCTFail("\(error)") }
    }

    func testShortAndTruncatedFramesAreRejected() {
        XCTAssertEqual(.TOO_SHORT, rejection(P.fromHex("b75201200100000000")))
        let detect = Self.example("DETECT")
        XCTAssertEqual(.TRUNCATED, rejection(Array(detect.dropLast())))
        var tooLong = Self.example("ROLE_GET")
        tooLong[10] = 221
        XCTAssertEqual(.LENGTH_TOO_LARGE, rejection(tooLong))
    }

    func testPayloadExamples() throws {
        XCTAssertEqual(P.DeskPair(open: 1, source: 1, secondsLeft: 60), try P.parseDeskPair(try P.decodeFrame(Self.example("DESK_PAIR")).payload))
        let r = try P.parsePairReq(try P.decodeFrame(Self.example("PAIR_REQ")).payload)
        XCTAssertEqual(0x4C8C_125B, r.devA)
        XCTAssertEqual(0x351C_2971, r.devB)
        XCTAssertEqual(1, r.imuOk)
        XCTAssertEqual(P.roleStarboard, r.roleA)
        XCTAssertEqual(P.rolePort, r.roleB)
        XCTAssertEqual("570b73e9475dfe3f", P.hex(r.nonce))
        let ackFrame = try P.decodeFrame(Self.example("PAIR_ACK"))
        XCTAssertEqual(P.F_IS_ACK, ackFrame.flags)
        XCTAssertEqual(P.PairAck(aOk: 1, bOk: 1, flags: 0), try P.parsePairAck(ackFrame.payload))
        let t = try P.parseTime(try P.decodeFrame(Self.example("TIME_PING")).payload)
        XCTAssertEqual(0x1234_5678, t.t1)
        let hello = try P.decodeFrame(Self.example("HELLO"))
        XCTAssertEqual(0xA1B2_C3D4, hello.podId)
    }

    func testDetectExampleMatchesTheBrief() throws {
        let d = try P.parseDetect(try P.decodeFrame(Self.example("DETECT")).payload)
        XCTAssertEqual(356_522, d.windowEndMs)
        XCTAssertEqual(2.0, Double(d.rollX2) / 2)
        XCTAssertEqual(-3.0, Double(d.pitchX2) / 2)
        XCTAssertTrue(d.imuOk)
        XCTAssertFalse(d.tilted)
        let r = d.records[0]
        XCTAssertEqual("11:22:33:44:55:66", r.address)
        XCTAssertEqual("5566", r.addressTail)
        XCTAssertEqual(1, r.addressType)
        XCTAssertEqual(1, r.deviceClass)
        XCTAssertTrue(r.hasManufacturerData)
        XCTAssertEqual(-71, r.rssiPort)
        XCTAssertEqual(-64, r.rssiStbd)
        XCTAssertEqual(9, r.hits)
        XCTAssertEqual(850, r.ageMs)
        XCTAssertEqual(0x004C, r.companyId)
        // mfg raw, as on the wire (the brief's example is being regenerated in btrx PR #15).
        XCTAssertEqual(4, r.mfgLen)
        XCTAssertEqual("4c001005", P.hex(r.mfg))
        XCTAssertFalse(r.isRemoteID)
    }

    func testHealthExampleAndFirmwarePayloads() throws {
        let h = try P.parseHealth(try P.decodeFrame(Self.example("HEALTH")).payload)
        XCTAssertEqual(332, h.uptimeS)
        XCTAssertEqual(0x0100, h.buildHash)
        XCTAssertEqual(P.roleStarboard, h.role)
        XCTAssertEqual(1, h.confirmed)
        XCTAssertEqual(P.FLAG_SCANNING, h.flags)
        XCTAssertEqual(24, h.dieTempC)
        XCTAssertEqual(-30, h.loraRssi)
        XCTAssertEqual(12, h.loraSnr)
        XCTAssertEqual(1, h.scanning)
        let fw = try P.parseHealth(P.fromHex("0a00000000010300000000ffff00fc000000000000ecffffff0000000001"))
        XCTAssertEqual(P.roleDeskPod, fw.role)
        XCTAssertEqual(0xFFFF, fw.bLinkAgeS)
        XCTAssertEqual(-4, fw.dieTempC)
        XCTAssertEqual(-20, fw.clockOffsetMs)
        let role = try P.parseRoleReport(P.fromHex("020200080000015b128c4c71291c35000000000000"))
        XCTAssertEqual(P.roleStarboard, role.role)
        XCTAssertEqual(P.FLAG_TX_INHIBIT, role.flags)
        XCTAssertEqual(0x0100, role.buildHash)
        XCTAssertEqual(0x4C8C_125B, role.devId)
        let ack = try P.parseCtrlAck(P.fromHex("4001010300000004000000"))
        XCTAssertEqual(P.START, ack.ackedType)
        XCTAssertEqual(3, ack.sessionId)
        XCTAssertEqual(P.PairAck(aOk: 1, bOk: 0, flags: P.FLAG_PAIRING), try P.parsePairAck(P.fromHex("01001000")))
    }

    func testPayloadErrors() {
        XCTAssertThrowsError(try P.parseDeskPair([1, 1, 60]))
        var p = [UInt8](repeating: 0, count: 11)
        p[10] = 8
        XCTAssertThrowsError(try P.parseDetect(p))
    }

    func testFrameLogNeverPrintsTheFullAddress() throws {
        let line = BtrxFrameLog.format(try P.decodeFrame(Self.example("DETECT")))
        XCTAssertTrue(line.hasPrefix("DETECT seq=201 pod=00000000"), line)
        XCTAssertTrue(line.contains("..5566"), line)
        XCTAssertFalse(line.contains("11:22:33"))
        XCTAssertFalse(line.contains("112233"))
    }

    // MARK: PAIR_CONFIRM and pod_id

    func testPairConfirmEncodesToTheBriefExample() {
        XCTAssertEqual(P.hex(Self.example("PAIR_CONFIRM")), P.hex(P.pairConfirmFrame(seq: 6, podId: 0, nonce: P.fromHex("570b73e9475dfe3f"))))
    }

    func testPairConfirmWithAssignedPodIdMatchesProtocolPy() {
        XCTAssertEqual(Self.pairConfirmPodId, P.hex(P.pairConfirmFrame(seq: 7, podId: 0x45D3_5AF0, nonce: P.fromHex("570b73e9475dfe3f"))))
    }

    func testPairConfirmPayloadReproducesTheFirmwareVector() throws {
        let f = try P.decodeFrame(P.fromHex(Self.pairConfirmFirmware))
        XCTAssertEqual(3, f.seq)
        XCTAssertEqual(9, f.podId)
        let payload = P.pairConfirmPayload(podId: 9, nonce: P.fromHex("0102030405060708"), roleA: 2, roleB: 1, confirm: 1,
                                           channel: 1, psk: [UInt8](repeating: 0xAB, count: 16), name: "btrx")
        XCTAssertEqual(P.hex(f.payload), P.hex(payload))
    }

    func testPodIdIsFnv1aOfDevAAndNeverZero() {
        XCTAssertEqual(0x45D3_5AF0, P.podId(forDevA: 0x4C8C_125B))
        XCTAssertEqual(0xEBEE_7337, P.podId(forDevA: 2))
        XCTAssertEqual(0x4B95_F515, P.podId(forDevA: 0))
        for d: UInt32 in [0, 1, 0xFFFF_FFFF, 0x0189_68BE] { XCTAssertNotEqual(0, P.podId(forDevA: d)) }
    }
}
