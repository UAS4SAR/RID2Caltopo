import Foundation

/// Public OSM/Esri basemaps are interactive-only; USGS/USFS government topo tiles may be cached offline.
public enum OperationalMapTilePolicy {
    public static let minimumCacheAgeDays = 7
    public static let openStreetMapCopyrightURL = URL(string: "https://www.openstreetmap.org/copyright")!
    /// Conservative default for OSM/Esri and other suppliers that misbehave under parallel fetch.
    public static let defaultFetchConcurrency = 1
    /// Bounded parallel fetch for USGS Scanned Topo and USFS Topo only (matches Android).
    public static let usgsHostedFetchConcurrency = 5
    private static let offlinePackageSources: Set<String> = [
        "USGS-Contours",
        "USGS-ScannedTopo",
        "USFS-Topo"
    ]

    public static func cacheAgeDays(_ configured: Int) -> Int {
        max(minimumCacheAgeDays, min(3_650, configured))
    }

    public static func allowsOfflineDownload(_ layer: OperationalMapBaseLayer) -> Bool {
        switch layer {
        case .usgsScannedTopo, .usfsTopo: true
        case .openStreetMap, .imagery: false
        }
    }

    public static func allowsPackageExport(source: String) -> Bool {
        offlinePackageSources.contains(source)
    }

    /// Live viewport fetch concurrency. MapKit already parallelizes; this documents the
    /// intended per-source policy and gates offline download workers.
    public static func liveFetchConcurrency(_ layer: OperationalMapBaseLayer) -> Int {
        switch layer {
        case .usgsScannedTopo, .usfsTopo: usgsHostedFetchConcurrency
        case .openStreetMap, .imagery: defaultFetchConcurrency
        }
    }

    public static func offlineFetchConcurrency(_ layer: OperationalMapBaseLayer) -> Int {
        liveFetchConcurrency(layer)
    }
}
