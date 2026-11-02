import Foundation

/// Public OSM/Esri basemaps are interactive-only; USGS/USFS government topo tiles may be cached offline.
public enum OperationalMapTilePolicy {
    public static let minimumCacheAgeDays = 7
    public static let openStreetMapCopyrightURL = URL(string: "https://www.openstreetmap.org/copyright")!
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
}
