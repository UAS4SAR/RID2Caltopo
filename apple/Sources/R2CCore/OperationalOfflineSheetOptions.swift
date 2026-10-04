import Foundation

/// Download Map choices kept by the offline manager for the app session, mirroring Android's
/// AndroidMapOfflinePrepCoordinator (preset, layers, DEM, AOL, DEM resolution, area, contours).
public struct OperationalOfflineSheetOptions: Sendable, Equatable {
    public var preset: OperationalOfflinePreset
    public var includeImagery: Bool
    public var includeOSM: Bool
    public var includeContours: Bool
    public var includeDEM: Bool
    public var includeAOL: Bool
    public var demResolution: OperationalDEMResolution
    /// Empty means "Current Visible Map".
    public var selectedBoundaryID: String
    /// Visible-map snapshot the selection uses when no boundary is chosen.
    public var viewportBounds: OperationalMapBounds

    public init(
        preset: OperationalOfflinePreset = .operations,
        includeImagery: Bool,
        includeOSM: Bool,
        includeContours: Bool,
        includeDEM: Bool = true,
        includeAOL: Bool = false,
        demResolution: OperationalDEMResolution = .maximum1m,
        selectedBoundaryID: String = "",
        viewportBounds: OperationalMapBounds
    ) {
        self.preset = preset
        self.includeImagery = includeImagery
        self.includeOSM = includeOSM
        self.includeContours = includeContours
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
    /// - First open of the session: Medium detail, the current base layer, DEM 1 m, no AOL, visible map.
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
            includeImagery: baseLayer == .imagery,
            includeOSM: baseLayer == .openStreetMap,
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
        return options
    }
}
