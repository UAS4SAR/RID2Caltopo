import Foundation

/// Alert kinds shown in the session alert-bell panel. Display names match the
/// operator-facing labels; speech phrases stay in `OperationalSpokenWarningKind`
/// / Android `SpokenWarningKind` where those already exist.
public enum AlertBellKind: String, CaseIterable, Sendable, Identifiable {
    case proximity
    case altitude
    case distance
    case droneSignalLoss
    case bridgeSignalLoss
    case wifiStrength
    case videoRequest

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .proximity: return "Proximity"
        case .altitude: return "Altitude"
        case .distance: return "Distance"
        case .droneSignalLoss: return "Drone signal loss"
        case .bridgeSignalLoss: return "Bridge signal loss"
        case .wifiStrength: return "WiFi strength"
        case .videoRequest: return "Video request"
        }
    }
}

/// Visual severity for the top-bar and per-row alert bells.
public enum AlertBellColor: Int, Sendable, Comparable {
    case white = 0
    case orange = 1
    case red = 2

    public static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }

    public static func worst(_ colors: AlertBellColor...) -> AlertBellColor {
        colors.max() ?? .white
    }
}

/// Pure 80%-approach / active-threshold rules for the alert bell.
///
/// Semantics (documented for operators and tests):
/// - **Altitude**: orange at ≥ 80% of the AGL limit (160 ft of 200); red at ≥ limit.
///   Map-marker “near” colour remains 90% / 180 ft elsewhere; this 80% rule is
///   only for the bell indicator.
/// - **Distance**: orange at ≥ 80% of the 1-mile (5280 ft) range limit; red at ≥ limit.
/// - **Proximity**: red while an alert is active (inside the configured minimum
///   separation). Orange while horizontal separation is within 1.25× the minimum
///   (i.e. 1 / 0.8) but not yet actively alerting. White farther out.
/// - **WiFi strength**: red below the weak threshold (60%). Orange below
///   threshold / 0.8 (75%) but still at or above 60%. White at ≥ 75% or unknown.
/// - **Bridge signal loss**: red when monitoring and last ping age ≥ loss
///   threshold (32 s). Orange at ≥ 80% of that threshold. White otherwise.
/// - **Drone signal loss / Video request**: red while the existing active
///   condition is true; no separate approaching tier (white otherwise).
public enum AlertBellThresholdPolicy {
    public static let approachRatio = 0.80
    /// Separation at or below `threshold × this` is “approaching” for proximity.
    public static let proximityApproachMultiplier = 1.0 / approachRatio
    public static let altitudeLimitFeet = 200.0
    public static let distanceLimitFeet = 5280.0
    public static let wifiWeakPercent = 60
    public static let bridgeLossSeconds = 32.0

    public static func altitudeColor(
        aglFeet: Double?,
        limitFeet: Double = altitudeLimitFeet
    ) -> AlertBellColor {
        guard let aglFeet, aglFeet.isFinite, limitFeet > 0 else { return .white }
        if aglFeet >= limitFeet { return .red }
        if aglFeet >= limitFeet * approachRatio { return .orange }
        return .white
    }

    public static func distanceColor(
        rangeFeet: Double?,
        limitFeet: Double = distanceLimitFeet
    ) -> AlertBellColor {
        guard let rangeFeet, rangeFeet.isFinite, limitFeet > 0 else { return .white }
        if rangeFeet >= limitFeet { return .red }
        if rangeFeet >= limitFeet * approachRatio { return .orange }
        return .white
    }

    public static func proximityColor(
        separationFeet: Double?,
        thresholdFeet: Double,
        isActivelyAlerting: Bool
    ) -> AlertBellColor {
        if isActivelyAlerting { return .red }
        guard let separationFeet, separationFeet.isFinite, thresholdFeet > 0 else { return .white }
        if separationFeet <= thresholdFeet { return .red }
        if separationFeet <= thresholdFeet * proximityApproachMultiplier { return .orange }
        return .white
    }

    public static func wifiColor(
        signalPercent: Int?,
        weakThresholdPercent: Int = wifiWeakPercent
    ) -> AlertBellColor {
        guard let signalPercent else { return .white }
        let clamped = min(100, max(0, signalPercent))
        if clamped < weakThresholdPercent { return .red }
        let approachCeiling = Int((Double(weakThresholdPercent) / approachRatio).rounded(.up))
        if clamped < approachCeiling { return .orange }
        return .white
    }

    public static func bridgeColor(
        secondsSinceLastPing: Double?,
        monitoringActive: Bool,
        lossThresholdSeconds: Double = bridgeLossSeconds
    ) -> AlertBellColor {
        guard monitoringActive, lossThresholdSeconds > 0 else { return .white }
        let age = secondsSinceLastPing ?? .greatestFiniteMagnitude
        if age >= lossThresholdSeconds { return .red }
        if age >= lossThresholdSeconds * approachRatio { return .orange }
        return .white
    }

    public static func droneSignalLossColor(isAlerting: Bool) -> AlertBellColor {
        isAlerting ? .red : .white
    }

    public static func videoColor(pendingRequest: Bool) -> AlertBellColor {
        pendingRequest ? .red : .white
    }

    public static func aggregate(_ colors: [AlertBellKind: AlertBellColor]) -> AlertBellColor {
        colors.values.max() ?? .white
    }
}

/// Session mute + latched “bell visible” state. Pure data; hosts own persistence
/// policy (in-memory only — mutes do not survive process restart).
public struct AlertBellSessionState: Sendable, Equatable {
    public var muted: Set<AlertBellKind>
    /// Latched true after a real alarm fires (speech requested via noteAlarmFired).
    /// Ambient red/orange metrics alone must not show the bell at app start.
    public var hasEverAlarmed: Bool
    public var colors: [AlertBellKind: AlertBellColor]

    public init(
        muted: Set<AlertBellKind> = [],
        hasEverAlarmed: Bool = false,
        colors: [AlertBellKind: AlertBellColor] = Dictionary(
            uniqueKeysWithValues: AlertBellKind.allCases.map { ($0, .white) }
        )
    ) {
        self.muted = muted
        self.hasEverAlarmed = hasEverAlarmed
        self.colors = colors
    }

    public var showBell: Bool { hasEverAlarmed }
    public var aggregateColor: AlertBellColor { AlertBellThresholdPolicy.aggregate(colors) }

    public func isMuted(_ kind: AlertBellKind) -> Bool { muted.contains(kind) }

    public mutating func setMuted(_ kind: AlertBellKind, muted: Bool) {
        if muted { self.muted.insert(kind) } else { self.muted.remove(kind) }
    }

    public mutating func updateColors(_ newColors: [AlertBellKind: AlertBellColor]) {
        colors = newColors
        // Do not latch hasEverAlarmed here: bridge/WiFi/signal-loss can paint red
        // from ambient startup state before any operator-facing alarm speaks.
    }
}

/// Snapshot of live metrics used to colour each alert row.
public struct AlertBellMetrics: Sendable, Equatable {
    public var proximitySeparationFeet: Double?
    public var proximityThresholdFeet: Double
    public var proximityActivelyAlerting: Bool
    public var maxAglFeet: Double?
    public var maxRangeFeet: Double?
    public var droneSignalLossActive: Bool
    public var bridgeSecondsSinceLastPing: Double?
    public var bridgeMonitoringActive: Bool
    public var wifiSignalPercent: Int?
    public var videoRequestPending: Bool

    public init(
        proximitySeparationFeet: Double? = nil,
        proximityThresholdFeet: Double = 0,
        proximityActivelyAlerting: Bool = false,
        maxAglFeet: Double? = nil,
        maxRangeFeet: Double? = nil,
        droneSignalLossActive: Bool = false,
        bridgeSecondsSinceLastPing: Double? = nil,
        bridgeMonitoringActive: Bool = false,
        wifiSignalPercent: Int? = nil,
        videoRequestPending: Bool = false
    ) {
        self.proximitySeparationFeet = proximitySeparationFeet
        self.proximityThresholdFeet = proximityThresholdFeet
        self.proximityActivelyAlerting = proximityActivelyAlerting
        self.maxAglFeet = maxAglFeet
        self.maxRangeFeet = maxRangeFeet
        self.droneSignalLossActive = droneSignalLossActive
        self.bridgeSecondsSinceLastPing = bridgeSecondsSinceLastPing
        self.bridgeMonitoringActive = bridgeMonitoringActive
        self.wifiSignalPercent = wifiSignalPercent
        self.videoRequestPending = videoRequestPending
    }

    public func colors() -> [AlertBellKind: AlertBellColor] {
        [
            .proximity: AlertBellThresholdPolicy.proximityColor(
                separationFeet: proximitySeparationFeet,
                thresholdFeet: proximityThresholdFeet,
                isActivelyAlerting: proximityActivelyAlerting
            ),
            .altitude: AlertBellThresholdPolicy.altitudeColor(aglFeet: maxAglFeet),
            .distance: AlertBellThresholdPolicy.distanceColor(rangeFeet: maxRangeFeet),
            .droneSignalLoss: AlertBellThresholdPolicy.droneSignalLossColor(
                isAlerting: droneSignalLossActive
            ),
            .bridgeSignalLoss: AlertBellThresholdPolicy.bridgeColor(
                secondsSinceLastPing: bridgeSecondsSinceLastPing,
                monitoringActive: bridgeMonitoringActive
            ),
            .wifiStrength: AlertBellThresholdPolicy.wifiColor(signalPercent: wifiSignalPercent),
            .videoRequest: AlertBellThresholdPolicy.videoColor(pendingRequest: videoRequestPending),
        ]
    }
}
