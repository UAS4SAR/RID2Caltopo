import Foundation

/// Public basemaps are interactive-only; offline/export grants must be explicit.
public enum OperationalMapTilePolicy {
    public static let minimumCacheAgeDays = 7
    public static let openStreetMapCopyrightURL = URL(string: "https://www.openstreetmap.org/copyright")!

    public static func cacheAgeDays(_ configured: Int) -> Int {
        max(minimumCacheAgeDays, min(3_650, configured))
    }

    public static func allowsOfflineDownload(_ layer: OperationalMapBaseLayer) -> Bool { false }

    public static func allowsPackageExport(source: String) -> Bool {
        source == "USGS-Contours"
    }
}
