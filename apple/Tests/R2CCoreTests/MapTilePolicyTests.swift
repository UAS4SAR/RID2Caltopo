import Foundation
import Testing
@testable import R2CCore

@Test func publicOsmAndEsriBasemapsCannotBecomeOfflineInputs() {
    #expect(!OperationalMapTilePolicy.allowsOfflineDownload(.openStreetMap))
    #expect(!OperationalMapTilePolicy.allowsOfflineDownload(.imagery))
    #expect(OperationalMapTilePolicy.allowsOfflineDownload(.usgsScannedTopo))
    #expect(OperationalMapTilePolicy.allowsOfflineDownload(.usfsTopo))
    for layer in OperationalMapBaseLayer.allCases {
        #expect(layer.tileURL(zoom: 8, x: 40, y: 80) != nil)
    }
}
@Test func packageExportRejectsExistingAndLegacyPublicTiles() {
    for source in ["ArcGIS-WorldImagery", "OSM-Standard", "Mapnik", "MAPNIK", "Imagery", "unknown"] {
        #expect(!OperationalMapTilePolicy.allowsPackageExport(source: source))
    }
    #expect(OperationalMapTilePolicy.allowsPackageExport(source: "USGS-Contours"))
    #expect(OperationalMapTilePolicy.allowsPackageExport(source: "USGS-ScannedTopo"))
    #expect(OperationalMapTilePolicy.allowsPackageExport(source: "USFS-Topo"))
}
@Test func cacheAgeClampsOldSettingsToSupportedOSMFallback() {
    for age in [Int.min, -1, 0, 1, 6, 7] { #expect(OperationalMapTilePolicy.cacheAgeDays(age) == 7) }
    #expect(OperationalMapTilePolicy.cacheAgeDays(30) == 30)
    #expect(OperationalMapTilePolicy.cacheAgeDays(365) == 365)
    #expect(OperationalMapTilePolicy.cacheAgeDays(Int.max) == 3650)
    #expect(OperationalMapTilePolicy.openStreetMapCopyrightURL.absoluteString == "https://www.openstreetmap.org/copyright")
}

@Test func fetchConcurrencyAllowsBoundedParallelOnlyForUsgsHostedTopo() {
    #expect(OperationalMapTilePolicy.liveFetchConcurrency(.openStreetMap) == 1)
    #expect(OperationalMapTilePolicy.liveFetchConcurrency(.imagery) == 1)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(.openStreetMap) == 1)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(.imagery) == 1)
    #expect(OperationalMapTilePolicy.liveFetchConcurrency(.usgsScannedTopo) == 5)
    #expect(OperationalMapTilePolicy.liveFetchConcurrency(.usfsTopo) == 5)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(.usgsScannedTopo) == 5)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(.usfsTopo) == 5)
    #expect((4...6).contains(OperationalMapTilePolicy.usgsHostedFetchConcurrency))
}
