import Foundation

/// The window that shows BTRX telemetry: the map (bearing layer) or the stream (steering overlay).
public enum BtrxTelemetryWindow: String, Sendable, Equatable {
    case map, stream
}

/// Where BTRX telemetry is drawn (Ken, Nov 23): always shown while BTRX is enabled, with no
/// on/off, in the primary window only: the bearing layer when the map is primary, the steering
/// overlay when the stream is. Full-screen map or video (with or without the other as a
/// picture-in-picture inset) is primary; in a split (side by side or top and bottom) the pane
/// with the larger on-screen area is. The compact stream PiP never shows telemetry, and an
/// inset map draws none either (the stream is primary then).
///
/// Near 50/50 the choice holds (`hysteresis`): the other pane has to reach 55 % of the two
/// panes' area to take over, so dragging the divider about the middle does not flicker. An exact
/// tie with nothing to hold (`tie`) goes to the map. Same as Android's BtrxTelemetryRoute;
/// cases in test-fixtures/btrx/telemetry-route.tsv.
public enum BtrxTelemetryRoute {
    /// Share past 50 % the other pane needs before telemetry moves to it.
    public static let hysteresis = 0.05
    /// An exact tie with no current window: the map.
    public static let tie = BtrxTelemetryWindow.map

    /// Areas in any unit (0 = pane not shown); `previous` = the window telemetry is on now, if any.
    public static func primary(mapArea: Double, streamArea: Double, previous: BtrxTelemetryWindow?) -> BtrxTelemetryWindow {
        let m = max(mapArea, 0), s = max(streamArea, 0)
        if s <= 0 { return .map }
        if m <= 0 { return .stream }
        let share = s / (m + s)
        if share >= 0.5 + hysteresis - 1e-9 { return .stream }
        if share <= 0.5 - hysteresis + 1e-9 { return .map }
        if let previous { return previous }
        if share > 0.5 { return .stream }
        if share < 0.5 { return .map }
        return tie
    }

    /// One-dimensional split: `streamFraction` of the width (side by side) or height (top and bottom) is the video pane.
    public static func forStreamFraction(_ streamFraction: Double, previous: BtrxTelemetryWindow?) -> BtrxTelemetryWindow {
        let f = min(max(streamFraction, 0), 1)
        return primary(mapArea: 1 - f, streamArea: f, previous: previous)
    }

    /// On-screen areas (shares of the live view) of the map and video panes in the iPad's
    /// layouts: full map (with or without a video inset) and full video (with or without a map
    /// inset) are the whole view (an inset never counts and never shows telemetry); `.split`
    /// goes by `videoFraction` (the video pane's share of the width side by side, or of the
    /// height top and bottom). Same as Android's btrxPaneAreas.
    public static func paneAreas(_ layout: OperationalMapVideoLayout, videoFraction: Double) -> (map: Double, stream: Double) {
        switch layout {
        case .map, .mapPrimary: return (1, 0)
        case .video, .videoPrimary: return (0, 1)
        case .split:
            let f = min(max(videoFraction, 0), 1)
            return (1 - f, f)
        }
    }

    public static func forLayout(_ layout: OperationalMapVideoLayout, videoFraction: Double,
                                 previous: BtrxTelemetryWindow?) -> BtrxTelemetryWindow {
        let a = paneAreas(layout, videoFraction: videoFraction)
        return primary(mapArea: a.map, streamArea: a.stream, previous: previous)
    }

    /// The map draws the bearing layer, its chips and popups: the engine runs and the map is primary.
    public static func mapShows(bearingOn: Bool, window: BtrxTelemetryWindow) -> Bool { bearingOn && window == .map }

    /// The stream tile may show the steering card: the stream is primary.
    public static func streamShows(_ window: BtrxTelemetryWindow) -> Bool { window == .stream }

    public static func windowName(_ w: BtrxTelemetryWindow) -> String { w == .map ? "Map" : "Stream" }

    /// Read-only line in the Desk Pod view (double tap on the map); there is no toggle.
    public static func shownOnLine(_ w: BtrxTelemetryWindow) -> String { "Telemetry shown on: \(windowName(w)) (primary view)" }

    public static let hint = "BTRX telemetry is always shown while BTRX is enabled: the bearing layer on the map when the map is the primary view, the steering overlay on the stream when the stream is. In a split the larger pane is primary (the map on an even split); the picture-in-picture inset never shows it."
}
