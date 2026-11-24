import XCTest
@testable import R2CCore

/// Swift copy of Android's FixedWidthStatusLabelTest.kt (Ken, Nov 23: the drone telemetry label on
/// the map jittered as ATO/AGL digits came and went). Shared test-fixtures/map-label/fixed-width-status.tsv.
final class FixedWidthStatusLabelTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()

    func testSharedFixture() throws {
        let rows = try String(contentsOf: Self.root.appendingPathComponent("test-fixtures/map-label/fixed-width-status.tsv"), encoding: .utf8)
            .split(separator: "\n").map(String.init).filter { !$0.isEmpty && !$0.hasPrefix("#") }.map { $0.components(separatedBy: "\t") }
        XCTAssertEqual(rows.count, 9)
        for r in rows { XCTAssertEqual(fixedWidthStatusLabel(r[0]), r[1].replacingOccurrences(of: "_", with: String(figureSpace)), r[0]) }
    }

    func testSameWidthForOneTwoAndThreeDigits() throws {
        let a = fixedWidthStatusLabel("ATO:6' AGL:9' AOL:-- RNG:42' HDG:7°")
        let b = fixedWidthStatusLabel("ATO:66' AGL:99' AOL:-- RNG:420' HDG:77°")
        let c = fixedWidthStatusLabel("ATO:-66' AGL:999' AOL:-- RNG:4200' HDG:277°")
        // Every field before RNG stays put; RNG (last) may grow (Ken, Nov 23).
        let rng = { (s: String) in s.distance(from: s.startIndex, to: s.range(of: " RNG:")!.lowerBound) }
        XCTAssertEqual(rng(a), rng(b))
        XCTAssertEqual(rng(a), rng(c))
        XCTAssertTrue(c.hasSuffix("RNG:4200'"))
        // The negative-AOL highlight still finds its value behind the padding.
        let t = fixedWidthStatusLabel("ATO:5' AOL:-5'")
        XCTAssertEqual(OperationalAircraftDisplay.negativeAOLRange(in: t).map { String(t[$0]) }, "AOL:\u{2007}-5'")
        let map = try String(contentsOf: Self.root.appendingPathComponent("apple/App/RIDTrackMapView.swift"), encoding: .utf8)
        XCTAssertTrue(map.contains("fixedWidthStatusLabel(OperationalAircraftDisplay.statusLabel("))
        XCTAssertTrue(map.contains("statusLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .bold)"))
    }
}
