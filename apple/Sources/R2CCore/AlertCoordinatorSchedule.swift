import Foundation

/// Cadence of the process-wide alert coordinator, the iOS counterpart of Android's
/// AlertSpeechCoordinator. Alerts are re-evaluated every second from the current
/// track snapshot, slower operational maintenance runs every 15 s, and while the
/// app is in background a heartbeat is logged every 15 s so a field log proves
/// that evaluation continued with the display locked.
public struct AlertCoordinatorSchedule: Sendable {
    public static let evaluationInterval: TimeInterval = 1
    public static let maintenanceInterval: TimeInterval = 15
    public static let backgroundHeartbeatInterval: TimeInterval = 15

    public private(set) var lastMaintenanceAt: Date?
    public private(set) var lastHeartbeatAt: Date?

    public init() {}

    /// True on the first call and then once per maintenance interval.
    public mutating func maintenanceDue(at now: Date) -> Bool {
        if let lastMaintenanceAt, now.timeIntervalSince(lastMaintenanceAt) < Self.maintenanceInterval {
            return false
        }
        lastMaintenanceAt = now
        return true
    }

    /// Background heartbeat: due on the first background tick and then every
    /// interval. Returning to foreground re-arms it so the next lock logs at once.
    public mutating func heartbeatDue(at now: Date, inBackground: Bool) -> Bool {
        guard inBackground else {
            lastHeartbeatAt = nil
            return false
        }
        if let lastHeartbeatAt, now.timeIntervalSince(lastHeartbeatAt) < Self.backgroundHeartbeatInterval {
            return false
        }
        lastHeartbeatAt = now
        return true
    }
}

/// Track freshness for coordinator diagnostics, using the same position-age
/// limit as proximity evaluation.
public struct AlertTrackFreshnessSummary: Equatable, Sendable {
    public let trackCount: Int
    public let freshCount: Int
    public let newestAgeSeconds: Double?

    public init(
        sampleDates: [Date],
        now: Date,
        maximumAgeSeconds: Double = RidProximityTelemetry.maximumPositionAgeSeconds
    ) {
        trackCount = sampleDates.count
        let ages = sampleDates.map { now.timeIntervalSince($0) }
        freshCount = ages.filter { (0...maximumAgeSeconds).contains($0) }.count
        newestAgeSeconds = ages.min()
    }

    public var logDescription: String {
        let newest = newestAgeSeconds.map { String(format: "%.1fs", max(0, $0)) } ?? "none"
        return "tracks=\(trackCount) fresh=\(freshCount) newestPositionAge=\(newest)"
    }
}
