import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxMapStatusTest.kt.
final class BtrxMapStatusTests: XCTestCase {
    typealias Model = BtrxDeskPodModel

    private func ble(_ phase: Model.LinkPhase, _ failed: Int = 0) -> Model.LinkStatus {
        Model.LinkStatus(kind: .bluetooth, phase: phase, failedAttempts: failed)
    }

    private func text(_ link: Model.LinkStatus, reporting: Bool = false, bluetoothOff: Bool = false, pausedForUSB: Bool = false) -> String {
        BtrxMapStatus.status(setUp: true, link: link, bluetoothOff: bluetoothOff, pausedForUSB: pausedForUSB, dronePodReporting: reporting)!.text
    }

    func testHiddenUntilBtrxIsSetUp() {
        XCTAssertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled: false, deskPodRemembered: false))
        XCTAssertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled: true, deskPodRemembered: false))
        XCTAssertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled: false, deskPodRemembered: true))
        XCTAssertNil(BtrxMapStatus.status(setUp: false, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: true))
        XCTAssertNil(BtrxMapStatus.status(setUp: false, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: false))
    }

    func testActiveFollowsTheLinkAndWarnsWhenTheDronePodIsQuiet() {
        let s = BtrxMapStatus.status(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: true)!
        XCTAssertEqual(s.text, "BTRX: Active")
        XCTAssertTrue(s.active)
        XCTAssertFalse(s.warning)
        // READY with no Drone Pod reports is still Active (the link is up), in orange.
        let quiet = BtrxMapStatus.status(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: false)!
        XCTAssertEqual(quiet.text, "BTRX: Active - No Drone Pod reports")
        XCTAssertTrue(quiet.active)
        XCTAssertTrue(quiet.warning)
        // Reports only matter while READY.
        XCTAssertEqual(text(ble(.retryWait, 1), reporting: true), "BTRX: Inactive - BT Down")
        XCTAssertFalse(BtrxMapStatus.status(setUp: true, link: ble(.retryWait, 1), bluetoothOff: false, pausedForUSB: false, dronePodReporting: true)!.warning)
    }

    func testDronePodReportWindowIs15Seconds() {
        XCTAssertEqual(BtrxMapStatus.dronePodReportSeconds, 15)
        XCTAssertFalse(BtrxMapStatus.dronePodReporting(secondsSinceReport: nil)) // none yet
        XCTAssertTrue(BtrxMapStatus.dronePodReporting(secondsSinceReport: 0))
        XCTAssertTrue(BtrxMapStatus.dronePodReporting(secondsSinceReport: 15))
        XCTAssertFalse(BtrxMapStatus.dronePodReporting(secondsSinceReport: 15.001))
        XCTAssertFalse(BtrxMapStatus.dronePodReporting(secondsSinceReport: -1)) // clock went back: not trusted
    }

    func testOnlyLivePairedPodFramesOtherThanPairReqCount() {
        XCTAssertTrue(BtrxMapStatus.isDronePodReport(frameType: BtrxProtocol.STEER, backlog: false, fromPairedPod: true))
        XCTAssertTrue(BtrxMapStatus.isDronePodReport(frameType: BtrxProtocol.HEALTH, backlog: false, fromPairedPod: true))
        XCTAssertTrue(BtrxMapStatus.isDronePodReport(frameType: BtrxProtocol.DETECT, backlog: false, fromPairedPod: true))
        XCTAssertFalse(BtrxMapStatus.isDronePodReport(frameType: BtrxProtocol.STEER, backlog: true, fromPairedPod: true))
        XCTAssertFalse(BtrxMapStatus.isDronePodReport(frameType: BtrxProtocol.HEALTH, backlog: false, fromPairedPod: false)) // Desk Pod's own HEALTH
        XCTAssertFalse(BtrxMapStatus.isDronePodReport(frameType: BtrxProtocol.PAIR_REQ, backlog: false, fromPairedPod: true))
    }

    func testDeskPodHealthEvery30sNoLongerFlapsTheChip() {
        // Nov 16: Desk Pod HEALTH every 30 s, no Drone Pod: the chip stays one state.
        let texts = Set((0...60).map { _ in
            BtrxMapStatus.status(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false,
                                 dronePodReporting: BtrxMapStatus.dronePodReporting(secondsSinceReport: nil))!.text
        })
        XCTAssertEqual(texts, ["BTRX: Active - No Drone Pod reports"])
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
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .ready), reporting: true), "BTRX: Active")
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .ready)), "BTRX: Active - No Drone Pod reports")
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .configuring)), "BTRX: Inactive - Connecting")
    }
}
