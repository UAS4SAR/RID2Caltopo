import Foundation

/// Position / altitude sample age limits for spoken alerts.
///
/// Foreground keeps the historical 5 s window (matches Android
/// `ProximityTelemetry.MAX_POSITION_AGE_MS` / altitude sample age). While the
/// iPad display is locked, iOS coalesces BLE advertisements so RID arrives far
/// more slowly; a 15 s window keeps proximity and altitude advisories usable
/// without inventing positions. Android keeps a dense foreground-service scan
/// and does not need this background widening.
public enum RidAlertPositionFreshness: Sendable {
    public static let foregroundMaximumAgeSeconds: TimeInterval = 5
    /// Chosen at the top of the approved 12–15 s range so sparse locked-screen
    /// BLE (~0.1–0.3 obs/s in field logs) still counts as fresh for alerts.
    public static let backgroundMaximumAgeSeconds: TimeInterval = 15

    public static func maximumAgeSeconds(inBackground: Bool) -> TimeInterval {
        inBackground ? backgroundMaximumAgeSeconds : foregroundMaximumAgeSeconds
    }

    public static func isFresh(sampleAt: Date, now: Date, maximumAgeSeconds: TimeInterval) -> Bool {
        now >= sampleAt && now.timeIntervalSince(sampleAt) <= maximumAgeSeconds
    }
}
