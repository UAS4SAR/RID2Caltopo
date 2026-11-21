import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxPopupPlacementTest.kt (Nov 20 A5 test: the tap panel ran off the
/// screen and under the stream PiP), from the shared test-fixtures/btrx/popup-placement.tsv.
final class BtrxPopupPlacementTests: XCTestCase {
    func testSharedFixtureMatchesAndroid() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("test-fixtures/btrx/popup-placement.tsv")
        let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }.map { $0.components(separatedBy: "\t") }
        XCTAssertEqual(rows.count, 10)
        for r in rows {
            let f = { (i: Int) -> Double in Double(r[i])! }
            let obstacle = r[8] == "-" ? nil : BtrxPopupPlacement.Box(left: f(8), top: f(9), right: f(10), bottom: f(11))
            let plan = r[1] == "point"
                ? BtrxPopupPlacement.atPoint(width: f(2), height: f(3), popupW: f(4), popupH: f(5), x: f(6), y: f(7), obstacle: obstacle, margin: 8)
                : BtrxPopupPlacement.atBottomStart(width: f(2), height: f(3), popupW: f(4), popupH: f(5), left: f(6), bottom: f(7),
                                                   obstacle: obstacle, margin: 8)
            let name = r[0]
            XCTAssertEqual(plan.choice, Int(r[12])!, name)
            XCTAssertEqual(plan.x, f(13), accuracy: 0.5, name)
            XCTAssertEqual(plan.y, f(14), accuracy: 0.5, name)
            XCTAssertEqual(plan.maxHeight, f(15), accuracy: 0.5, name)
            XCTAssertEqual(plan.clear, r[16] == "1", name)
            let w = min(f(4), f(2) - 16), h = min(f(5), plan.maxHeight)
            XCTAssertTrue(plan.x >= 7.99 && plan.y >= 7.99 && plan.x + w <= f(2) - 7.99 && plan.y + h <= f(3) - 7.99, name)
            if plan.clear, let obstacle {
                XCTAssertFalse(BtrxPopupPlacement.overlaps(.init(left: plan.x, top: plan.y, right: plan.x + w, bottom: plan.y + h), obstacle, margin: 8), name)
            }
        }
    }
}
