import Testing
import Foundation
@testable import R2CCore

private enum RetryFailure: Error { case capacity, catalog }
private func retryCapacity(_ limit: Int64, free: Int64 = 960_000_000_000) -> OperationalOfflineCapacity {
    .init(currentTileCacheBytes: 8_300_000_000, estimatedTileBytes: 12_000_000_000,
          estimatedDEMBytes: 2_000_000_000, maximumTileCacheBytes: limit, availableVolumeBytes: free)
}

@Test @MainActor func offlineRetryAfterIncreasingLimitAndReopening() async throws {
    for cachedPlan: String? in ["aol-plan", nil] {
        var limit: Int64 = 10_000_000_000
        var lookups = 0
        var started: String?
        func retry() async throws {
            try await OperationalOfflineRetry.run(includeAOL: true, matchingPlan: cachedPlan,
                resolvePlan: { lookups += 1; return "aol-plan" }, isCurrent: { true },
                checkCapacity: { _ in
                    if retryCapacity(limit).exceedsCacheLimit { throw RetryFailure.capacity }
                }, start: { started = $0 })
        }
        do { try await retry(); Issue.record("Expected capacity failure") }
        catch RetryFailure.capacity {}
        #expect(started == nil)
        limit = 100_000_000_000
        try await retry()
        #expect(started == "aol-plan")
        #expect(lookups == (cachedPlan == nil ? 2 : 0))
    }
}

@Test @MainActor func offlineRetryChecksResolvedPlanAndFreeSpace() async throws {
    var started = false
    do {
        try await OperationalOfflineRetry.run(includeAOL: true, matchingPlan: nil as String?,
            resolvePlan: { "large-aol" }, isCurrent: { true }, checkCapacity: { plan in
                #expect(plan == "large-aol")
                if retryCapacity(100_000_000_000, free: 1_000_000_000).exceedsAvailableVolume {
                    throw RetryFailure.capacity
                }
            }, start: { _ in started = true })
        Issue.record("Expected free-space failure")
    } catch RetryFailure.capacity {}
    #expect(!started)
}

@Test @MainActor func offlineRetryRecoversCatalogFailureWithoutOmittingAol() async throws {
    var started: String?
    do {
        try await OperationalOfflineRetry.run(includeAOL: true, matchingPlan: nil as String?,
            resolvePlan: { throw RetryFailure.catalog }, isCurrent: { true },
            checkCapacity: { _ in }, start: { started = $0 })
        Issue.record("Expected catalog failure")
    } catch RetryFailure.catalog {}
    #expect(started == nil)
    try await OperationalOfflineRetry.run(includeAOL: true, matchingPlan: nil as String?,
        resolvePlan: { "recovered" }, isCurrent: { true }, checkCapacity: { _ in }, start: { started = $0 })
    #expect(started == "recovered")
}

@Test @MainActor func offlineRetryRejectsChangedSelectionAndCancellation() async throws {
    var current = true
    var started = false
    try await OperationalOfflineRetry.run(includeAOL: true, matchingPlan: nil as String?,
        resolvePlan: { current = false; return "old-region" }, isCurrent: { current },
        checkCapacity: { _ in Issue.record("Stale selection must not reach capacity check") },
        start: { _ in started = true })
    let task = Task { @MainActor in
        try await OperationalOfflineRetry.run(includeAOL: true, matchingPlan: nil as String?,
            resolvePlan: { try await Task.sleep(for: .seconds(60)); return "cancelled" },
            isCurrent: { true }, checkCapacity: { _ in }, start: { _ in started = true })
    }
    task.cancel()
    do { try await task.value; Issue.record("Expected cancellation") } catch is CancellationError {}
    #expect(!started)
    current = true
    try await OperationalOfflineRetry.run(includeAOL: true, matchingPlan: "plan",
        resolvePlan: { Issue.record("Unexpected lookup"); return "bad" }, isCurrent: { current },
        checkCapacity: { _ in current = false }, start: { _ in started = true })
    #expect(!started)
}

@Test @MainActor func offlineRetryExplicitOptOutSkipsAol() async throws {
    var started = false
    try await OperationalOfflineRetry.run(includeAOL: false, matchingPlan: "old-plan",
        resolvePlan: { Issue.record("Unexpected lookup"); return "bad" }, isCurrent: { true },
        checkCapacity: { _ in }, start: { #expect($0 == nil); started = true })
    #expect(started)
}
