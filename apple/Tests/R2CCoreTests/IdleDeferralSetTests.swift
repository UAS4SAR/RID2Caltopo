import Testing
import Foundation
@testable import R2CCore

// Downloading map tiles or processing local map images counts as busy: the idle timeout is
// suspended while any of them runs and restarts from the end of the last one (Nov 18 2026).
// Same semantics as Android MappingWorkTracker + MapOfflinePrepRuntime.

private func remaining(_ set: IdleDeferralSet, now: TimeInterval, minutes: Int = 2) -> TimeInterval? {
    ApplicationIdleTimeoutPolicy.remainingDelay(
        appStartedAt: Date(timeIntervalSince1970: 0), lastRIDMessageAt: nil, maximumIdleMinutes: minutes,
        now: Date(timeIntervalSince1970: now),
        lastProtectedActivityAt: set.lastActivityAt, protectedActivityActive: set.active)
}

@Test func mappingWorkSuspendsIdleTimeoutPastTheDeadline() {
    var set = IdleDeferralSet()
    _ = set.begin("mapping.reconstruction", at: Date(timeIntervalSince1970: 30))
    #expect(set.active)
    #expect(remaining(set, now: 3_600) == nil)
    #expect(!ApplicationIdleTimeoutPolicy.isExpired(
        appStartedAt: Date(timeIntervalSince1970: 0), lastRIDMessageAt: nil, maximumIdleMinutes: 2,
        now: Date(timeIntervalSince1970: 3_600),
        lastProtectedActivityAt: set.lastActivityAt, protectedActivityActive: set.active))
}

@Test func countdownRestartsWhenTheLastWorkFinishesOrFails() {
    var set = IdleDeferralSet()
    let reconstruction = set.begin("mapping.reconstruction", at: Date(timeIntervalSince1970: 10))
    let export = set.begin("mapping.geotiff", at: Date(timeIntervalSince1970: 20))
    let ended1 = set.end(reconstruction, at: Date(timeIntervalSince1970: 500))
    #expect(ended1)   // export still busy
    #expect(set.active)
    #expect(remaining(set, now: 900) == nil)
    let ended2 = set.end(export, at: Date(timeIntervalSince1970: 1_000))
    #expect(ended2)         // failure path ends the same way
    #expect(!set.active)
    #expect(remaining(set, now: 1_000) == 120)
    #expect(remaining(set, now: 1_090) == 30)
    #expect(remaining(set, now: 1_200) == 0)
    let ended3 = set.end(export, at: Date(timeIntervalSince1970: 1_300))
    #expect(!ended3)        // ending twice is ignored
    #expect(set.lastActivityAt == Date(timeIntervalSince1970: 1_000))
}

@Test func offlineDownloadLevelControlDoesNotEndMappingWork() {
    var set = IdleDeferralSet()
    set.set("offline-map", active: true, at: Date(timeIntervalSince1970: 0))
    set.set("offline-map", active: true, at: Date(timeIntervalSince1970: 5))  // repeated start holds one lease
    let mapping = set.begin("mapping.transfer", at: Date(timeIntervalSince1970: 10))
    set.set("offline-map", active: false, at: Date(timeIntervalSince1970: 50))
    #expect(set.active)                                                      // mapping still holds it
    #expect(set.activeSources == ["mapping.transfer"])
    let ended4 = set.end(mapping, at: Date(timeIntervalSince1970: 60))
    #expect(ended4)
    #expect(!set.active)
    set.set("offline-map", active: false, at: Date(timeIntervalSince1970: 70)) // stop with nothing held
    #expect(!set.active && set.lastActivityAt == Date(timeIntervalSince1970: 70))
}
