import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxMapStatusTest.kt.
final class BtrxMapStatusTests: XCTestCase {
    typealias Model = BtrxDeskPodModel
    /// Earlier tests are about reasons after the link was READY this session.
    private func seen(setUp: Bool, link: Model.LinkStatus, bluetoothOff: Bool, pausedForUSB: Bool, dronePodReporting: Bool,
                      deskPodSilent: Bool = false, deskPodNotice: String? = nil) -> BtrxMapStatus.Status? {
        BtrxMapStatus.status(setUp: setUp, link: link, bluetoothOff: bluetoothOff, pausedForUSB: pausedForUSB, dronePodReporting: dronePodReporting,
                             deskPodSilent: deskPodSilent, deskPodNotice: deskPodNotice, readyEarlier: true)
    }

    private func ble(_ phase: Model.LinkPhase, _ failed: Int = 0) -> Model.LinkStatus {
        Model.LinkStatus(kind: .bluetooth, phase: phase, failedAttempts: failed)
    }

    private func text(_ link: Model.LinkStatus, reporting: Bool = false, bluetoothOff: Bool = false, pausedForUSB: Bool = false) -> String {
        seen(setUp: true, link: link, bluetoothOff: bluetoothOff, pausedForUSB: pausedForUSB, dronePodReporting: reporting)!.text
    }

    func testHiddenUntilBtrxIsSetUp() {
        XCTAssertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled: false, deskPodRemembered: false))
        XCTAssertFalse(BtrxMapStatus.setUp(bluetoothLinkEnabled: true, deskPodRemembered: false))
        XCTAssertTrue(BtrxMapStatus.setUp(bluetoothLinkEnabled: false, deskPodRemembered: true))
        XCTAssertNil(seen(setUp: false, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: true))
        XCTAssertNil(seen(setUp: false, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: false))
    }

    func testActiveFollowsTheLinkAndWarnsWhenTheDronePodIsQuiet() {
        let s = seen(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: true)!
        XCTAssertEqual(s.text, "BTRX: Active")
        XCTAssertTrue(s.active)
        XCTAssertFalse(s.warning)
        // READY with no Drone Pod reports is still Active (the link is up), in orange.
        let quiet = seen(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false, dronePodReporting: false)!
        XCTAssertEqual(quiet.text, "BTRX: Active - No Drone Pod reports")
        XCTAssertTrue(quiet.active)
        XCTAssertTrue(quiet.warning)
        // Reports only matter while READY.
        XCTAssertEqual(text(ble(.retryWait, 1), reporting: true), "BTRX: Inactive - BT Down")
        XCTAssertFalse(seen(setUp: true, link: ble(.retryWait, 1), bluetoothOff: false, pausedForUSB: false, dronePodReporting: true)!.warning)
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
            seen(setUp: true, link: ble(.ready), bluetoothOff: false, pausedForUSB: false,
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
        XCTAssertNil(seen(setUp: true, link: Model.LinkStatus(kind: .none, phase: .off), bluetoothOff: true, pausedForUSB: false, dronePodReporting: false)) // link off: no chip (Nov 18)
    }

    func testUSBLink() {
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .ready), reporting: true), "BTRX: Active")
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .ready)), "BTRX: Active - No Drone Pod reports")
        XCTAssertEqual(text(Model.LinkStatus(kind: .usb, phase: .configuring)), "BTRX: Inactive - Connecting")
    }
}
