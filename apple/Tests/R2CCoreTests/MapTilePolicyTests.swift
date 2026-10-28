import Foundation
import Testing
@testable import R2CCore

@Test func publicBasemapsCannotBecomeOfflineInputs() {
    for layer in OperationalMapBaseLayer.allCases {
        #expect(!OperationalMapTilePolicy.allowsOfflineDownload(layer))
        #expect(layer.tileURL(zoom: 8, x: 40, y: 80) != nil)
    }
}
@Test func packageExportRejectsExistingAndLegacyPublicTiles() {
    for source in ["ArcGIS-WorldImagery", "OSM-Standard", "Mapnik", "MAPNIK", "Imagery", "unknown"] {
        #expect(!OperationalMapTilePolicy.allowsPackageExport(source: source))
    }
    #expect(OperationalMapTilePolicy.allowsPackageExport(source: "USGS-Contours"))
}
@Test func cacheAgeClampsOldSettingsToSupportedOSMFallback() {
    for age in [Int.min, -1, 0, 1, 6, 7] { #expect(OperationalMapTilePolicy.cacheAgeDays(age) == 7) }
    #expect(OperationalMapTilePolicy.cacheAgeDays(30) == 30)
    #expect(OperationalMapTilePolicy.cacheAgeDays(365) == 365)
    #expect(OperationalMapTilePolicy.cacheAgeDays(Int.max) == 3650)
    #expect(OperationalMapTilePolicy.openStreetMapCopyrightURL.absoluteString == "https://www.openstreetmap.org/copyright")
}
