import Foundation

/// Per-source zoom range chosen on Download Map, clamped to the supplier's native range.
public struct OperationalOfflineSourceZoom: Sendable, Equatable {
    public var minZoom: Int
    public var maxZoom: Int

    public init(minZoom: Int = 12, maxZoom: Int = 16) {
        self.minZoom = minZoom
        self.maxZoom = maxZoom
    }

    public func clamped(to sourceId: String) -> OperationalOfflineSourceZoom {
        guard let range = OperationalMapTileSourceRegistry.clampZoomRange(
            sourceId: sourceId, minZoom: minZoom, maxZoom: maxZoom
        ) else {
            return OperationalOfflineSourceZoom(minZoom: minZoom, maxZoom: max(minZoom, maxZoom))
        }
        return OperationalOfflineSourceZoom(minZoom: range.lowerBound, maxZoom: range.upperBound)
    }
}

/// Download Map choices kept by the offline manager for the app session, mirroring Android's
/// AndroidMapOfflinePrepCoordinator (layers, zooms, DEM, AOL, DEM resolution, area, contours).
public struct OperationalOfflineSheetOptions: Sendable, Equatable {
    public var includeUsgsScannedTopo: Bool
    public var includeUsfsTopo: Bool
    public var includeContours: Bool
    public var usgsScannedTopoZoom: OperationalOfflineSourceZoom
    public var usfsTopoZoom: OperationalOfflineSourceZoom
    public var contoursZoom: OperationalOfflineSourceZoom
    public var includeDEM: Bool
    public var includeAOL: Bool
    public var demResolution: OperationalDEMResolution
    /// Empty means "Current Visible Map".
    public var selectedBoundaryID: String
    /// Visible-map snapshot the selection uses when no boundary is chosen.
    public var viewportBounds: OperationalMapBounds

    public init(
        includeUsgsScannedTopo: Bool = false,
        includeUsfsTopo: Bool = false,
        includeContours: Bool,
        usgsScannedTopoZoom: OperationalOfflineSourceZoom = OperationalOfflineSourceZoom(),
        usfsTopoZoom: OperationalOfflineSourceZoom = OperationalOfflineSourceZoom(),
        contoursZoom: OperationalOfflineSourceZoom = OperationalOfflineSourceZoom(),
        includeDEM: Bool = true,
        includeAOL: Bool = false,
        demResolution: OperationalDEMResolution = .maximum1m,
        selectedBoundaryID: String = "",
        viewportBounds: OperationalMapBounds
    ) {
        self.includeUsgsScannedTopo = includeUsgsScannedTopo
        self.includeUsfsTopo = includeUsfsTopo
        self.includeContours = includeContours
        self.usgsScannedTopoZoom = usgsScannedTopoZoom.clamped(to: "USGS-ScannedTopo")
        self.usfsTopoZoom = usfsTopoZoom.clamped(to: "USFS-Topo")
        self.contoursZoom = contoursZoom.clamped(to: "USGS-Contours")
        self.includeDEM = includeDEM
        self.includeAOL = includeAOL
        self.demResolution = demResolution
        self.selectedBoundaryID = selectedBoundaryID
        self.viewportBounds = viewportBounds
    }

    /// Choices to show when Download Map opens. Matches Android:
    /// - During a download, everything that download started with (area, layers, contours, AOL), so a
    ///   hidden-and-reopened sheet shows the same selection and Retry after a failure keeps AOL.
    /// - Otherwise the last-used choices stay, except contours follow the map's contour overlay and the
    ///   visible map is captured afresh (Android onDownloadMap resets exactly those).
    /// - First open: current offline-allowed base layer ticked when applicable, DEM 1 m, no AOL, visible map.
    /// A boundary that no longer exists falls back to the visible map.
    public static func forOpening(
        remembered: Self?,
        downloadRunning: Bool,
        viewportBounds: OperationalMapBounds,
        contoursOverlayOn: Bool,
        baseLayer: OperationalMapBaseLayer,
        boundaryIDs: [String],
        defaults: UserDefaults = .standard
    ) -> Self {
        var options = remembered
            ?? Self.loadPersisted(viewportBounds: viewportBounds, defaults: defaults)
            ?? Self(
                includeUsgsScannedTopo: baseLayer == .usgsScannedTopo,
                includeUsfsTopo: baseLayer == .usfsTopo,
                includeContours: contoursOverlayOn,
                viewportBounds: viewportBounds
            )
        if remembered == nil || !downloadRunning {
            options.includeContours = contoursOverlayOn
            options.viewportBounds = viewportBounds
        }
        if !options.selectedBoundaryID.isEmpty && !boundaryIDs.contains(options.selectedBoundaryID) {
            options.selectedBoundaryID = ""
        }
        options.usgsScannedTopoZoom = options.usgsScannedTopoZoom.clamped(to: "USGS-ScannedTopo")
        options.usfsTopoZoom = options.usfsTopoZoom.clamped(to: "USFS-Topo")
        options.contoursZoom = options.contoursZoom.clamped(to: "USGS-Contours")
        return options
    }

    public var selectedOfflineBaseLayers: [OperationalMapBaseLayer] {
        var layers: [OperationalMapBaseLayer] = []
        if includeUsgsScannedTopo { layers.append(.usgsScannedTopo) }
        if includeUsfsTopo { layers.append(.usfsTopo) }
        return layers
    }

    private static let defaultsPrefix = "offlinePrep."

    /// Persist source selection and zoom ranges across app restarts (clamped on write).
    public func persist(defaults: UserDefaults = .standard) {
        let usgs = usgsScannedTopoZoom.clamped(to: "USGS-ScannedTopo")
        let usfs = usfsTopoZoom.clamped(to: "USFS-Topo")
        let contours = contoursZoom.clamped(to: "USGS-Contours")
        defaults.set(includeUsgsScannedTopo, forKey: Self.defaultsPrefix + "usgs")
        defaults.set(includeUsfsTopo, forKey: Self.defaultsPrefix + "usfs")
        defaults.set(includeContours, forKey: Self.defaultsPrefix + "contours")
        defaults.set(true, forKey: Self.defaultsPrefix + "initialized")
        defaults.set(usgs.minZoom, forKey: Self.defaultsPrefix + "usgsMin")
        defaults.set(usgs.maxZoom, forKey: Self.defaultsPrefix + "usgsMax")
        defaults.set(usfs.minZoom, forKey: Self.defaultsPrefix + "usfsMin")
        defaults.set(usfs.maxZoom, forKey: Self.defaultsPrefix + "usfsMax")
        defaults.set(contours.minZoom, forKey: Self.defaultsPrefix + "contoursMin")
        defaults.set(contours.maxZoom, forKey: Self.defaultsPrefix + "contoursMax")
    }

    /// Load persisted source selection / zooms (clamped on read). Nil when never saved.
    public static func loadPersisted(
        viewportBounds: OperationalMapBounds,
        defaults: UserDefaults = .standard
    ) -> Self? {
        guard defaults.object(forKey: defaultsPrefix + "initialized") != nil else { return nil }
        return Self(
            includeUsgsScannedTopo: defaults.bool(forKey: defaultsPrefix + "usgs"),
            includeUsfsTopo: defaults.bool(forKey: defaultsPrefix + "usfs"),
            includeContours: defaults.bool(forKey: defaultsPrefix + "contours"),
            usgsScannedTopoZoom: OperationalOfflineSourceZoom(
                minZoom: defaults.object(forKey: defaultsPrefix + "usgsMin") as? Int ?? 12,
                maxZoom: defaults.object(forKey: defaultsPrefix + "usgsMax") as? Int ?? 16
            ),
            usfsTopoZoom: OperationalOfflineSourceZoom(
                minZoom: defaults.object(forKey: defaultsPrefix + "usfsMin") as? Int ?? 12,
                maxZoom: defaults.object(forKey: defaultsPrefix + "usfsMax") as? Int ?? 16
            ),
            contoursZoom: OperationalOfflineSourceZoom(
                minZoom: defaults.object(forKey: defaultsPrefix + "contoursMin") as? Int ?? 12,
                maxZoom: defaults.object(forKey: defaultsPrefix + "contoursMax") as? Int ?? 16
            ),
            viewportBounds: viewportBounds
        )
    }
}
