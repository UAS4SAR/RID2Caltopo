import XCTest
@testable import R2CCore

/// Shared STEER format-2 vectors (`test-fixtures/btrx/steer-format2.tsv`) and log-line text.
/// Same cases as Android `BtrxProtocolTest.steerFormat2SharedVectors`.
final class BtrxSteerGyroYawTests: XCTestCase {
    private func fixtureURL() -> URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("test-fixtures/btrx/steer-format2.tsv")
    }

    private func rows() throws -> [[String]] {
        try String(contentsOf: fixtureURL(), encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
            .map { $0.components(separatedBy: "\t") }
    }

    private func yflags(_ s: String) -> Int {
        if s.hasPrefix("0x") || s.hasPrefix("0X") { return Int(s.dropFirst(2), radix: 16)! }
        return Int(s)!
    }

    func testSharedFormat2Vectors() throws {
        let rows = try rows()
        XCTAssertEqual(rows.count, 16)
        for c in rows {
            let name = c[0]
            let s = try BtrxProtocol.parseSteer(BtrxProtocol.fromHex(c[1]))
            XCTAssertEqual(s.extras.count, Int(c[2]), "\(name) extras")
            XCTAssertEqual(s.extFormat, Int(c[3]), "\(name) ext_format")
            XCTAssertEqual(s.extTruncated, c[4] == "1", "\(name) truncated")
            if c[5] == "1" {
                let y = s.yaw
                XCTAssertNotNil(y, "\(name) has_yaw")
                guard let y else { continue }
                XCTAssertEqual(y.yawX10, Int(c[6]), "\(name) yaw_x10")
                XCTAssertEqual(y.yawFixedX10, Int(c[7]), "\(name) yaw_fixed")
                XCTAssertEqual(y.biasZMdps, Int(c[8]), "\(name) bias")
                XCTAssertEqual(y.tOffMs, Int(c[9]), "\(name) t_off")
                XCTAssertEqual(y.turns, Int(c[10]), "\(name) turns")
                XCTAssertEqual(y.yflags, yflags(c[11]), "\(name) yflags")
                if y.tOffMs == BtrxProtocol.yawTOffUnknown {
                    XCTAssertNil(y.sampleMs(s.windowEndMs), "\(name) sample not ready")
                } else {
                    XCTAssertEqual(y.sampleMs(s.windowEndMs), Int64(s.windowEndMs) + Int64(y.tOffMs), "\(name) sample_ms")
                }
            } else {
                XCTAssertNil(s.yaw, "\(name) no yaw")
            }
        }
    }

    func testSteerYawLogLineIsMachineReadable() throws {
        let rows = try rows()
        let hex = rows.first { $0[0] == "fmt2_0extra" }![1]
        let s = try BtrxProtocol.parseSteer(BtrxProtocol.fromHex(hex))
        let fields = BtrxYawLog.steerFields(s)
        XCTAssertTrue(fields.contains("yaw_x10=123") && fields.contains("yaw_fixed_x10=124")
                      && fields.contains("turns=1") && fields.contains("bias_z_mdps=-345")
                      && fields.contains("t_off_ms=-20") && fields.contains("sample_ms=123436")
                      && fields.contains("yflags=0x03"), fields)
        let line = try BtrxFrameLog.describe(BtrxProtocol.STEER, BtrxProtocol.fromHex(hex))
        XCTAssertTrue(line.contains("ext_format=2") && line.contains("yaw_x10=123") && line.contains("sample_ms=123436"), line)
        let gyro = BtrxYawLog.steerLine(arrivalMs: 1_700_000_000_000, s, backlog: false)!
        XCTAssertTrue(gyro.hasPrefix("Btrx gyro_yaw arrival_ms=1700000000000") && gyro.contains("window_end_ms=123456"), gyro)
        let unknownHex = rows.first { $0[0] == "fmt2_0extra_toff_unknown" }![1]
        let unknown = try BtrxProtocol.parseSteer(BtrxProtocol.fromHex(unknownHex))
        XCTAssertTrue(BtrxYawLog.steerFields(unknown).contains("sample_ms=-"))
        let f1 = try BtrxFrameLog.describe(BtrxProtocol.STEER, BtrxProtocol.fromHex(rows.first { $0[0] == "fmt1_2extra" }![1]))
        XCTAssertTrue(f1.contains("ext_format=1") && !f1.contains("yaw_x10="), f1)
    }
}
