import XCTest
@testable import R2CCore

/// Swift copy of Android's StreamTileChromeTest.kt: the compact PiP is the video and one
/// "Observation only" badge; full-size tiles are unchanged (Ken, Nov 20).
final class StreamTileChromeTests: XCTestCase {
    func testCompactPipShowsOnlyTheBadge() {
        let shown = StreamTileChrome.allCases.filter { StreamTileChrome.shows($0, compactPip: true) }
        XCTAssertEqual(shown, [.safetyBadge])
    }

    func testFullSizeTileUnchanged() {
        let hidden = StreamTileChrome.allCases.filter { !StreamTileChrome.shows($0, compactPip: false) }
        XCTAssertEqual(hidden, [.safetyBadge])
        XCTAssertEqual(StreamTileChrome.allCases.count, 10)
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
