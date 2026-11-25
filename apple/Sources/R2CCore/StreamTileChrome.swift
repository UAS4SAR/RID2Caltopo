import Foundation

/// Everything a stream tile can draw besides the video (Ken, Nov 20: "too much text in the PiP").
/// The compact PiP over the map shows only `.safetyBadge`; full-size tiles show the rest as
/// before. Same list as Android's StreamTileChrome (StreamTile.kt).
public enum StreamTileChrome: CaseIterable, Sendable {
    case safetyNotice, safetyBadge, statusRow, steeringOverlay, centerpointOverlay,
         requesterLabel, alertOverlay, clueButton, legend, waitingPlaceholder

    public static let badgeText = "Observation only"

    public static func shows(_ chrome: StreamTileChrome, compactPip: Bool) -> Bool {
        compactPip ? chrome == .safetyBadge : chrome != .safetyBadge
    }

    /// Ken, Nov 25: a double tap on the primary/full stream opens the Desk Pod panel when
    /// BTRX is connected (same as a double tap on the map). It never fires on the compact
    /// PiP, and it no longer captures a clue — use the camera icon for that.
    public static func streamDoubleTapOpensDeskPod(compactPip: Bool, deskPodConnected: Bool) -> Bool {
        !compactPip && deskPodConnected
    }
}
