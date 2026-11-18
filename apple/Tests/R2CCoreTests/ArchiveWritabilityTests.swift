import Foundation
import XCTest
@testable import R2CCore

/// Nov 18 S11U read-only SD card and the missed capture. Android: ArchiveWritabilityTrackerTest,
/// ArchiveWritabilityTextTest, NotCapturingStreamsTest, CaptureStreamsSafeguardsTest.
final class ArchiveWritabilityTests: XCTestCase {
    private var now = Date(timeIntervalSince1970: 1_000_000)
    private var logs: [String] = []
    private var events = 0
    private lazy var tracker: ArchiveWritabilityTracker = {
        let t = ArchiveWritabilityTracker(clock: { [unowned self] in self.now }, log: { [unowned self] in self.logs.append($0) })
        t.addListener { [unowned self] _, _ in self.events += 1 }
        return t
    }()
    private let erofs = NSError(domain: NSPOSIXErrorDomain, code: Int(EROFS))

    func testClassifiesReadOnlyAndIoErrors() {
        XCTAssertEqual(ArchiveWritabilityTracker.classify(erofs), .readOnly)
        XCTAssertEqual(ArchiveWritabilityTracker.classify(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteVolumeReadOnlyError)), .readOnly)
        XCTAssertEqual(ArchiveWritabilityTracker.classify(NSError(domain: "x", code: 1, userInfo: [NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: Int(EIO))])), .ioError)
        XCTAssertNil(ArchiveWritabilityTracker.classify(NSError(domain: "x", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing file"])))
        XCTAssertNil(ArchiveWritabilityTracker.classify(nil))
    }

    func testStickyWithOneEventPerStateChange() {
        XCTAssertTrue(tracker.noteFailure(erofs, where: "check"))
        XCTAssertTrue(tracker.noteFailure(erofs, where: "check"))
        XCTAssertFalse(tracker.writable)
        XCTAssertEqual(events, 1)
        XCTAssertEqual(logs.count, 1)
        XCTAssertFalse(tracker.noteFailure(NSError(domain: "x", code: 3), where: "scan"))
        XCTAssertFalse(tracker.writable)
    }

    func testPeriodicRecheckAtMostEveryFiveMinutes() {
        var probes = 0
        XCTAssertFalse(tracker.recheckDue(.periodic))
        tracker.noteFailure(erofs, where: "check")
        now += ArchiveWritabilityTracker.recheckInterval - 1
        tracker.recheck(.periodic) { probes += 1 }
        XCTAssertEqual(probes, 0)
        now += 1
        tracker.recheck(.periodic) { probes += 1; throw self.erofs }
        XCTAssertEqual(probes, 1)
        XCTAssertEqual(events, 1)
        now += ArchiveWritabilityTracker.recheckInterval
        XCTAssertTrue(tracker.recheck(.periodic) { probes += 1 })
        XCTAssertEqual(events, 2)
        XCTAssertEqual(tracker.verdict.generation, 2)
    }

    func testRemountAndFolderPickAlwaysProbe() {
        tracker.noteFailure(erofs, where: "check")
        XCTAssertFalse(tracker.recheck(.remount) { throw self.erofs })
        XCTAssertTrue(tracker.recheck(.folderPicked) {})
    }

    func testTextMatchesAndroid() {
        let card = ArchiveWritabilityVerdict(writable: false, problem: .readOnly, removable: true, generation: 1)
        XCTAssertEqual(ArchiveWritabilityText.alertTitle(card), "SD card is read-only")
        XCTAssertTrue(ArchiveWritabilityText.alertMessage(card, fallbackAvailable: true).hasPrefix("SD card is read-only (likely failing). Recording to it is paused; live video still works."))
        XCTAssertTrue(ArchiveWritabilityText.alertMessage(card, fallbackAvailable: false).contains("Recording is paused; live video still works."))
        XCTAssertEqual(ArchiveWritabilityText.chip(card, fallbackAvailable: false), "SD card read-only · recording paused")
        let onDevice = ArchiveWritabilityVerdict(writable: false, problem: .ioError)
        XCTAssertEqual(ArchiveWritabilityText.chip(onDevice, fallbackAvailable: true), "Archive storage I/O errors · saving to tablet")
        XCTAssertTrue(ArchiveWritabilityText.status(onDevice, fallbackAvailable: true) { _ in "7:01 AM" }.hasPrefix("Archive storage has I/O errors (likely failing) since 7:01 AM."))
    }

    func testNotCapturingPanelPolicy() {
        typealias S = NotCapturingStreamsPolicy.Stream
        let drone = S(sourcePath: "RC2/1sar7DjMtrc4td", designator: "1sar7DjMtrc4td", publisherID: "c1", live: true)
        let connecting = S(sourcePath: "RC2/alpha", designator: "alpha", publisherID: nil, live: false)
        XCTAssertTrue(NotCapturingStreamsPolicy.pending([drone], captureEnabled: true, dismissed: []).isEmpty)
        let pending = NotCapturingStreamsPolicy.pending([drone, connecting], captureEnabled: false, dismissed: [])
        XCTAssertEqual(pending.map(\.designator), ["1sar7DjMtrc4td"])
        XCTAssertEqual(NotCapturingStreamsPolicy.title(pending[0]), "Not capturing stream: 1sar7DjMtrc4td")
        XCTAssertTrue(NotCapturingStreamsPolicy.pending([drone], captureEnabled: false, dismissed: [drone.key]).isEmpty)
        let republished = S(sourcePath: drone.sourcePath, designator: drone.designator, publisherID: "c2", live: true)
        XCTAssertEqual(NotCapturingStreamsPolicy.pending([republished], captureEnabled: false, dismissed: [drone.key]).count, 1)
        XCTAssertEqual(NotCapturingStreamsPolicy.confirm, "Confirm")
        XCTAssertEqual(NotCapturingStreamsPolicy.turnOn, "Turn on Stream capture")
    }

    func testCaptureStreamsLogAndDefault() {
        XCTAssertEqual(CaptureStreamsLog.change(old: true, new: false, source: CaptureStreamsLog.settings), "Capture Streams changed old=true new=false source=Settings UI")
        XCTAssertTrue(CaptureStreamsLog.loaded(stored: nil))
        XCTAssertFalse(CaptureStreamsLog.loaded(stored: false))
        XCTAssertEqual(CaptureStreamsLog.serverStart(false), "Capture Streams setting at media server start: OFF (streams are not recorded)")
    }

    func testRecordingRootFailureKeepsLiveVideoWithRecordingPaused() {
        struct Failure: Error {}
        let temporary = URL(fileURLWithPath: "/tmp/r2c-staging", isDirectory: true)
        let failed = RecordingRootChoice.resolve(durable: { throw Failure() }, temporary: temporary)
        XCTAssertEqual(failed.root, temporary)
        XCTAssertFalse(failed.recordable)
        XCTAssertTrue(failed.warning?.contains("live video continues") ?? false)
        let durable = URL(fileURLWithPath: "/tmp/r2c-durable", isDirectory: true)
        let ok = RecordingRootChoice.resolve(durable: { durable }, temporary: temporary)
        XCTAssertEqual(ok, RecordingRootChoice(root: durable, recordable: true, warning: nil))
    }
}
