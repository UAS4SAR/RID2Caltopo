import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxTelemetryRouteTest.kt (Ken, Nov 23): BTRX telemetry is always
/// shown while BTRX is enabled, in the primary window only (the larger pane in a split, 5 %
/// hysteresis, an exact tie goes to the map), and there is no way to turn it off. Shared
/// test-fixtures/btrx/telemetry-route.tsv.
final class BtrxTelemetryRouteTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()

    private func rows() throws -> [[String]] {
        try String(contentsOf: Self.root.appendingPathComponent("test-fixtures/btrx/telemetry-route.tsv"), encoding: .utf8)
            .split(separator: "\n").map(String.init).filter { !$0.isEmpty && !$0.hasPrefix("#") }.map { $0.components(separatedBy: "\t") }
    }

    private func window(_ s: String) -> BtrxTelemetryWindow? { BtrxTelemetryWindow(rawValue: s) }
    private func src(_ path: String) throws -> String { try String(contentsOf: Self.root.appendingPathComponent(path), encoding: .utf8) }

    func testConstantsMatchFixture() throws {
        var c: [String: String] = [:]
        for r in try rows() where r[0] == "const" { c[r[1]] = r[2] }
        XCTAssertEqual(Double(c["hysteresis"]!)!, BtrxTelemetryRoute.hysteresis)
        XCTAssertEqual(window(c["tie"]!), BtrxTelemetryRoute.tie)
    }

    func testPrimaryIsTheLargerPaneWithHysteresis() throws {
        let cases = try rows().filter { $0[0] == "area" }
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for r in cases {
            XCTAssertEqual(window(r[4]), BtrxTelemetryRoute.primary(mapArea: Double(r[1])!, streamArea: Double(r[2])!, previous: window(r[3])),
                           r.joined(separator: " "))
        }
    }

    func testDraggingTheDividerAboutTheMiddleDoesNotFlicker() throws {
        let drags = try rows().filter { $0[0] == "drag" }
        XCTAssertEqual(2, drags.count)
        for r in drags {
            var w = window(r[1])!
            let got = r[2].components(separatedBy: ",").map { f -> BtrxTelemetryWindow in
                w = BtrxTelemetryRoute.forStreamFraction(Double(f)!, previous: w); return w
            }
            XCTAssertEqual(r[3].components(separatedBy: ",").map { window($0) }, got, r.joined(separator: " "))
        }
    }

    func testLayoutsMapToPaneAreas() throws {
        // Full map or video, with or without the other as an inset: the inset never counts.
        for (layout, map, stream) in [(OperationalMapVideoLayout.map, 1.0, 0.0), (.mapPrimary, 1, 0), (.video, 0, 1), (.videoPrimary, 0, 1)] {
            let a = BtrxTelemetryRoute.paneAreas(layout, videoFraction: 0.5)
            XCTAssertEqual(a.map, map, "\(layout)"); XCTAssertEqual(a.stream, stream, "\(layout)")
        }
        let a = BtrxTelemetryRoute.paneAreas(.split, videoFraction: 0.7)
        XCTAssertEqual(a.map, 0.3, accuracy: 1e-9); XCTAssertEqual(a.stream, 0.7, accuracy: 1e-9)
        XCTAssertEqual(.map, BtrxTelemetryRoute.forLayout(.mapPrimary, videoFraction: 0.9, previous: .stream))
        XCTAssertEqual(.stream, BtrxTelemetryRoute.forLayout(.videoPrimary, videoFraction: 0.1, previous: .map))
        XCTAssertEqual(.map, BtrxTelemetryRoute.forLayout(.split, videoFraction: 0.5, previous: nil))
        XCTAssertEqual(.stream, BtrxTelemetryRoute.forLayout(.split, videoFraction: 0.6, previous: .map))
        XCTAssertEqual(.map, BtrxTelemetryRoute.forLayout(.split, videoFraction: 0.4, previous: .stream))
        XCTAssertEqual(.stream, BtrxTelemetryRoute.forLayout(.split, videoFraction: 0.52, previous: .stream))
        // The live view reports every layout change and divider drag.
        let view = try src("apple/App/RIDTrackMapView.swift")
        XCTAssertTrue(view.contains(".onChange(of: presentedLayout, initial: true) { _, _ in reportBtrxPaneAreas() }"))
        XCTAssertTrue(view.contains(".onChange(of: splitFraction) { _, _ in reportBtrxPaneAreas() }"))
        XCTAssertTrue(view.contains("btrxCenter.reportPaneAreas(mapArea: a.map, streamArea: a.stream)"))
    }

    func testTelemetryIsAlwaysInExactlyOneWindow() {
        for w in [BtrxTelemetryWindow.map, .stream] {
            XCTAssertNotEqual(BtrxTelemetryRoute.mapShows(bearingOn: true, window: w), BtrxTelemetryRoute.streamShows(w))
            XCTAssertFalse(BtrxTelemetryRoute.mapShows(bearingOn: false, window: w))
        }
        XCTAssertEqual("Telemetry shown on: Map (primary view)", BtrxTelemetryRoute.shownOnLine(.map))
        XCTAssertEqual("Telemetry shown on: Stream (primary view)", BtrxTelemetryRoute.shownOnLine(.stream))
    }

    func testThereIsNoWayToTurnBtrxTelemetryOff() throws {
        let center = try src("apple/Sources/R2CAppleRadios/BtrxBleCentral.swift")
        XCTAssertFalse(center.contains("func setOverlayShown"))
        XCTAssertFalse(center.contains("var overlayShown"))
        XCTAssertFalse(center.contains("public func setBearingOn"))
        XCTAssertTrue(center.contains("private func setBearingOn"))
        // The old saved "hidden" choice is dropped, never read back.
        XCTAssertTrue(center.contains("defaults.removeObject(forKey: Self.keyOverlayShown)"))
        let ui = try ["apple/App/DeskPodView.swift", "apple/App/RIDTrackMapView.swift", "apple/App/AppleStreamRegistry.swift"].map(src).joined(separator: "\n")
        for gone in ["setOverlayShown", "setBearingOn", "overlayToggle", "overlayHidden", "overlayHide", "layerSwitch", "hiddenChip"] {
            XCTAssertFalse(ui.contains(gone), gone)
        }
        let desk = try src("apple/App/DeskPodView.swift")
        XCTAssertTrue(desk.contains("Text(BtrxTelemetryRoute.shownOnLine(center.telemetryWindow))"))
        XCTAssertTrue(desk.contains("center.retryBearingLayer()"))
    }
}
