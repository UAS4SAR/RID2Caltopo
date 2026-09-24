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
