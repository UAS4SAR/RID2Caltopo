import XCTest
@testable import R2CCore

/// Edge overrun, 3D surface detail and unknown-area fill: persistence, legacy defaults, route and report plumbing.
final class MappingPlanKnobsTests: XCTestCase {
    private let feature = Data(#"{"type":"Feature","id":"assignment-1","properties":{"class":"Assignment","title":"Test"},"geometry":{"type":"Polygon","coordinates":[[[-122,37],[-121.999,37],[-121.999,37.001],[-122,37.001],[-122,37]]]}}"#.utf8)
    private var camera: MappingResolution.Profile { MappingResolution.profiles.first { $0.id == "m4td.visible.wide" }! }
    private func parameters(_ data: Data) throws -> [String: Any] {
        (try JSONSerialization.jsonObject(with: data) as! [String: Any])["parameters"] as! [String: Any]
    }

    func testExplicitKnobsRoundTripThroughMissionJSON() throws {
        let mission = try MappingMission(feature: feature, incidentID: "MAP")
        let data = try mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround, marginMeters: 33.5, surfaceCellMeters: 0.1, fillUnknown2DAreas: false)
        let p = try parameters(data)
        XCTAssertEqual(p["marginMeters"] as? Double, 33.5)
        XCTAssertEqual(p["surfaceCellMeters"] as? Double, 0.1)
        XCTAssertEqual(p["fillUnknown2DAreas"] as? Bool, false)
        XCTAssertEqual(MappingMissionDefaults.cell(p), 0.1)
        XCTAssertFalse(MappingMissionDefaults.fillUnknown2D(p))
        XCTAssertEqual(MappingMissionDefaults.margin(p), 33.5)
        XCTAssertEqual(try MappingFlightRoute(mission: data).routeEndMarginMeters, 33.5)
    }

    func testNewMissionDefaultsAndHalfPhotoWidthMargin() throws {
        let mission = try MappingMission(feature: feature, incidentID: "MAP")
        let p = try parameters(try mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround))
        XCTAssertEqual(p["surfaceCellMeters"] as? Double, 0.2)
        XCTAssertEqual(p["fillUnknown2DAreas"] as? Bool, true)
        // 4032 px at 2 cm = 80.64 m wide -> 40.32 m
        XCTAssertEqual(p["marginMeters"] as! Double, 40.32, accuracy: 1e-9)
        XCTAssertEqual(MappingMissionDefaults.defaultMarginMeters(widthPixels: 4032, gsdCmPerPixel: 2), 40.32, accuracy: 1e-9)
        XCTAssertEqual(MappingMissionDefaults.defaultMarginMeters(widthPixels: 4032, gsdCmPerPixel: 0.2), 10)
        XCTAssertEqual(MappingMissionDefaults.defaultMarginMeters(widthPixels: 4032, gsdCmPerPixel: 10), 100)
    }

    func testOldMissionsMissingKeysReadStandardFillOnTenMeters() throws {
        let mission = try MappingMission(feature: feature, incidentID: "MAP")
        let data = try mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround)
        var object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        var p = object["parameters"] as! [String: Any]
        for key in ["marginMeters", "surfaceCellMeters", "fillUnknown2DAreas"] { p.removeValue(forKey: key) }
        object["parameters"] = p
        XCTAssertEqual(MappingMissionDefaults.cell(p), 0.2)
        XCTAssertTrue(MappingMissionDefaults.fillUnknown2D(p))
        XCTAssertEqual(MappingMissionDefaults.margin(p), 10)
        XCTAssertEqual(MappingMissionDefaults.cell(nil), 0.2)
        let legacy = try JSONSerialization.data(withJSONObject: object)
        XCTAssertEqual(try MappingFlightRoute(mission: legacy).routeEndMarginMeters, 10)
        _ = try MappingPlanningEstimate(mission: legacy, totalCapacity: 1_000_000_000_000, availableCapacity: 900_000_000_000)
    }

    func testUnsupportedValuesAreRejected() throws {
        let mission = try MappingMission(feature: feature, incidentID: "MAP")
        XCTAssertThrowsError(try mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround, surfaceCellMeters: 0.3))
        for bad in [-1.0, 101, Double.nan, Double.infinity] {
            XCTAssertThrowsError(try mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround, marginMeters: bad))
        }
        XCTAssertEqual(MappingMissionDefaults.cell(["surfaceCellMeters": 0.3]), 0.2)
        XCTAssertEqual(MappingProcessingPolicy.surfaceCellOptions, [0.2, 0.1, 0.4])
        XCTAssertEqual(MappingMissionDefaults.cellLabel(0.1), "Fine")
        XCTAssertEqual(MappingMissionDefaults.cellLabel(0.2), "Standard")
        XCTAssertEqual(MappingMissionDefaults.cellLabel(0.4), "Coarse")
    }

    func testLargerOverrunAndObliqueExtensionAddPhotosAndSummaryFields() throws {
        let mission = try MappingMission(feature: feature, incidentID: "MAP")
        let tight = try MappingFlightRoute(mission: mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround, marginMeters: 10))
        let wide = try MappingFlightRoute(mission: mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround, marginMeters: 40))
        XCTAssertGreaterThan(wide.photoEstimate, tight.photoEstimate)
        XCTAssertEqual(tight.obliqueEndExtensionMeters, 0)
        let brush = try MappingFlightRoute(mission: mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.brushTrees, canopyHeightMeters: 10, maximumObstacleHeightMeters: 10, marginMeters: 10))
        XCTAssertGreaterThan(brush.obliqueEndExtensionMeters, 0)
        let summary = try JSONSerialization.jsonObject(with: brush.summary()) as! [String: Any]
        XCTAssertEqual(summary["routeEndMarginMeters"] as? Double, 10)
        XCTAssertEqual(summary["obliqueEndExtensionMeters"] as? Double, brush.obliqueEndExtensionMeters)
        // Clamped when a saved plan carries an out-of-range margin.
        let data = try mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround)
        var object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        var p = object["parameters"] as! [String: Any]; p["marginMeters"] = 250.0; object["parameters"] = p
        XCTAssertEqual(try MappingFlightRoute(mission: JSONSerialization.data(withJSONObject: object)).routeEndMarginMeters, 100)
    }

    func testModelStorageTermScalesWithInverseCellSquared() throws {
        func storage(_ cell: Double) throws -> MappingProcessingPolicy.StorageEstimate {
            try MappingProcessingPolicy.storage(photos: 40, width: 4032, height: 3024, areaSquareMeters: 10_000, pixelMeters: 0.02, regional: false, surfaceCellMeters: cell)
        }
        let standard = try storage(0.2), fine = try storage(0.1), coarse = try storage(0.4)
        // products = 2*raster + model: Fine adds 3x and Coarse removes 0.75x the Standard model term (area/cell² * 64).
        let standardModel = 10_000 / (0.2 * 0.2) * 64
        XCTAssertEqual(Double(fine.products - standard.products), 3 * standardModel, accuracy: 4)
        XCTAssertEqual(Double(coarse.products - standard.products), -0.75 * standardModel, accuracy: 4)
        XCTAssertEqual(try MappingProcessingPolicy.storage(photos: 40, width: 4032, height: 3024, areaSquareMeters: 10_000, pixelMeters: 0.02, regional: false).products, standard.products)
        XCTAssertThrowsError(try storage(0.3))
    }

    func testPlanningEstimateReadsCellFromMission() throws {
        let mission = try MappingMission(feature: feature, incidentID: "MAP")
        func estimate(_ cell: Double) throws -> MappingPlanningEstimate {
            try MappingPlanningEstimate(mission: mission.json(cameraProfile: camera, desiredGsdCmPerPixel: 2, captureProfile: MappingProcessingPolicy.openGround, surfaceCellMeters: cell), totalCapacity: 1_000_000_000_000, availableCapacity: 900_000_000_000)
        }
        let standard = try estimate(0.2), fine = try estimate(0.1)
        XCTAssertGreaterThan(fine.storage.products, standard.storage.products)
        XCTAssertEqual(fine.photos, standard.photos)
    }

    func testLayerDecodesEstimatedFillAndFormatsInspectorLine() throws {
        let id = UUID().uuidString.lowercased()
        let json = #"{"type":"r2c_mapping_layer","version":1,"tileScheme":"xyz","layerId":"\#(id)","incidentId":"MAP","title":"T","minZoom":14,"maxZoom":20,"tileUrlTemplate":"http://127.0.0.1:8765/local-layer/\#(id)/tiles/{Z}/{X}/{Y}.png","estimatedFillFraction":0.123,"fillRadiusMeters":25}"#
        let layer = try MappingLayer.decode(Data(json.utf8), incidentID: "MAP")
        XCTAssertEqual(layer.estimatedFillSummary, "Estimated (no measured depth): 12%")
        XCTAssertEqual(layer.fillRadiusMeters, 25)
        let round = try JSONDecoder().decode(MappingLayer.self, from: JSONEncoder().encode(layer))
        XCTAssertEqual(round.estimatedFillFraction, 0.123)
        let legacyJSON = json.replacingOccurrences(of: #","estimatedFillFraction":0.123,"fillRadiusMeters":25"#, with: "")
        XCTAssertNil(try MappingLayer.decode(Data(legacyJSON.utf8), incidentID: "MAP").estimatedFillSummary)
        XCTAssertEqual(MappingLayer.estimatedFillSummary(fraction: 0.004), "Estimated (no measured depth): <1%")
    }
}
