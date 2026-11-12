import XCTest
@testable import R2CCore

/// Swift copy of Android's MeshtasticSerialTest.kt.
final class MeshtasticPhoneAPITests: XCTestCase {
    typealias M = MeshtasticPhoneAPI

    static func fromRadioPacket(from: UInt32, to: UInt32, rssi: Int32, snr: Float, port: Int, payload: [UInt8]) -> [UInt8] {
        let data = M.ProtoWriter().varint(1, UInt64(port)).bytesField(2, payload).bytes()
        let packet = M.ProtoWriter().fixed32(1, from).fixed32(2, to).bytesField(4, data).fixed32(6, 99)
            .fixed32(7, 0).fixed32(8, snr.bitPattern).int32(12, rssi).bytes()
        return M.ProtoWriter().varint(1, 5).bytesField(2, packet).bytes()
    }

    func testStreamFrameIsMagicPlusBigEndianLength() {
        let framed = M.frame([UInt8](repeating: 7, count: 300))
        XCTAssertEqual("94c3012c", BtrxProtocol.hex(Array(framed.prefix(4))))
        XCTAssertEqual(304, framed.count)
    }

    func testWantConfigAndHeartbeatEncodings() {
        XCTAssertEqual("18f8acd19101", BtrxProtocol.hex(M.toRadioWantConfig(0x1234_5678)))
        XCTAssertEqual("3a00", BtrxProtocol.hex(M.toRadioHeartbeat()))
        XCTAssertEqual("3a020801", BtrxProtocol.hex(M.toRadioHeartbeat(nonce: 1)))
    }

    func testDisconnectEncodingAndSerialFrame() {
        XCTAssertEqual("2001", BtrxProtocol.hex(M.toRadioDisconnect()))
        XCTAssertEqual("94c300022001", BtrxProtocol.hex(M.frame(M.toRadioDisconnect())))
    }

    func testToRadioPacketEncodesPrivateAppDataWithHopLimitZero() {
        let frame = BtrxProtocol.roleGetFrame(seq: 1)
        let out = M.toRadioPacket(to: 0xA1B2_C3D4, packetId: 0x0102_0304, portnum: 256, payload: frame)
        let data = "08800212" + "0d" + BtrxProtocol.hex(frame)
        let packet = "15d4c3b2a1" + "22" + String(format: "%02x", data.count / 2) + data + "3504030201"
        XCTAssertEqual("0a" + String(format: "%02x", packet.count / 2) + packet, BtrxProtocol.hex(out))
        let three = M.toRadioPacket(to: 1, packetId: 2, portnum: 256, payload: frame, hopLimit: 3)
        XCTAssertTrue(BtrxProtocol.hex(three).hasSuffix("4803"))
    }

    func testParsesMyInfoConfigCompleteAndOtherRecords() throws {
        let myInfo = M.ProtoWriter().varint(1, 7).bytesField(3, M.ProtoWriter().varint(1, 0xDEAD_BEEF).varint(8, 3).bytes()).bytes()
        XCTAssertEqual(.myInfo(nodeNum: 0xDEAD_BEEF), try M.parseFromRadio(myInfo))
        XCTAssertEqual(.configComplete(id: 42), try M.parseFromRadio(M.ProtoWriter().varint(7, 42).bytes()))
        XCTAssertEqual(.other(field: 10), try M.parseFromRadio(M.ProtoWriter().bytesField(10, [8, 0]).bytes()))
        XCTAssertEqual(.other(field: 0), try M.parseFromRadio([]))
    }

    func testParsesMeshPacketWithNegativeRssiAndFloatSnr() throws {
        let payload = BtrxProtocolTests.example("HEALTH")
        let proto = Self.fromRadioPacket(from: 0x4C8C_125B, to: 0xFFFF_FFFF, rssi: -87, snr: 6.25, port: 256, payload: payload)
        guard case .packet(let p) = try M.parseFromRadio(proto) else { return XCTFail("not a packet") }
        XCTAssertEqual(0x4C8C_125B, p.from)
        XCTAssertEqual(-87, p.rxRssi)
        XCTAssertEqual(6.25, p.rxSnr)
        XCTAssertEqual(256, p.portnum)
        XCTAssertEqual(payload, p.payload)
    }

    func testStreamDecoderSkipsTextAndHandlesSplitReads() {
        var got: [String] = []
        var noise = 0
        let d = M.StreamDecoder(onMessage: { got.append(BtrxProtocol.hex($0)) }, onNoise: { noise += $0 })
        let stream = Array("INFO boot\r\n".utf8) + M.frame([0x38, 0x01]) + [0x94, 0x41] + M.frame([0x3A, 0x00])
        stream.forEach { d.feed([$0]) }
        XCTAssertEqual(["3801", "3a00"], got)
        XCTAssertGreaterThanOrEqual(noise, 11)
    }

    func testMeshtasticUuidsAreTheStandardOnes() {
        XCTAssertEqual("6ba1b218-15a8-461f-9fa8-5dcae273eafd", M.serviceUUID)
        XCTAssertEqual("f75c76d2-129e-4dad-a1dd-7866124401e7", M.toRadioUUID)
        XCTAssertEqual("2c55e69e-4993-11ed-b878-0242ac120002", M.fromRadioUUID)
        XCTAssertEqual("ed9da18c-a800-4f66-a670-aa7547e34453", M.fromNumUUID)
    }
}
