import XCTest
@testable import R2CCore

/// Swift copy of Android's RecordingMarkerPolicyTest / RecordingStatusTest / recorder-line tests.
final class RecordingMarkerPolicyTests: XCTestCase {
    private let ready = RecordingInputs(serverRunning: true, captureEnabled: true, storageBlocked: false, recorderReady: true, archiveWritable: true)

    private func marker(recording: Bool = false, inputs: RecordingInputs? = nil, waiting: TimeInterval = 60,
                        live: Bool = true, local: Bool = false) -> RecordingMarker {
        RecordingMarkerPolicy.marker(live: live, localPlayback: local, recorderActive: recording, inputs: inputs ?? ready, waitingFor: waiting)
    }

    private func with(_ change: (inout RecordingInputs) -> Void) -> RecordingInputs { var i = ready; change(&i); return i }

    func testHiddenWhenNotLiveOrLocalPlayback() {
        XCTAssertEqual(marker(live: false).state, .hidden)
        XCTAssertEqual(marker(local: true).state, .hidden)
    }

    func testRedRecWhenTheRecorderIsWriting() {
        XCTAssertEqual(marker(recording: true).state, .recording)
        XCTAssertEqual(marker(recording: true).label, "● REC")
        XCTAssertEqual(marker(recording: true, inputs: with { $0.captureEnabled = false }).state, .recording)
    }

    func testCaptureOffIsNotRecordingImmediately() {
        let m = marker(inputs: with { $0.captureEnabled = false }, waiting: 0)
        XCTAssertEqual(m.state, .notRecording)
        XCTAssertEqual(m.reason, .captureOff)
        XCTAssertEqual(m.label, "NOT RECORDING")
        XCTAssertTrue(RecordingMarkerPolicy.tapTurnsOnCapture(m))
    }

    func testStorageReasons() {
        XCTAssertEqual(marker(inputs: with { $0.storageBlocked = true }, waiting: 0).reason, .storageBlocked)
        XCTAssertEqual(marker(inputs: with { $0.storageBlocked = true; $0.archiveWritable = false }, waiting: 0).reason, .archiveNotWritable)
        XCTAssertFalse(RecordingMarkerPolicy.tapTurnsOnCapture(marker(inputs: with { $0.storageBlocked = true })))
    }

    func testStartingDuringGraceThenNotRecording() {
        XCTAssertEqual(marker(waiting: 2).state, .starting)
        XCTAssertEqual(marker(waiting: RecordingMarkerPolicy.startGraceSeconds - 0.001).state, .starting)
        let late = marker(waiting: RecordingMarkerPolicy.startGraceSeconds)
        XCTAssertEqual(late.state, .notRecording)
        XCTAssertEqual(late.reason, .recorderNotStarted)
        XCTAssertEqual(marker(inputs: with { $0.recorderReady = false }).reason, .recorderNotReady)
    }

    func testTextsMatchAndroid() {
        XCTAssertEqual(RecordingMarkerPolicy.spokenPhrase, "Video not recording")
        XCTAssertEqual(RecordingMarkerPolicy.turnOn, "Turn on")
        for reason in NotRecordingReason.allCases { XCTAssertFalse(RecordingMarkerPolicy.reasonText(reason).isEmpty) }
        XCTAssertTrue(RecordingMarkerPolicy.promptText(designator: "Alpha", reason: .captureOff).hasPrefix("Alpha: "))
    }

    func testAggregateShowsWorstAndNamesItWhenSeveral() {
        let rec = RecordingMarker(.recording)
        let off = RecordingMarker(.notRecording, reason: .captureOff)
        XCTAssertNil(RecordingMarkerPolicy.aggregate([]))
        XCTAssertEqual(RecordingMarkerPolicy.aggregate([("A", rec)])?.marker, rec)
        let both = RecordingMarkerPolicy.aggregate([("A", rec), ("B", off)])
        XCTAssertEqual(both?.marker, off)
        XCTAssertEqual(both?.designator, "B")
        XCTAssertNil(RecordingMarkerPolicy.aggregate([("B", off)])?.designator)
    }

    func testConnectWarningFiresOncePerPublishAndWaitsForGrace() {
        var policy = RecordingConnectWarningPolicy()
        let off = RecordingMarker(.notRecording, reason: .captureOff)
        XCTAssertTrue(policy.evaluate([.init(key: "a#1", designator: "Alpha", marker: RecordingMarker(.starting))]).isEmpty)
        XCTAssertEqual(policy.evaluate([.init(key: "a#1", designator: "Alpha", marker: off)]),
                       [.init(key: "a#1", designator: "Alpha", reason: .captureOff)])
        XCTAssertTrue(policy.evaluate([.init(key: "a#1", designator: "Alpha", marker: off)]).isEmpty)
        XCTAssertEqual(policy.evaluate([.init(key: "a#2", designator: "Alpha", marker: off)]).count, 1)
    }

    func testConnectWarningSilentWhenRecordingStartsFirst() {
        var policy = RecordingConnectWarningPolicy()
        XCTAssertTrue(policy.evaluate([.init(key: "a#1", designator: "Alpha", marker: RecordingMarker(.recording))]).isEmpty)
        XCTAssertTrue(policy.evaluate([.init(key: "a#1", designator: "Alpha", marker: RecordingMarker(.notRecording, reason: .recorderNotStarted))]).isEmpty)
    }

    func testSnapshotFollowsRecorderStartAndStop() {
        var s = RecordingStatusSnapshot()
        s.update(ready)
        s.streamStarted("alpha", at: 100)
        XCTAssertEqual(s.marker(sourcePath: "alpha", live: true, now: 101).state, .starting)
        s.recorderStarted("/alpha/")
        XCTAssertEqual(s.marker(sourcePath: "alpha", live: true, now: 101).state, .recording)
        s.recorderStopped("alpha", at: 200)
        XCTAssertEqual(s.marker(sourcePath: "alpha", live: true, now: 205).state, .starting)
        XCTAssertEqual(s.marker(sourcePath: "alpha", live: true, now: 210).state, .notRecording)
        s.recorderStarted("alpha")
        s.serverStarted(at: 300)
        XCTAssertTrue(s.recordingPaths.isEmpty)
        s.recorderStarted("alpha")
        s.update(with { $0.serverRunning = false })
        XCTAssertTrue(s.recordingPaths.isEmpty)
    }

    func testParserReadsRecorderLines() {
        var parser = MediaMTXLogEventParser()
        XCTAssertEqual(parser.parse(line: "[path alpha] [recorder] recording 2 tracks (H264, MPEG-4 Audio)"), .recorderStarted(path: "alpha"))
        XCTAssertEqual(parser.parse(line: "[path alpha] [recorder] recording 1 track (H265)"), .recorderStarted(path: "alpha"))
        XCTAssertEqual(parser.parse(line: "[path alpha] [recorder] recording stopped"), .recorderStopped(path: "alpha"))
        XCTAssertEqual(parser.parse(line: "[path alpha] [recorder] no supported tracks found, skipping recording"), .recorderStopped(path: "alpha"))
        XCTAssertEqual(parser.parse(line: "[path alpha] [recorder] record file complete path=/r/a.mp4 durationMs=5"),
                       .recordFileCompleted(path: "alpha", filePath: "/r/a.mp4", durationMilliseconds: 5))
    }

    func testSessionLogRollsOverAtTenMegabytes() {
        XCTAssertEqual(SessionLogRollover.maxBytes, 10_000_000)
        XCTAssertTrue(SessionLogRollover.shouldRollOver(bytesWritten: 9_999_990, incomingBytes: 20, fileAttached: true))
        XCTAssertFalse(SessionLogRollover.shouldRollOver(bytesWritten: 9_999_990, incomingBytes: 10, fileAttached: true))
        XCTAssertFalse(SessionLogRollover.shouldRollOver(bytesWritten: 9_999_990, incomingBytes: 20, fileAttached: false))
        XCTAssertFalse(SessionLogRollover.shouldRollOver(bytesWritten: 0, incomingBytes: 20_000_000, fileAttached: true))
    }
}
