import Foundation

/// Resolves own-ship (streamed) altitude for compliance alerts when BLE Remote ID
/// is stale but DJI SEI is still arriving. Never used for proximity between
/// aircraft — proximity continues to use RID/BLE positions only.
public enum OperationalOwnShipAltitudePreference: Sendable {
    public static let metersToFeet = 3.28084

    public struct Sample: Equatable, Sendable {
        public let aglFeet: Double
        public let telemetryAt: Date
        /// True when the sample came from SEI relative-up because RID AGL was stale or missing.
        public let usedSEI: Bool

        public init(aglFeet: Double, telemetryAt: Date, usedSEI: Bool) {
            self.aglFeet = aglFeet
            self.telemetryAt = telemetryAt
            self.usedSEI = usedSEI
        }
    }

    /// Prefer a fresh RID/display AGL. If that sample is outside the freshness
    /// window (or missing), fall back to SEI relative-up for the paired stream.
    public static func resolve(
        ridAglFeet: Double?,
        ridTelemetryAt: Date?,
        seiRelativeUpMeters: Double?,
        seiTelemetryAt: Date?,
        now: Date,
        maximumAgeSeconds: TimeInterval
    ) -> Sample? {
        if let agl = ridAglFeet, agl.isFinite,
           let ridAt = ridTelemetryAt,
           RidAlertPositionFreshness.isFresh(sampleAt: ridAt, now: now, maximumAgeSeconds: maximumAgeSeconds) {
            return Sample(aglFeet: agl, telemetryAt: ridAt, usedSEI: false)
        }
        if let up = seiRelativeUpMeters, up.isFinite, up >= -50, up <= 30_000,
           let seiAt = seiTelemetryAt,
           RidAlertPositionFreshness.isFresh(sampleAt: seiAt, now: now, maximumAgeSeconds: maximumAgeSeconds) {
            return Sample(aglFeet: max(0, up * metersToFeet), telemetryAt: seiAt, usedSEI: true)
        }
        return nil
    }
}
