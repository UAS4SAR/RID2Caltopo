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
}
