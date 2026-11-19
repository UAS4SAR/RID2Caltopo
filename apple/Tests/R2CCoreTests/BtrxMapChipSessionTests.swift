import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxMapChipSessionTest.kt; same shared fixture.
final class BtrxMapChipSessionTests: XCTestCase {
    typealias Model = BtrxDeskPodModel

    private func ble(_ phase: Model.LinkPhase, _ failed: Int = 0) -> Model.LinkStatus {
        Model.LinkStatus(kind: .bluetooth, phase: phase, failedAttempts: failed)
    }

    private func chip(_ link: Model.LinkStatus, readyEarlier: Bool = false, requestedAt: Double? = nil, now: Double = 0,
                      bluetoothOff: Bool = false, enabled: Bool = true, paired: Bool = true) -> String? {
        BtrxMapStatus.status(setUp: paired, link: link, bluetoothOff: bluetoothOff, pausedForUSB: false, dronePodReporting: false,
                             linkEnabled: enabled, readyEarlier: readyEarlier, requestedAt: requestedAt, now: now)?.text
    }

    func testSharedFixtureMatchesAndroid() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("test-fixtures/btrx/map-chip-visibility.tsv")
        let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }.map { $0.components(separatedBy: "\t") }
        XCTAssertEqual(rows.count, 12)
        for r in rows {
            let got = BtrxMapStatus.visibility(paired: r[0] == "true", enabled: r[1] == "true", ready: r[2] == "true", readyEarlier: r[3] == "true",
                                               requestedAt: r[4] == "-" ? nil : Double(r[4])! / 1000, now: Double(r[5])! / 1000)
            XCTAssertEqual(got, r[6], r[7])
        }
        XCTAssertEqual(BtrxMapStatus.connectingWindow, 120)
    }

    func testNoBtDownBeforeReadyThisSession() {
        XCTAssertNil(chip(ble(.retryWait, 1), now: 600))
        XCTAssertNil(chip(ble(.connecting, 4), now: 600))
        XCTAssertNil(chip(ble(.scanning, 2), now: 600))
        XCTAssertEqual(chip(ble(.retryWait, 1), readyEarlier: true), "BTRX: Inactive - BT Down")
    }

    func testUserActionShowsConnectingForTwoMinutesThenHides() {
        XCTAssertEqual(chip(ble(.retryWait, 1), requestedAt: 10, now: 20), "BTRX: Inactive - Connecting")
        XCTAssertEqual(chip(ble(.scanning), requestedAt: 10, now: 129.999), "BTRX: Inactive - Connecting")
        XCTAssertNil(chip(ble(.scanning), requestedAt: 10, now: 130))
        XCTAssertEqual(chip(ble(.unavailable), requestedAt: 0, now: 0.001, bluetoothOff: true), "BTRX: Inactive - Bluetooth off")
        XCTAssertEqual(chip(ble(.noPermission), requestedAt: 0, now: 0.001), "BTRX: Inactive - No permission")
        XCTAssertEqual(chip(ble(.unavailable), requestedAt: 0, now: 0.001), "BTRX: Inactive - Bluetooth unavailable")
    }

    func testLinkOffAndNeverPairedNeverShow() {
        XCTAssertNil(chip(Model.LinkStatus(kind: .none, phase: .off), readyEarlier: true, requestedAt: 0, now: 0.001))
        XCTAssertNil(chip(ble(.retryWait, 1), readyEarlier: true, enabled: false))
        XCTAssertNil(chip(ble(.scanning), requestedAt: 0, now: 0.001, paired: false))
        XCTAssertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled: true, deskPodRemembered: false))
        XCTAssertEqual(chip(ble(.ready)), "BTRX: Active - No Drone Pod reports")
    }

    func testSessionRemembersReadyUntilForgotten() {
        var s = BtrxMapStatus.Session()
        XCTAssertFalse(s.readyEarlier)
        s.observe(ready: false); XCTAssertFalse(s.readyEarlier)
        s.observe(ready: true); s.observe(ready: false); XCTAssertTrue(s.readyEarlier)
        s.request(now: 5); XCTAssertEqual(s.requestedAt, 5)
        s.forget(); XCTAssertFalse(s.readyEarlier); XCTAssertNil(s.requestedAt)
    }
}
