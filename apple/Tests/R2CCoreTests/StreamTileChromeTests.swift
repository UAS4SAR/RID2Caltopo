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
}
