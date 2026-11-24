import XCTest
import CBtrxBearing
@testable import R2CCore

/// Swift copy of Android's BtrxLaunchZoneTest.kt (Ken, Nov 23): strong sources near the takeoff
/// point are muted on the ground and through the initial climb; the app states the rule with the
/// engine's numbers (btrx_bearing.h, test-fixtures/btrx/launch-zone.tsv).
final class BtrxLaunchZoneTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()

    func testConstantsMatchEngineDefaultsAndFixture() throws {
        var c = btrx_bearing_config_t()
        btrx_bearing_config_default(&c)
        XCTAssertEqual(c.launch_rssi_dbm, Float(BTRX_BEARING_LAUNCH_RSSI_DBM))
        XCTAssertEqual(c.launch_radius_m, Float(BTRX_BEARING_LAUNCH_RADIUS_M))
        XCTAssertEqual(c.launch_zone_max_agl_m, Float(BTRX_BEARING_LAUNCH_ZONE_MAX_AGL_M))
        XCTAssertEqual(c.launch_window_s, Float(BTRX_BEARING_LAUNCH_WINDOW_S))
        XCTAssertEqual(c.launch_max_agl_m, 5)
        XCTAssertTrue(c.launch_auto_mute)
        let rows = try String(contentsOf: Self.root.appendingPathComponent("test-fixtures/btrx/launch-zone.tsv"), encoding: .utf8)
            .split(separator: "\n").map { $0.components(separatedBy: "\t") }.filter { $0[0] == "const" }
        var k: [String: Int] = [:]
        for r in rows { k[r[1]] = Int(r[2]) }
        XCTAssertEqual(k["rssi_dbm"], Int(BTRX_BEARING_LAUNCH_RSSI_DBM))
        XCTAssertEqual(k["radius_m"], Int(BTRX_BEARING_LAUNCH_RADIUS_M))
        XCTAssertEqual(k["zone_max_agl_m"], Int(BTRX_BEARING_LAUNCH_ZONE_MAX_AGL_M))
        XCTAssertEqual(k["window_s"], Int(BTRX_BEARING_LAUNCH_WINDOW_S))
    }

    func testSettingTextStatesTheZone() {
        XCTAssertEqual("Mute strong sources near the takeoff spot", BtrxBearingView.settingLaunchMute)
        XCTAssertEqual("Devices heard -70 dBm or stronger within 30 m of the takeoff spot in the first 5 min, on the ground and through the "
                       + "initial climb (up to 40 m AGL, until the drone first leaves that area), are muted: usually the team at the launch "
                       + "site. Show them from the map chip.", BtrxBearingView.settingLaunchMuteHint)
    }
}
