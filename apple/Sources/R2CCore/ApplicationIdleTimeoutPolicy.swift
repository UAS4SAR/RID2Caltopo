import Foundation

public enum ApplicationIdleTimeoutPolicy {
    public static func deadline(
        appStartedAt: Date,
        lastRIDMessageAt: Date?,
        maximumIdleMinutes: Int,
        lastProtectedActivityAt: Date? = nil,
        protectedActivityActive: Bool = false
    ) -> Date? {
        guard maximumIdleMinutes > 0, !protectedActivityActive else { return nil }
        let baseline = max(
            appStartedAt,
            max(lastRIDMessageAt ?? appStartedAt, lastProtectedActivityAt ?? appStartedAt)
        )
        return baseline.addingTimeInterval(Double(maximumIdleMinutes) * 60)
    }

    public static func remainingDelay(
        appStartedAt: Date,
        lastRIDMessageAt: Date?,
        maximumIdleMinutes: Int,
        now: Date,
        lastProtectedActivityAt: Date? = nil,
        protectedActivityActive: Bool = false
    ) -> TimeInterval? {
        guard let deadline = deadline(
            appStartedAt: appStartedAt,
            lastRIDMessageAt: lastRIDMessageAt,
            maximumIdleMinutes: maximumIdleMinutes,
            lastProtectedActivityAt: lastProtectedActivityAt,
            protectedActivityActive: protectedActivityActive
        ) else { return nil }
        return max(0, deadline.timeIntervalSince(now))
    }

    public static func isExpired(
        appStartedAt: Date,
        lastRIDMessageAt: Date?,
        maximumIdleMinutes: Int,
        now: Date,
        lastProtectedActivityAt: Date? = nil,
        protectedActivityActive: Bool = false
    ) -> Bool {
        guard let deadline = deadline(
            appStartedAt: appStartedAt,
            lastRIDMessageAt: lastRIDMessageAt,
            maximumIdleMinutes: maximumIdleMinutes,
            lastProtectedActivityAt: lastProtectedActivityAt,
            protectedActivityActive: protectedActivityActive
        ) else { return false }
        return now >= deadline
    }
}
