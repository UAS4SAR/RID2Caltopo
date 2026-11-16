import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxMapStatusTest.kt.
final class BtrxMapStatusTests: XCTestCase {
    typealias Model = BtrxDeskPodModel

    private func ble(_ phase: Model.LinkPhase, _ failed: Int = 0) -> Model.LinkStatus {
        Model.LinkStatus(kind: .bluetooth, phase: phase, failedAttempts: failed)
    }

    private func text(_ link: Model.LinkStatus, talking: Bool = false, bluetoothOff: Bool = false, pausedForUSB: Bool = false) -> String {
        BtrxMapStatus.status(setUp: true, link: link, bluetoothOff: bluetoothOff, pausedForUSB: pausedForUSB, talking: talking)!.text
    }

    func testHiddenUntilBtrxIsSetUp() {
        XCTAssertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled: false, deskPodRemembered: false))
        XCTAssertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled: true, deskPodRemembered: false))
        XCTAssertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled: false, deskPodRemembered: true))
        XCTAssertNil(BtrxMapStatus.status(setUp: false, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, talking: true))
    }

    func testActiveOnlyWhenReadyAndTalking() {
        let s = BtrxMapStatus.status(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, talking: true)!
        XCTAssertEqual(s.text, "BTRX: Active")
        XCTAssertTrue(s.active)
        let quiet = BtrxMapStatus.status(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, talking: false)!
        XCTAssertEqual(quiet.text, "BTRX: Inactive - No Drone Pod reports")
        XCTAssertFalse(quiet.active)
        XCTAssertEqual(text(ble(.retryWait, 1), talking: true), "BTRX: Inactive - BT Down")
    }

    func testReasons() {
        XCTAssertEqual(text(ble(.scanning)), "BTRX: Inactive - Connecting")
        XCTAssertEqual(text(ble(.connecting)), "BTRX: Inactive - Connecting")
        XCTAssertEqual(text(ble(.configuring, 3)), "BTRX: Inactive - Connecting")
        XCTAssertEqual(text(ble(.retryWait, 1)), "BTRX: Inactive - BT Down")
        XCTAssertEqual(text(ble(.connecting, 2)), "BTRX: Inactive - BT Down")
        XCTAssertEqual(text(ble(.scanning, 2)), "BTRX: Inactive - BT Down")
        XCTAssertEqual(text(ble(.unavailable, 1), bluetoothOff: true), "BTRX: Inactive - Bluetooth off")
        XCTAssertEqual(text(ble(.retryWait, 1), bluetoothOff: true), "BTRX: Inactive - Bluetooth off")
        XCTAssertEqual(text(ble(.unavailable)), "BTRX: Inactive - Bluetooth unavailable")
        XCTAssertEqual(text(ble(.noPermission, 1)), "BTRX: Inactive - No permission")
        XCTAssertEqual(text(ble(.pausedForUSB), pausedForUSB: true), "BTRX: Inactive - Paused (USB)")
        XCTAssertEqual(text(Model.LinkStatus(kind: .none, phase: .off), bluetoothOff: true), "BTRX: Inactive - Link off")
    }

    func testUSBLink() {
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .ready), talking: true), "BTRX: Active")
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .ready)), "BTRX: Inactive - No Drone Pod reports")
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .configuring)), "BTRX: Inactive - Connecting")
    }
}
