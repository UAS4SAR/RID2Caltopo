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
        boundaryIDs: [String]
    ) -> Self {
        var options = remembered ?? Self(
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
}
