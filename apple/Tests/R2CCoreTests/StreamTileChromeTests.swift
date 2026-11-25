import XCTest
@testable import R2CCore

/// Swift copy of Android's StreamTileChromeTest.kt: the compact PiP is the video and one
/// "Observation only" badge; full-size tiles are unchanged (Ken, Nov 20).
final class StreamTileChromeTests: XCTestCase {
    func testCompactPipShowsOnlyTheBadgeAndRecordingMarker() {
        // Ken, Nov 25: "show the recording marker on the PiP too".
        let shown = StreamTileChrome.allCases.filter { StreamTileChrome.shows($0, compactPip: true) }
        XCTAssertEqual(shown, [.safetyBadge, .recordingMarker])
    }

    func testEveryTileSizeShowsTheRecordingMarker() throws {
        XCTAssertTrue(StreamTileChrome.shows(.recordingMarker, compactPip: true))
        XCTAssertTrue(StreamTileChrome.shows(.recordingMarker, compactPip: false))
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let tile = try String(contentsOf: root.appendingPathComponent("apple/App/AppleStreamRegistry.swift"), encoding: .utf8)
        XCTAssertTrue(tile.contains("AppleStreamRecordingMarker(session: session)"))
        // Ken, Nov 25: the status-row chip was redundant; the tile marker is adequate.
        let map = try String(contentsOf: root.appendingPathComponent("apple/App/RIDTrackMapView.swift"), encoding: .utf8)
        XCTAssertFalse(map.contains("AppleRecordingStatusChip()"))
        let content = try String(contentsOf: root.appendingPathComponent("apple/App/ContentView.swift"), encoding: .utf8)
        XCTAssertTrue(content.contains(".modifier(AppleRecordingPromptHost())"))
        XCTAssertTrue(content.contains("AppleRecordingStatus.shared.start()"))
        let log = try String(contentsOf: root.appendingPathComponent("apple/App/AppleDiagnosticLog.swift"), encoding: .utf8)
        XCTAssertTrue(log.contains("SessionLogRollover.shouldRollOver("))
    }

    func testFullSizeTileUnchanged() {
        let hidden = StreamTileChrome.allCases.filter { !StreamTileChrome.shows($0, compactPip: false) }
        XCTAssertEqual(hidden, [.safetyBadge])
        XCTAssertEqual(StreamTileChrome.allCases.count, 11)
    }

    func testBadgeTextStartsTheFullNotice() {
        XCTAssertEqual(StreamTileChrome.badgeText, "Observation only")
        XCTAssertTrue(ApplicationLaunchDisclaimer.text.contains("Observation only — do not pilot using this video."))
    }

    func testStreamDoubleTapOpensDeskPodOnlyWhenConnectedAndNotOnThePip() throws {
        XCTAssertTrue(StreamTileChrome.streamDoubleTapOpensDeskPod(compactPip: false, deskPodConnected: true))
        XCTAssertFalse(StreamTileChrome.streamDoubleTapOpensDeskPod(compactPip: false, deskPodConnected: false))
        XCTAssertFalse(StreamTileChrome.streamDoubleTapOpensDeskPod(compactPip: true, deskPodConnected: true))
        XCTAssertFalse(StreamTileChrome.streamDoubleTapOpensDeskPod(compactPip: true, deskPodConnected: false))
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let map = try String(contentsOf: root.appendingPathComponent("apple/App/RIDTrackMapView.swift"), encoding: .utf8)
        XCTAssertTrue(map.contains("onDoubleTapSession: { _, _, _ in"))
        XCTAssertTrue(map.contains("guard !compact, btrxCenter?.openDeskPodScreenFromMap() == true else { return }"))
        XCTAssertTrue(map.contains("Button(action: { beginSnapshotCapture() })"))
        XCTAssertTrue(map.contains("Image(systemName: \"camera.fill\")"))
        let tile = try String(contentsOf: root.appendingPathComponent("apple/App/AppleStreamRegistry.swift"), encoding: .utf8)
        XCTAssertTrue(tile.contains("guard !compactPip else { return }"))
    }
}
