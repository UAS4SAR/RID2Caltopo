import Foundation

/// Public OSM/Esri basemaps are interactive-only; USGS/USFS government topo tiles may be cached offline.
public enum OperationalMapTilePolicy {
    public static let minimumCacheAgeDays = 7
    public static let openStreetMapCopyrightURL = URL(string: "https://www.openstreetmap.org/copyright")!
    /// Conservative default for OSM/Esri and other suppliers that misbehave under parallel fetch.
    public static let defaultFetchConcurrency = 1
    /// Bounded parallel fetch for USGS Scanned Topo and USFS Topo only (matches Android).
    public static let usgsHostedFetchConcurrency = 5
    /// Background pool covers both parallel USGS/USFS sources plus an optional contours worker.
    public static let offlineFetchThreadPool = usgsHostedFetchConcurrency * 2 + 1

    public static func cacheAgeDays(_ configured: Int) -> Int {
        max(minimumCacheAgeDays, min(3_650, configured))
    }

    public static func allowsOfflineDownload(_ layer: OperationalMapBaseLayer) -> Bool {
        OperationalMapTileSourceRegistry.descriptor(id: layer.packageId)?.offlineAllowed ?? false
    }

    public static func allowsPackageExport(source: String) -> Bool {
        OperationalMapTileSourceRegistry.offlineAllowedSources.contains { $0.id == source }
    }

    /// Live viewport fetch concurrency. MapKit already parallelizes; this documents the
    /// intended per-source policy and gates offline download workers.
    public static func liveFetchConcurrency(_ layer: OperationalMapBaseLayer) -> Int {
        OperationalMapTileSourceRegistry.descriptor(id: layer.packageId)?.fetchConcurrency
            ?? defaultFetchConcurrency
    }

    public static func offlineFetchConcurrency(_ layer: OperationalMapBaseLayer) -> Int {
        liveFetchConcurrency(layer)
    }

    public static func offlineFetchConcurrency(sourceId: String) -> Int {
        OperationalMapTileSourceRegistry.descriptor(id: sourceId)?.fetchConcurrency
            ?? defaultFetchConcurrency
    }
}
