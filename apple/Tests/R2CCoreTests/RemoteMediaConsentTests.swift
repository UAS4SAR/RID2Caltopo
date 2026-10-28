import Foundation
import XCTest
@testable import R2CCore

final class RemoteMediaConsentTests: XCTestCase {
    func testServerCannotAuthorizeWhileLocalControlIsOff() {
        let consent = RemoteMediaConsent()
        let video = consent.receive(key: "video|1", resource: "camera", serverRequiresConsent: false)
        let recording = consent.receive(key: "recording|1", resource: "recording|path", serverRequiresConsent: false)
        XCTAssertFalse(video.isAllowed)
        XCTAssertFalse(recording.isAllowed)
    }

    func testServerRequiredPromptIsNeverSkippedWhenControlIsOn() {
        let consent = RemoteMediaConsent()
        consent.update(enabled: true)
        XCTAssertFalse(consent.receive(key: "video|1", resource: "camera", serverRequiresConsent: true).isAllowed)
    }

    func testDelayedOfferAndReenableDoNotReviveAutoGrant() {
        let consent = RemoteMediaConsent()
        consent.update(enabled: true)
        let old = consent.receive(key: "video|1", resource: "camera", serverRequiresConsent: false)
        XCTAssertTrue(old.isAllowed)
        consent.update(enabled: false)
        XCTAssertFalse(old.isAllowed)
        consent.update(enabled: true)
        XCTAssertFalse(consent.find(key: "video|1", resource: "camera")!.isAllowed)
        XCTAssertFalse(consent.receive(key: "video|1", resource: "camera", serverRequiresConsent: false).isAllowed)
        XCTAssertTrue(consent.receive(key: "video|2", resource: "camera", serverRequiresConsent: false).isAllowed)
    }

    func testFreshPerRequestApprovalWorksWhileControlIsOff() {
        let consent = RemoteMediaConsent()
        let old = consent.receive(key: "video|1", resource: "camera", serverRequiresConsent: false)
        let explicit = consent.approve(key: "video|1")!
        XCTAssertFalse(old.isAllowed)
        consent.update(enabled: false)
        XCTAssertTrue(explicit.isAllowed)
        XCTAssertFalse(explicit.automatic)
        XCTAssertFalse(consent.receive(key: "video|2", resource: "camera", serverRequiresConsent: false).isAllowed)
    }

    func testVideoApprovalCannotAuthorizeDifferentMediaOrRecording() {
        let consent = RemoteMediaConsent()
        consent.receive(key: "video|1", resource: "camera-A", serverRequiresConsent: true)
        XCTAssertNotNil(consent.approve(key: "video|1"))
        XCTAssertNil(consent.find(key: "video|1", resource: "camera-B"))
        XCTAssertNil(consent.find(key: "recording|1", resource: "camera-A"))
        XCTAssertFalse(consent.receive(key: "video|1", resource: "camera-B", serverRequiresConsent: false).isAllowed)
    }

    func testWithdrawingAdvanceAuthorizationCancelsUploadAndStopsNextChunk() {
        let consent = RemoteMediaConsent()
        consent.update(enabled: true)
        let grant = consent.receive(key: "recording|1", resource: "file|path", serverRequiresConsent: false)
        let request = FakeTransfer()
        grant.onRevocation { request.cancel() }
        var chunksSent = 0
        for _ in 0..<3 {
            guard grant.isAllowed else { break }
            chunksSent += 1
            consent.update(enabled: false)
        }
        XCTAssertEqual(chunksSent, 1)
        XCTAssertEqual(request.cancelCount, 1)
        consent.update(enabled: false)
        XCTAssertEqual(request.cancelCount, 1)
    }

    func testQueuedRecordingStartRechecksTheGrant() async {
        let consent = RemoteMediaConsent()
        consent.update(enabled: true)
        let grant = consent.receive(key: "recording|1", resource: "file|path", serverRequiresConsent: false)
        consent.update(enabled: false)
        let sent = await Task.detached { grant.isAllowed ? 1 : 0 }.value
        XCTAssertEqual(sent, 0)
    }

    func testExplicitRecordingSurvivesSwitchWithdrawalButNotCancellation() {
        let consent = RemoteMediaConsent()
        consent.receive(key: "recording|1", resource: "file|path", serverRequiresConsent: true)
        let grant = consent.approve(key: "recording|1")!
        consent.update(enabled: true)
        consent.update(enabled: false)
        XCTAssertTrue(grant.isAllowed)
        consent.cancel(key: grant.key)
        XCTAssertFalse(grant.isAllowed)
    }

    func testReconnectResetAndFreshProcessRequireNewApproval() {
        let consent = RemoteMediaConsent()
        consent.update(enabled: true)
        consent.receive(key: "video|1", resource: "camera", serverRequiresConsent: false)
        consent.reset()
        XCTAssertFalse(consent.find(key: "video|1", resource: "camera")!.isAllowed)
        XCTAssertFalse(consent.receive(key: "video|1", resource: "camera", serverRequiresConsent: false).isAllowed)
        let restarted = RemoteMediaConsent()
        XCTAssertNil(restarted.find(key: "video|1", resource: "camera"))
        XCTAssertFalse(restarted.receive(key: "video|1", resource: "camera", serverRequiresConsent: false).isAllowed)
    }

    func testCompletedTransferDoesNotSendAWithdrawal() {
        let consent = RemoteMediaConsent()
        consent.update(enabled: true)
        let grant = consent.receive(key: "recording|1", resource: "file", serverRequiresConsent: false)
        let transfer = FakeTransfer()
        grant.onRevocation { transfer.cancel() }
        consent.finish(key: grant.key, grant: grant)
        consent.update(enabled: false)
        XCTAssertFalse(grant.isAllowed)
        XCTAssertEqual(transfer.cancelCount, 0)
    }

    func testLateCancellationRegistrationCancelsImmediately() {
        let consent = RemoteMediaConsent()
        consent.update(enabled: true)
        let grant = consent.receive(key: "recording|1", resource: "file", serverRequiresConsent: false)
        consent.update(enabled: false)
        let transfer = FakeTransfer()
        grant.onRevocation { transfer.cancel() }
        XCTAssertEqual(transfer.cancelCount, 1)
    }
}

private final class FakeTransfer: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var cancelCount: Int { lock.lock(); defer { lock.unlock() }; return count }
    func cancel() { lock.lock(); count += 1; lock.unlock() }
}
