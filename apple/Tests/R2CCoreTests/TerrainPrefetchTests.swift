import Foundation
import Testing
@testable import R2CCore

@Test func terrainOperatingEnvelopeCoversOneMileAtBothCellEdges() {
    let radius = OperationalTerrainPrefetch.operatingRadiusMeters
    let bounds = OperationalTerrainPrefetch.bounds(latitude: 39.001, longitude: -121.001, radiusMeters: radius)
    #expect(bounds == OperationalTerrainPrefetch.bounds(latitude: 39.0049, longitude: -121.0049, radiusMeters: radius))
    let latPad = radius / 111_195
    let lonPad = latPad / cos(39.005 * .pi / 180)
    #expect(bounds.south <= 39.0 - latPad)
    #expect(bounds.north >= 39.005 + latPad)
    #expect(bounds.west <= -121.005 - lonPad)
    #expect(bounds.east >= -121.0 + lonPad)
}

@Test func terrainOperatingAreaSelectsFourCornerTiles() throws {
    func tile(_ name: String, _ south: Double, _ north: Double, _ west: Double, _ east: Double) -> [String: Any] {
        ["downloadURL": "https://example.test/\(name).tif", "sizeInBytes": 350_000_000,
         "boundingBox": ["minY": south, "maxY": north, "minX": west, "maxX": east]]
    }
    let data = try JSONSerialization.data(withJSONObject: ["items": [
        tile("sw", 38.9, 39.0, -121.1, -121.0), tile("se", 38.9, 39.0, -121.0, -120.9),
        tile("nw", 39.0, 39.1, -121.1, -121.0), tile("ne", 39.0, 39.1, -121.0, -120.9),
        tile("far", 40.0, 40.1, -120.0, -119.9)
    ]])
    let bounds = OperationalTerrainPrefetch.bounds(latitude: 39.0001, longitude: -121.0001,
                                                   radiusMeters: OperationalTerrainPrefetch.operatingRadiusMeters)
    let products = try OperationalS1MCatalog.products(data: data, intersecting: bounds)
    #expect(products.count == 4)
    #expect(!products.contains { $0.url.lastPathComponent == "far.tif" })
}

@Test func verticalClueUsesLocalTerrainWithoutHeadingOrAGL() async {
    let result = await OperationalClueGeometry.projectWithTerrain(
        droneLatitude: 39, droneLongitude: -105, droneAltitudeMeters: 550,
        headingDegrees: nil, aglMeters: nil, gimbalAngleDegrees: -90,
        sampleElevationMeters: { lat, lon in
            #expect(lat == 39 && lon == -105)
            return OperationalTerrainSample(elevationMeters: 487, source: "usgs-geotiff-local-1m", horizontalResolutionMeters: 1)
        }
    )
    #expect(result.latitude == 39 && result.longitude == -105)
    #expect(result.altitudeMeters == 487)
    #expect(result.terrainProjectionApplied)
    #expect(result.demSource == "usgs-geotiff-local-1m")
    #expect(result.demResolutionMeters == 1)
}

@Test func verticalClueRetainsFlatEstimateWhenTerrainUnavailable() async {
    let result = await OperationalClueGeometry.projectWithTerrain(
        droneLatitude: 39, droneLongitude: -105, droneAltitudeMeters: 550,
        headingDegrees: 0, aglMeters: 30, gimbalAngleDegrees: -90,
        sampleElevationMeters: { _, _ in nil }
    )
    #expect(result.altitudeMeters == 520)
    #expect(!result.terrainProjectionApplied)
}
