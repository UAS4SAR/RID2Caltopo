import Foundation

/// Decides when the spoken "Proximity" advisory is (re)announced.
///
/// Shared rule with Android `ProximitySpeechSchedule` (AlertSpeechCoordinator.kt):
/// announce when a new alert instance becomes active, then repeat every
/// `repeatInterval` while that alert stays active and is not suspended. A
/// suspended, cleared, or disabled alert resets the schedule, so a resumed alert
/// is treated as newly active. The per-pair 30 s speech cooldown still applies on
/// top of this schedule on both platforms.
public struct RidProximitySpeechSchedule: Sendable {
    /// Android `ProximitySpeechSchedule.REPEAT_INTERVAL_MS` (30_000 ms).
    public static let repeatInterval: TimeInterval = 30

    private var lastInstanceID: Int64?
    private var lastAnnouncedAt: Date?

    public init() {}

    public mutating func shouldAnnounce(
        activeAlertInstanceID: Int64?,
        suspended: Bool,
        enabled: Bool,
        now: Date
    ) -> Bool {
        guard enabled, !suspended, let activeAlertInstanceID else {
            lastInstanceID = nil
            lastAnnouncedAt = nil
            return false
        }
        if activeAlertInstanceID != lastInstanceID {
            lastInstanceID = activeAlertInstanceID
            lastAnnouncedAt = now
            return true
        }
        guard let lastAnnouncedAt, now.timeIntervalSince(lastAnnouncedAt) >= Self.repeatInterval else {
            return false
        }
        self.lastAnnouncedAt = now
        return true
    }
}
