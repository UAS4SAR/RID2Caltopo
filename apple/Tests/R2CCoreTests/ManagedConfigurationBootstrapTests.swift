import Testing
@testable import R2CCore

@MainActor
@Test func managedConfigurationBootstrapLoadsBeforeAnyMapIsSelected() async throws {
    let loader = ManagedConfigurationBootstrap<[String]>()
    var aircraft: [String] = []
    let applied = try await loader.refresh(scope: "organization/device", now: 0,
        fetch: { ["Neo", "Neo - 2"] }, isCurrent: { true }, apply: { aircraft = $0 })
    #expect(applied)
    #expect(aircraft == ["Neo", "Neo - 2"])
}

@MainActor
@Test func managedConfigurationBootstrapRetriesImmediatelyAfterSignIn() async throws {
    enum Failure: Error { case signInRequired }
    let loader = ManagedConfigurationBootstrap<String>()
    do {
        try await loader.refresh(scope: "device", now: 0,
            fetch: { throw Failure.signInRequired }, isCurrent: { true }, apply: { _ in })
        Issue.record("Unauthorized fetch should fail")
    } catch Failure.signInRequired {}
    var attempts = 0
    let throttled = try await loader.refresh(scope: "device", now: 1,
        fetch: { attempts += 1; return "settings" }, isCurrent: { true }, apply: { _ in })
    #expect(!throttled)
    let recovered = try await loader.refresh(scope: "device", force: true, now: 2,
        fetch: { attempts += 1; return "settings" }, isCurrent: { true }, apply: { _ in })
    #expect(recovered)
    #expect(attempts == 1)
}

@MainActor
@Test func managedConfigurationBootstrapDiscardsPreviousOrganizationResponse() async throws {
    let loader = ManagedConfigurationBootstrap<String>()
    var currentScope = "old"
    var applied = false
    let result = try await loader.refresh(scope: currentScope, now: 0,
        fetch: { currentScope = "new"; return "old organization's settings" },
        isCurrent: { currentScope == "old" }, apply: { _ in applied = true })
    #expect(!result)
    #expect(!applied)
    let fresh = try await loader.refresh(scope: currentScope, now: 1,
        fetch: { "new organization's settings" }, isCurrent: { true }, apply: { _ in applied = true })
    #expect(fresh && applied)
}

@MainActor
@Test func managedConfigurationBootstrapCoalescesConcurrentRequests() async throws {
    let loader = ManagedConfigurationBootstrap<String>()
    var duplicateFetch = false
    try await loader.refresh(scope: "device", now: 0, fetch: {
        let duplicate = try await loader.refresh(scope: "device", force: true, now: 1,
            fetch: { duplicateFetch = true; return "duplicate" }, isCurrent: { true }, apply: { _ in })
        #expect(!duplicate)
        return "settings"
    }, isCurrent: { true }, apply: { _ in })
    #expect(!duplicateFetch)
}

@Test func managedConfigurationRefreshPreservesUnchangedLocalStateButRestoresClearedCredentials() {
    #expect(!ManagedConfigurationRefreshPolicy.shouldApply(remoteVersion: 42, localVersion: 42, hasCredentials: true))
    #expect(ManagedConfigurationRefreshPolicy.shouldApply(remoteVersion: 42, localVersion: 42, hasCredentials: false))
    #expect(ManagedConfigurationRefreshPolicy.shouldApply(remoteVersion: 43, localVersion: 42, hasCredentials: true))
}

@MainActor
@Test func managedConfigurationRecoveryWaitsForOfflineAttemptThenRetries() async throws {
    enum Offline: Error { case unavailable }
    let loader = ManagedConfigurationBootstrap<String>()
    var releaseOffline: CheckedContinuation<Void, Never>?
    var recovered = ""
    let startup = Task { @MainActor in
        try await loader.refresh(scope: "device", now: 0, fetch: {
            await withCheckedContinuation { releaseOffline = $0 }
            throw Offline.unavailable
        }, isCurrent: { true }, apply: { _ in })
    }
    while releaseOffline == nil { await Task.yield() }
    var retryStarted = false
    var requests = 0
    let recovery = Task { @MainActor in
        retryStarted = true
        return try await loader.refresh(scope: "device", force: true, waitForInFlight: true, now: 1,
            fetch: { requests += 1; return "current settings" }, isCurrent: { true }, apply: { recovered = $0 })
    }
    while !retryStarted { await Task.yield() }
    #expect(requests == 0)
    releaseOffline?.resume()
    do { _ = try await startup.value; Issue.record("Expected offline failure") } catch Offline.unavailable {}
    #expect(try await recovery.value)
    #expect(requests == 1)
    #expect(recovered == "current settings")
}

@MainActor
@Test func managedConfigurationRecoveryDiscardsChangedOrganizationWhileWaiting() async throws {
    let loader = ManagedConfigurationBootstrap<String>()
    var release: CheckedContinuation<Void, Never>?
    var current = true
    let startup = Task { @MainActor in
        try await loader.refresh(scope: "old", now: 0, fetch: {
            await withCheckedContinuation { release = $0 }
            return "old settings"
        }, isCurrent: { current }, apply: { _ in Issue.record("Stale settings applied") })
    }
    while release == nil { await Task.yield() }
    var retryStarted = false
    let recovery = Task { @MainActor in
        retryStarted = true
        return try await loader.refresh(scope: "old", force: true, waitForInFlight: true, now: 1,
            fetch: { Issue.record("Stale recovery fetched"); return "old settings" },
            isCurrent: { current }, apply: { _ in Issue.record("Stale recovery applied") })
    }
    while !retryStarted { await Task.yield() }
    current = false
    release?.resume()
    #expect(try await !startup.value)
    #expect(try await !recovery.value)
}
