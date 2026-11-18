import Testing
import Foundation
@testable import R2CCore

// Nov 18 2026 lifecycle changes: prewarm/background launches do no startup storage work, and
// background location is held only for the monitoring session.

@Test func prewarmIsRecognisedBeforeApplicationState() {
    #expect(LaunchKind.classify(activePrewarm: "1", applicationStateIsBackground: true) == .prewarm)
    #expect(LaunchKind.classify(activePrewarm: "1", applicationStateIsBackground: false) == .prewarm)
    #expect(LaunchKind.classify(activePrewarm: nil, applicationStateIsBackground: true) == .background)
    #expect(LaunchKind.classify(activePrewarm: "0", applicationStateIsBackground: false) == .foreground)
}

@Test func onlyADirectOpenRunsStartupStorageWorkImmediately() {
    #expect(LaunchKind.prewarm.defersStartupStorageWork)
    #expect(LaunchKind.background.defersStartupStorageWork)
    #expect(!LaunchKind.foreground.defersStartupStorageWork)
}

@Test func deferredStartupWorkRunsOnceOnFirstActivation() {
    var work = DeferredStartupWork()
    #expect(!work.ran)
    let first = work.appBecameActive()
    let second = work.appBecameActive()
    #expect(first)
    #expect(!second)
    #expect(work.ran)
}

@MainActor
@Test func sessionLeaseIsHeldForExactlyTheSession() {
    var made = 0
    var released: [Int] = []
    let lease = SessionScopedLease<Int>(make: { made += 1; return made }, release: { released.append($0) })
    #expect(!lease.end())
    #expect(lease.begin())
    #expect(!lease.begin())          // a second start while held keeps the first lease
    #expect(lease.isHeld && made == 1)
    #expect(lease.end())
    #expect(released == [1])
    #expect(!lease.isHeld)
    #expect(lease.begin())           // reopening starts a new session lease
    #expect(lease.end())
    #expect(released == [1, 2])
}

@MainActor
@Test func unavailableLeaseIsNotHeld() {
    let lease = SessionScopedLease<Int>(make: { nil }, release: { _ in Issue.record("nothing to release") })
    #expect(!lease.begin())
    #expect(!lease.isHeld)
    #expect(!lease.end())
}
