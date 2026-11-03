import XCTest
import R2CCore
@testable import R2CMapping

// MappingLocalPreviewZoom lives in R2CCore; this file locks the App MapPane contract.

/// Package-level selection helpers for View 2D (App store mirrors this contract).
enum MappingLayerSelection {
    static func selectLocal(registered: Set<String>, layerID: String?, hasTiles: Bool) -> (selected: String?, ok: Bool, reason: String?) {
        guard let layerID else { return (nil, true, nil) }
        if !registered.contains(layerID) { return (nil, false, "unregistered") }
        if !hasTiles { return (nil, false, "no-tiles") }
        return (layerID, true, nil)
    }

    static func selectedLocal(registered: Set<String>, selectedID: String?, hasTiles: (String) -> Bool) -> (selected: String?, cleared: Bool) {
        guard let selectedID else { return (nil, false) }
        if !registered.contains(selectedID) || !hasTiles(selectedID) {
            return (nil, true)
        }
        return (selectedID, false)
    }
}

final class MappingLayerSelectionTests: XCTestCase {
    func testSelectLocalRefusesUnregisteredOrMissingTiles() {
        let id = "a817d2b0-bc06-4080-9f15-6fd4cd7e0c92"
        XCTAssertFalse(MappingLayerSelection.selectLocal(registered: [], layerID: id, hasTiles: true).ok)
        XCTAssertEqual(MappingLayerSelection.selectLocal(registered: [], layerID: id, hasTiles: true).reason, "unregistered")
        XCTAssertFalse(MappingLayerSelection.selectLocal(registered: [id], layerID: id, hasTiles: false).ok)
        XCTAssertEqual(MappingLayerSelection.selectLocal(registered: [id], layerID: id, hasTiles: false).reason, "no-tiles")
        let ok = MappingLayerSelection.selectLocal(registered: [id], layerID: id, hasTiles: true)
        XCTAssertTrue(ok.ok)
        XCTAssertEqual(ok.selected, id)
    }

    func testStaleSelectionClearedWhenTilesMissing() {
        let id = "a817d2b0-bc06-4080-9f15-6fd4cd7e0c92"
        let cleared = MappingLayerSelection.selectedLocal(registered: [id], selectedID: id, hasTiles: { _ in false })
        XCTAssertNil(cleared.selected)
        XCTAssertTrue(cleared.cleared)
        let kept = MappingLayerSelection.selectedLocal(registered: [id], selectedID: id, hasTiles: { _ in true })
        XCTAssertEqual(kept.selected, id)
        XCTAssertFalse(kept.cleared)
    }

    func testSelectLocalNilClears() {
        let result = MappingLayerSelection.selectLocal(registered: ["x"], layerID: nil, hasTiles: true)
        XCTAssertTrue(result.ok)
        XCTAssertNil(result.selected)
    }

    func testColdStartPersistedSelectionDoesNotZoomWithoutToken() {
        // Mirrors App localPreviewZoomToken: cold start / MapView recreation with a
        // persisted selection and usable bounds must not move the camera.
        XCTAssertFalse(
            MappingLocalPreviewZoom.shouldZoom(token: nil, lastConsumed: nil, hasUsableBounds: true)
        )
        XCTAssertFalse(
            MappingLocalPreviewZoom.shouldConsume(token: nil, lastConsumed: nil)
        )
        let token = UUID()
        XCTAssertTrue(
            MappingLocalPreviewZoom.shouldZoom(token: token, lastConsumed: nil, hasUsableBounds: true)
        )
        XCTAssertFalse(
            MappingLocalPreviewZoom.shouldZoom(token: token, lastConsumed: token, hasUsableBounds: true)
        )
    }
}
