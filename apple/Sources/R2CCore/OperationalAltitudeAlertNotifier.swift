import Foundation

/// One aircraft that is a candidate for the altitude advisory. Mirrors Android's
/// `ComplianceAlertCandidate` (ProximityAlertHost.kt).
public struct OperationalAltitudeAlertCandidate: Sendable, Equatable {
    public let remoteID: String
    public let aglFeet: Double
    public let thresholdFeet: Double
    public let telemetryAt: Date
    /// Freshness window used when this candidate was built (5 s foreground / 15 s background).
    public let maximumAgeSeconds: TimeInterval

    public init(
        remoteID: String,
        aglFeet: Double,
        thresholdFeet: Double = OperationalAltitudeAlertNotifier.limitFeet,
        telemetryAt: Date,
        maximumAgeSeconds: TimeInterval = OperationalAltitudeAlertNotifier.maximumSampleAge
    ) {
        self.remoteID = remoteID
        self.aglFeet = aglFeet
        self.thresholdFeet = thresholdFeet
        self.telemetryAt = telemetryAt
        self.maximumAgeSeconds = maximumAgeSeconds
    }
}

/// Port of Android's `ComplianceAlertCenter.updateCandidates`.
///
/// Android passes only aircraft at or above the 200 ft AGL limit into this
/// notifier (StreamsViewModel filters on `COMPLIANCE_ALERT_AGL_LIMIT_FT`), so the
/// 90% (180 ft) "near" tier is retained here for parity but is not reached by the
/// production candidate list on either platform. At 180 ft both platforms only
/// change the map marker colour.
public struct OperationalAltitudeAlertNotifier: Sendable {
    public static let limitFeet: Double = 200
    public static let nearLimitRatio: Double = 0.90
    public static let nearRepeatInterval: TimeInterval = 30
    public static let overRepeatInterval: TimeInterval = 15
    /// Foreground default (Android `MAX_ALTITUDE_SAMPLE_AGE_MS`). Background uses
    /// `RidAlertPositionFreshness.backgroundMaximumAgeSeconds` via `isFreshSample`.
    public static let maximumSampleAge: TimeInterval = RidAlertPositionFreshness.foregroundMaximumAgeSeconds
    /// Android `SpokenWarningKind.Altitude.phrase`.
    public static let spokenPhrase = OperationalSpokenWarningKind.altitude.phrase
    /// Android `ComplianceAlertHost` requests speech with a 15 s per-aircraft cooldown.
    public static let spokenCooldown: TimeInterval = 15

    public struct Decision: Sendable, Equatable {
        public let remoteID: String
        public let aglFeet: Double
        public let severity: OperationalAltitudeSeverity
        /// True when Android would create a new alert instance (speech, vibration, toast).
        public let shouldNotify: Bool
    }

    private var lastSeverity: OperationalAltitudeSeverity = .normal
    private var lastNotifiedAt: Date?

    public init() {}

    public static func isFreshSample(
        telemetryAt: Date,
        now: Date,
        maximumAgeSeconds: TimeInterval = maximumSampleAge
    ) -> Bool {
        RidAlertPositionFreshness.isFresh(
            sampleAt: telemetryAt,
            now: now,
            maximumAgeSeconds: maximumAgeSeconds
        )
    }

    public static func severity(aglFeet: Double, thresholdFeet: Double) -> OperationalAltitudeSeverity {
        guard aglFeet.isFinite, thresholdFeet > 0 else { return .normal }
        if aglFeet >= thresholdFeet { return .overLimit }
        if aglFeet >= thresholdFeet * nearLimitRatio { return .caution }
        return .normal
    }

    public mutating func update(candidates: [OperationalAltitudeAlertCandidate], now: Date) -> Decision? {
        let best = candidates
            .filter {
                $0.aglFeet.isFinite && $0.thresholdFeet > 0
                    && Self.isFreshSample(
                        telemetryAt: $0.telemetryAt,
                        now: now,
                        maximumAgeSeconds: $0.maximumAgeSeconds
                    )
            }
            .compactMap { candidate -> (OperationalAltitudeAlertCandidate, OperationalAltitudeSeverity)? in
                let severity = Self.severity(aglFeet: candidate.aglFeet, thresholdFeet: candidate.thresholdFeet)
                return severity == .normal ? nil : (candidate, severity)
            }
            .max { lhs, rhs in
                lhs.1 != rhs.1 ? lhs.1 < rhs.1 : lhs.0.aglFeet < rhs.0.aglFeet
            }
        guard let (candidate, severity) = best else {
            lastSeverity = .normal
            lastNotifiedAt = nil
            return nil
        }
        let interval = severity == .overLimit ? Self.overRepeatInterval : Self.nearRepeatInterval
        let shouldNotify = severity != lastSeverity
            || lastNotifiedAt.map { now.timeIntervalSince($0) >= interval } ?? true
        lastSeverity = severity
        if shouldNotify { lastNotifiedAt = now }
        return Decision(remoteID: candidate.remoteID, aglFeet: candidate.aglFeet, severity: severity, shouldNotify: shouldNotify)
    }
}
