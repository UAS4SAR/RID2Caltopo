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
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(sourceId: "USGS-Contours") == 1)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(sourceId: "OSM-Standard") == 1)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(sourceId: "ArcGIS-WorldImagery") == 1)

    #expect(OperationalMapTilePolicy.liveFetchConcurrency(.usgsScannedTopo) == 5)
    #expect(OperationalMapTilePolicy.liveFetchConcurrency(.usfsTopo) == 5)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(.usgsScannedTopo) == 5)
    #expect(OperationalMapTilePolicy.offlineFetchConcurrency(.usfsTopo) == 5)
    #expect((4...6).contains(OperationalMapTilePolicy.usgsHostedFetchConcurrency))
    #expect(
        OperationalMapTilePolicy.offlineFetchThreadPool
            == OperationalMapTilePolicy.usgsHostedFetchConcurrency * 2 + 1
    )

    for source in OperationalMapTileSourceRegistry.all {
        let parallel = source.fetchConcurrency > OperationalMapTilePolicy.defaultFetchConcurrency
        #expect(parallel == (source.id == "USGS-ScannedTopo" || source.id == "USFS-Topo"))
    }
}

@Test func registryOfflineAllowedSourcesMatchDownloadMapCheckboxes() {
    let offlineIds = OperationalMapTileSourceRegistry.offlineAllowedSources.map(\.id).sorted()
    #expect(offlineIds == ["USFS-Topo", "USGS-Contours", "USGS-ScannedTopo"])
    #expect(OperationalMapTileSourceRegistry.offlineAllowedBaseSources.map(\.id)
        == ["USGS-ScannedTopo", "USFS-Topo"])

    for source in OperationalMapTileSourceRegistry.all where source.id == "OSM-Standard" || source.id == "ArcGIS-WorldImagery" {
        #expect(!source.offlineAllowed)
    }
    for source in OperationalMapTileSourceRegistry.offlineAllowedSources {
        #expect(source.offlineAllowed)
    }

    #expect(OperationalMapTileSourceRegistry.crossPlatformIds == [
        "ArcGIS-WorldImagery",
        "OSM-Standard",
        "USFS-Topo",
        "USGS-Contours",
        "USGS-ScannedTopo",
    ])
}

@Test func registryClampZoomRangeHonorsNativeSourceLimits() {
    #expect(OperationalMapTileSourceRegistry.clampZoomRange(
        sourceId: "USGS-ScannedTopo", minZoom: 10, maxZoom: 14
    ) == 10...14)
    #expect(OperationalMapTileSourceRegistry.clampZoomRange(
        sourceId: "USGS-ScannedTopo", minZoom: -1, maxZoom: 40
    ) == 0...16)
    #expect(OperationalMapTileSourceRegistry.clampZoomRange(
        sourceId: "USFS-Topo", minZoom: 18, maxZoom: 20
    ) == 16...16)
    #expect(OperationalMapTileSourceRegistry.clampZoomRange(
        sourceId: "USGS-Contours", minZoom: 0, maxZoom: 22
    ) == 0...22)
    #expect(OperationalMapTileSourceRegistry.clampZoomRange(
        sourceId: "unknown", minZoom: 0, maxZoom: 10
    ) == nil)
}

@Test func offlineEstimateSumsTileCountsAcrossSelectedSources() {
    let bounds = OperationalMapBounds(north: 39.05, south: 39.0, west: -120.05, east: -120.0)
    let usgs = OperationalOfflineSourceZoom(minZoom: 12, maxZoom: 13).clamped(to: "USGS-ScannedTopo")
    let usfs = OperationalOfflineSourceZoom(minZoom: 12, maxZoom: 12).clamped(to: "USFS-Topo")
    let contours = OperationalOfflineSourceZoom(minZoom: 14, maxZoom: 14).clamped(to: "USGS-Contours")

    let usgsCount = OperationalOfflineMapPlanner.tileCount(
        bounds: bounds, minimumZoom: usgs.minZoom, maximumZoom: usgs.maxZoom
    )
    let usfsCount = OperationalOfflineMapPlanner.tileCount(
        bounds: bounds, minimumZoom: usfs.minZoom, maximumZoom: usfs.maxZoom
    )
    let contourCount = OperationalOfflineMapPlanner.tileCount(
        bounds: bounds, minimumZoom: contours.minZoom, maximumZoom: contours.maxZoom
    )
    #expect(usgsCount > 0 && usfsCount > 0 && contourCount > 0)

    let allSelected = usgsCount + usfsCount + contourCount
    let topoOnly = usgsCount + usfsCount
    #expect(allSelected == topoOnly + contourCount)
    #expect(allSelected > usgsCount)
    #expect(allSelected > OperationalOfflineMapPlanner.tileCount(
        bounds: bounds, minimumZoom: 12, maximumZoom: 13
    ))
}
