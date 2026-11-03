import Foundation

/// Single source of truth for map tile suppliers on Apple.
/// Base-layer picker, Download Map checkboxes, offline/export policy, and fetch concurrency
/// all derive from these descriptors. IDs must stay in sync with Android's MapTileSourceRegistry.
public struct OperationalMapTileSourceDescriptor: Sendable, Equatable, Identifiable {
    /// Cross-platform package / Android tile-source name (e.g. "USGS-ScannedTopo").
    public let id: String
    public let label: String
    public let host: String
    public let format: String
    public let minZoom: Int
    public let maxZoom: Int
    public let offlineAllowed: Bool
    public let attribution: String
    public let fetchConcurrency: Int
    /// Apple on-disk folder key under MapTiles/.
    public let cacheKey: String
    public let legacyCacheNames: [String]

    public init(
        id: String,
        label: String,
        host: String,
        format: String,
        minZoom: Int,
        maxZoom: Int,
        offlineAllowed: Bool,
        attribution: String,
        fetchConcurrency: Int,
        cacheKey: String,
        legacyCacheNames: [String] = []
    ) {
        self.id = id
        self.label = label
        self.host = host
        self.format = format
        self.minZoom = minZoom
        self.maxZoom = maxZoom
        self.offlineAllowed = offlineAllowed
        self.attribution = attribution
        self.fetchConcurrency = fetchConcurrency
        self.cacheKey = cacheKey
        self.legacyCacheNames = legacyCacheNames
    }
}

public enum OperationalMapTileSourceRegistry {
    public static let all: [OperationalMapTileSourceDescriptor] = [
        .init(
            id: "OSM-Standard",
            label: "OpenStreetMap",
            host: "tile.openstreetmap.org",
            format: "png",
            minZoom: 0,
            maxZoom: 19,
            offlineAllowed: false,
            attribution: "© OpenStreetMap contributors",
            fetchConcurrency: OperationalMapTilePolicy.defaultFetchConcurrency,
            cacheKey: "openStreetMap",
            legacyCacheNames: ["Mapnik", "MAPNIK"]
        ),
        .init(
            id: "ArcGIS-WorldImagery",
            label: "Imagery",
            host: "services.arcgisonline.com",
            format: "jpg",
            minZoom: 0,
            maxZoom: 19,
            offlineAllowed: false,
            attribution: "Tiles © Esri",
            fetchConcurrency: OperationalMapTilePolicy.defaultFetchConcurrency,
            cacheKey: "imagery"
        ),
        .init(
            id: "USGS-ScannedTopo",
            label: "USGS Scanned Topo",
            host: "historical1.arcgis.com",
            format: "png",
            minZoom: 0,
            maxZoom: 16,
            offlineAllowed: true,
            attribution: "USGS Historical Topographic Map Collection",
            fetchConcurrency: OperationalMapTilePolicy.usgsHostedFetchConcurrency,
            cacheKey: "usgsScannedTopo"
        ),
        .init(
            id: "USFS-Topo",
            label: "USFS Topo",
            host: "basemap.nationalmap.gov",
            format: "jpg",
            minZoom: 0,
            maxZoom: 16,
            offlineAllowed: true,
            attribution: "USGS The National Map (USGSTopo)",
            fetchConcurrency: OperationalMapTilePolicy.usgsHostedFetchConcurrency,
            cacheKey: "usfsTopo",
            legacyCacheNames: ["USGS-Topo"]
        ),
        .init(
            id: "USGS-Contours",
            label: "Contours",
            host: "carto.nationalmap.gov",
            format: "png",
            minZoom: 0,
            maxZoom: 22,
            offlineAllowed: true,
            attribution: "Contours: USGS",
            fetchConcurrency: OperationalMapTilePolicy.defaultFetchConcurrency,
            cacheKey: "usgsContours"
        ),
    ]

    public static let byId: [String: OperationalMapTileSourceDescriptor] =
        Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })

    /// Offline-allowed base map sources shown as Download Map checkboxes (not Contours).
    public static var offlineAllowedBaseSources: [OperationalMapTileSourceDescriptor] {
        all.filter { $0.offlineAllowed && $0.id != "USGS-Contours" }
    }

    public static var offlineAllowedSources: [OperationalMapTileSourceDescriptor] {
        all.filter(\.offlineAllowed)
    }

    public static func descriptor(id: String) -> OperationalMapTileSourceDescriptor? { byId[id] }

    public static func descriptor(cacheKey: String) -> OperationalMapTileSourceDescriptor? {
        all.first { $0.cacheKey == cacheKey }
    }

    public static func clampZoomRange(sourceId: String, minZoom: Int, maxZoom: Int) -> ClosedRange<Int>? {
        guard let source = byId[sourceId] else { return nil }
        let lo = min(source.maxZoom, max(source.minZoom, minZoom))
        let hi = min(source.maxZoom, max(source.minZoom, maxZoom))
        guard lo <= hi else { return nil }
        return lo...hi
    }

    /// Canonical IDs shared with Android MapTileSourceRegistry (sorted).
    public static var crossPlatformIds: [String] { all.map(\.id).sorted() }
}
