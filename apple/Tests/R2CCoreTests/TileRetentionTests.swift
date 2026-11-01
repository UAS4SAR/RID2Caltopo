import Foundation
import Testing
@testable import R2CCore

@Test func restrictedWindowIsASingleThreeDayConstant() {
    #expect(OperationalTileRetention.restrictedRetentionDays == 3)
    #expect(OperationalTileRetention.restrictedRetentionSeconds == 3 * 24 * 60 * 60)
}

@Test func osmAndEsriNamesAreRestricted() {
    for name in [
        "OSM-Standard", "Mapnik", "MAPNIK", "openStreetMap", "OpenStreetMap", "osm",
        "ArcGIS-WorldImagery", "Imagery", "imagery", "Esri", "World_Imagery", "WorldImagery",
    ] {
        #expect(OperationalTileRetention.classifySource(name) == .restricted)
        #expect(OperationalTileRetention.classifyLocation("v1|\(name)|12|3|4") == .restricted)
    }
}

@Test func demAolAndPublicToposAreNeverPurged() {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    let ancient = now.addingTimeInterval(-3_000 * 86_400)
    let locations = [
        "v1|USGS-Topo|12|3|4",
        "v1|usgsTopo|8|1|2",
        "v1|USFS-Topo|10|4|5",
        "v1|usfsTopo|10|4|5",
        "v1|FSTopo|10|4|5",
        "v1|DEM|1|2|3",
        "v1|S1M|1|2|3",
        "v1|usgs-geotiff|1|2|3",
        "v1|AOL|1|2|3",
        "v1|lidar|1|2|3",
        "/storage/DEM/S1M_n0450e1510_20260821.tif",
        "/storage/MapTiles/surface_v1/tile-0-0.aol",
        "cache/dem/n45w122.tiff",
        "v1|custom-layer|4|5|6",
        "not-a-cache-key",
    ]
    for location in locations {
        #expect(OperationalTileRetention.classifyLocation(location) == .unrestricted)
        #expect(!OperationalTileRetention.shouldPurge(location: location, createdAt: ancient, now: now, standardMaxAgeDays: 365))
    }
}

@Test func osmAndEsriExpireAfterThreeDaysAndNotBefore() {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    let window = OperationalTileRetention.restrictedRetentionSeconds
    let key = "v1|ArcGIS-WorldImagery|12|1582|657"
    let legacy = "/var/MapTiles/openStreetMap/12/657/1582.png"
    #expect(!OperationalTileRetention.shouldPurge(
        location: key, createdAt: now.addingTimeInterval(-window + 1), now: now, standardMaxAgeDays: 365))
    #expect(OperationalTileRetention.shouldPurge(
        location: key, createdAt: now.addingTimeInterval(-window), now: now, standardMaxAgeDays: 365))
    #expect(OperationalTileRetention.shouldPurge(
        location: legacy, createdAt: now.addingTimeInterval(-4 * 86_400), now: now, standardMaxAgeDays: 365))
    #expect(OperationalTileRetention.shouldPurge(
        location: "v1|MAPNIK|1|0|0", createdAt: now.addingTimeInterval(-4 * 86_400), now: now, standardMaxAgeDays: 3_650))
}

@Test func missingAgeIsNotDeleted() {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    #expect(!OperationalTileRetention.shouldPurge(location: "v1|OSM-Standard|1|0|0", createdAt: nil, now: now, standardMaxAgeDays: 365))
    #expect(!OperationalTileRetention.shouldPurge(location: "v1|USGS-Contours|1|0|0", createdAt: nil, now: now, standardMaxAgeDays: 1))
    let cap = now.addingTimeInterval(OperationalTileRetention.restrictedRetentionSeconds)
    #expect(OperationalTileRetention.decision(
        location: "v1|Imagery|1|0|0", createdAt: nil, expiresAt: now.addingTimeInterval(365 * 86_400),
        now: now, standardMaxAgeDays: 365
    ) == .rewriteExpiry(cap))
}

@Test func youngRestrictedTilesKeepAThreeDayCap() {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    let created = now.addingTimeInterval(-86_400)
    let cap = created.addingTimeInterval(OperationalTileRetention.restrictedRetentionSeconds)
    #expect(OperationalTileRetention.decision(
        location: "v1|OSM-Standard|3|1|2", createdAt: created, expiresAt: created.addingTimeInterval(30 * 86_400),
        now: now, standardMaxAgeDays: 365
    ) == .rewriteExpiry(cap))
    #expect(OperationalTileRetention.decision(
        location: "v1|OSM-Standard|3|1|2", createdAt: created, expiresAt: cap,
        now: now, standardMaxAgeDays: 365
    ) == .keep)
}

@Test func contoursFollowTheOperatorAgeAndUnrestrictedExpiryIsCleared() {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    let contours = "v1|USGS-Contours|9|8|7"
    #expect(OperationalTileRetention.classifyLocation(contours) == .standard)
    #expect(!OperationalTileRetention.shouldPurge(
        location: contours, createdAt: now.addingTimeInterval(-4 * 86_400), now: now, standardMaxAgeDays: 365))
    #expect(OperationalTileRetention.shouldPurge(
        location: contours, createdAt: now.addingTimeInterval(-365 * 86_400), now: now, standardMaxAgeDays: 365))
    #expect(OperationalTileRetention.decision(
        location: "usgsTopo/12/1/2.jpg", createdAt: now.addingTimeInterval(-365 * 86_400),
        expiresAt: now.addingTimeInterval(86_400), now: now, standardMaxAgeDays: 365
    ) == .rewriteExpiry(.distantFuture))
    #expect(OperationalTileRetention.decision(
        location: "v1|USGS-Topo|1|0|0", createdAt: now.addingTimeInterval(-365 * 86_400),
        expiresAt: .distantFuture, now: now, standardMaxAgeDays: 365
    ) == .keep)
}

@Test func newWritesUseTheSourceWindow() {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    #expect(OperationalTileRetention.expiresAt(source: "OSM-Standard", now: now, standardMaxAgeDays: 365)
        == now.addingTimeInterval(OperationalTileRetention.restrictedRetentionSeconds))
    #expect(OperationalTileRetention.expiresAt(source: "ArcGIS-WorldImagery", now: now, standardMaxAgeDays: 365)
        == now.addingTimeInterval(OperationalTileRetention.restrictedRetentionSeconds))
    #expect(OperationalTileRetention.expiresAt(source: "USGS-Topo", now: now, standardMaxAgeDays: 365) == .distantFuture)
    #expect(OperationalTileRetention.expiresAt(source: "USFS-Topo", now: now, standardMaxAgeDays: 365) == .distantFuture)
    #expect(OperationalTileRetention.expiresAt(source: "S1M", now: now, standardMaxAgeDays: 365) == .distantFuture)
    #expect(OperationalTileRetention.expiresAt(source: "USGS-Contours", now: now, standardMaxAgeDays: 365)
        == now.addingTimeInterval(365 * 86_400))
}

@Test func newPublicTopoLayersHaveDocumentedEndpoints() {
    let usgs = OperationalMapBaseLayer.usgsTopo
    let usfs = OperationalMapBaseLayer.usfsTopo
    #expect(usgs.label == "USGS Topo")
    #expect(usfs.label == "USFS Topo")
    #expect(usgs.tileURL(zoom: 10, x: 197, y: 388)?.absoluteString
        == "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/10/388/197")
    #expect(usfs.tileURL(zoom: 10, x: 388, y: 197)?.absoluteString
        == "https://tiles.arcgis.com/tiles/gGHDlz6USftL5Pau/arcgis/rest/services/FSBasemap_20240617/VectorTileServer/tile/10/197/388.pbf")
    #expect(usgs.attribution.contains("USGS"))
    #expect(usfs.attribution.contains("Forest Service"))
    #expect(usgs.sourceMaximumZoom == 16)
    #expect(!OperationalMapTilePolicy.allowsOfflineDownload(usgs))
    #expect(!OperationalMapTilePolicy.allowsOfflineDownload(usfs))
    #expect(OperationalTileRetention.classifySource(usgs.cacheKey) == .unrestricted)
    #expect(OperationalTileRetention.classifySource(usfs.cacheKey) == .unrestricted)
}
