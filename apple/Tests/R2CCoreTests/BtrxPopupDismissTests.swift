import Foundation
import Testing
@testable import R2CCore

/// Ken, Nov 23 field test: map taps re-opened the panel and popups ran under the attribution.
@Suite("BTRX popup dismissal and attribution")
struct BtrxPopupDismissTests {
    @Test func popupsUseOnlyTheMapAboveTheAttributionStrip() {
        #expect(BtrxPopupPlacement.usableHeight(1000, reservedBottom: 0) == 1000)
        #expect(BtrxPopupPlacement.usableHeight(1000, reservedBottom: 40) == 960)
        #expect(BtrxPopupPlacement.usableHeight(1000, reservedBottom: -5) == 1000)
        #expect(BtrxPopupPlacement.usableHeight(30, reservedBottom: 40) == 1)
        let usable = BtrxPopupPlacement.usableHeight(800, reservedBottom: 48)
        let plan = BtrxPopupPlacement.atPoint(width: 1200, height: usable, popupW: 300, popupH: 400, x: 500, y: 700, obstacle: nil, margin: 8)
        #expect(plan.y + 400 <= 800 - 48 - 8 + 0.01)
        #expect(BtrxPopupPlacement.bottomAboveReserved(72, reservedBottom: 48) == 24)
        #expect(BtrxPopupPlacement.bottomAboveReserved(72, reservedBottom: 100) == 0)
    }

    @Test func mapTapWithAPopupOpenOnlyClosesIt() throws {
        let appleRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let map = try String(contentsOf: appleRoot.appendingPathComponent("App/RIDTrackMapView.swift"), encoding: .utf8)
        let handleTap = map.components(separatedBy: "@objc private func handleTap(_ gesture: UITapGestureRecognizer) {")[1]
            .components(separatedBy: "func hasSources(")[0]
        let dismiss = try #require(handleTap.range(of: "if popupOpen { onDismissPopup?(); return }"))
        let select = try #require(handleTap.range(of: "onSelect?("))
        let pinned = try #require(handleTap.range(of: "onPinned?("))
        #expect(dismiss.lowerBound < select.lowerBound && dismiss.lowerBound < pinned.lowerBound)
        let artifact = map.components(separatedBy: "@objc private func handleArtifactTap(")[1]
            .components(separatedBy: "func gestureRecognizer(")[0]
        #expect(artifact.contains("if btrxMap.popupOpen { return }"))
        #expect(map.contains("btrxPopupOpen: !inset && (btrxPanel != nil || btrxInspect != nil)"))
        #expect(map.contains("if btrxPanel == nil && btrxInspect == nil { onMapTap?() }"))
        #expect(map.components(separatedBy: "reservedBottom: mapAttributionHeight").count == 3)
    }
}
