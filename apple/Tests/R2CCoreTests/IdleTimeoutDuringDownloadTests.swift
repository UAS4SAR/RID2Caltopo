import Testing
import Foundation
@testable import R2CCore

// An active offline map download (including AOL preparation) suspends the idle timeout, and the
// countdown restarts from the download's end. Same cases as Android ApplicationIdleTimeoutPolicyTest.

@Test func idleTimeoutSuspendedWhileOfflineDownloadRunsEvenPastTheDeadline() {
    let startedAt = Date(timeIntervalSince1970: 0)
    let downloadStartedAt = Date(timeIntervalSince1970: 30)
    let now = Date(timeIntervalSince1970: 600)
    #expect(ApplicationIdleTimeoutPolicy.remainingDelay(
        appStartedAt: startedAt, lastRIDMessageAt: nil, maximumIdleMinutes: 2, now: now,
        lastProtectedActivityAt: downloadStartedAt, protectedActivityActive: true
    ) == nil)
    #expect(!ApplicationIdleTimeoutPolicy.isExpired(
        appStartedAt: startedAt, lastRIDMessageAt: nil, maximumIdleMinutes: 2, now: now,
        lastProtectedActivityAt: downloadStartedAt, protectedActivityActive: true
    ))
}

@Test func idleCountdownRestartsFromOfflineDownloadEnd() {
    let startedAt = Date(timeIntervalSince1970: 0)
    let lastRID = Date(timeIntervalSince1970: 50)
    let lastInput = Date(timeIntervalSince1970: 90)
    let downloadEndedAt = Date(timeIntervalSince1970: 600)
    func remaining(_ seconds: TimeInterval) -> TimeInterval? {
        ApplicationIdleTimeoutPolicy.remainingDelay(
            appStartedAt: startedAt, lastRIDMessageAt: lastRID, maximumIdleMinutes: 2,
            now: downloadEndedAt.addingTimeInterval(seconds),
            lastProtectedActivityAt: downloadEndedAt, protectedActivityActive: false,
            lastUserInteractionAt: lastInput
        )
    }
    #expect(remaining(0) == 120)
    #expect(remaining(60) == 60)
    #expect(remaining(120) == 0)
    #expect(ApplicationIdleTimeoutPolicy.isExpired(
        appStartedAt: startedAt, lastRIDMessageAt: lastRID, maximumIdleMinutes: 2,
        now: downloadEndedAt.addingTimeInterval(120),
        lastProtectedActivityAt: downloadEndedAt, lastUserInteractionAt: lastInput
    ))
}
