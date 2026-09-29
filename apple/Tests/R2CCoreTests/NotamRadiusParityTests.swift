import Foundation
import Testing
@testable import R2CCore

struct NotamRadiusParityTests {
    let pilot = OperationalNotamCoordinate(latitude: 39.153128, longitude: -121.132875)

    func feature(geometry: [String: Any]?, classification: String = "FDC") -> [String: Any] {
        var result: [String: Any] = ["properties": ["coreNOTAMData": ["notam": [
            "id": "service-area", "icaoLocation": "KZOA", "number": "6/5757", "year": "2026",
            "classification": classification,
            "text": "AIRSPACE ADS-B SER MAY NOT BE AVBL WI AN AREA DEFINED AS 590NM RADIUS OF 352024N1190648W"
        ]]]]
        result["geometry"] = geometry
        return result
    }

    @Test func serviceAreaUsesTextRadiusInsteadOfDistantReferencePoint() throws {
        let notice = try #require(OperationalNotamParser.parseFeature(feature(geometry: [
            "type": "GeometryCollection", "geometries": [["type": "Point", "coordinates": [-122.0, 38.4]]]
        ]), pilot: pilot, operatingRadiusNM: 0.868976))
        #expect(notice.intersectsPilotArea)
        #expect(notice.distanceNM == 0)
        #expect(notice.severity == .normal)
    }

    @Test func suppliedPolygonIsPreserved() throws {
        let notice = try #require(OperationalNotamParser.parseFeature(feature(geometry: [
            "type": "Polygon", "coordinates": [[[-110.0, 30.0], [-109.0, 30.0], [-109.0, 31.0], [-110.0, 30.0]]]
        ]), pilot: pilot, operatingRadiusNM: 0.868976))
        #expect(!notice.intersectsPilotArea)
    }

    @Test func missingGeometryUsesFDCRadius() throws {
        let notice = try #require(OperationalNotamParser.parseFeature(feature(geometry: nil), pilot: pilot, operatingRadiusNM: 0.868976))
        #expect(notice.distanceNM == 0)
        #expect(notice.intersectsPilotArea)
    }
}
