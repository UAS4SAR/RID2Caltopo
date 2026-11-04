import XCTest
@testable import R2CCore

final class CluePhotoGeometrySyncTests: XCTestCase {
    func testIdenticalPointsDoNotNeedRepair() {
        XCTAssertFalse(CluePhotoGeometrySync.needsRepair(
            markerLatitude: 47.0,
            markerLongitude: -122.0,
            linkLatitude: 47.0,
            linkLongitude: -122.0
        ))
    }

    func testSubMeterOffsetDoesNotNeedRepair() {
        XCTAssertFalse(CluePhotoGeometrySync.needsRepair(
            markerLatitude: 47.0,
            markerLongitude: -122.0,
            linkLatitude: 47.0000027,
            linkLongitude: -122.0
        ))
    }

    func testMultiMeterOffsetNeedsRepair() {
        XCTAssertTrue(CluePhotoGeometrySync.needsRepair(
            markerLatitude: 47.0,
            markerLongitude: -122.0,
            linkLatitude: 47.0001,
            linkLongitude: -122.0
        ))
    }

    func testPointLatLngReadsFeatureCoordinates() {
        let feature: [String: Any] = [
            "id": "x",
            "type": "Feature",
            "geometry": [
                "type": "Point",
                "coordinates": [-122.5, 47.25],
            ],
            "properties": ["class": "Marker"],
        ]
        let ll = CluePhotoGeometrySync.pointLatLng(from: feature)
        XCTAssertEqual(ll?.latitude ?? 0, 47.25, accuracy: 1e-9)
        XCTAssertEqual(ll?.longitude ?? 0, -122.5, accuracy: 1e-9)
    }

    func testCopyPropertiesPreservesParentAndBackendMedia() {
        let feature: [String: Any] = [
            "properties": [
                "class": "MapMediaObject",
                "parentId": "Marker:abc",
                "backendMediaId": "m1",
                "title": "t",
            ],
        ]
        let props = CluePhotoGeometrySync.copyProperties(from: feature)
        XCTAssertEqual(props["class"] as? String, "MapMediaObject")
        XCTAssertEqual(props["parentId"] as? String, "Marker:abc")
        XCTAssertEqual(props["backendMediaId"] as? String, "m1")
        XCTAssertEqual(props["title"] as? String, "t")
    }
}
