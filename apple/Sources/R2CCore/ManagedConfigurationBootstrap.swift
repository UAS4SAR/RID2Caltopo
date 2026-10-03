import Foundation

/// Downloads organization settings independently of incident-map coordination.
@MainActor
public final class ManagedConfigurationBootstrap<Snapshot> {
    private var inFlight = Set<String>()
    private var completionWaiters: [String: [CheckedContinuation<Void, Never>]] = [:]
    private var lastAttempt: (scope: String, time: TimeInterval)?

    public init() {}

    @discardableResult
    public func refresh(
        scope: String,
        force: Bool = false,
        waitForInFlight: Bool = false,
        now: TimeInterval = ProcessInfo.processInfo.systemUptime,
        fetch: () async throws -> Snapshot?,
        isCurrent: () -> Bool,
        apply: (Snapshot) throws -> Void
    ) async throws -> Bool {
        guard !scope.isEmpty else { return false }
        // A restored path must get a fresh attempt after an offline request finishes.
        while waitForInFlight && inFlight.contains(scope) {
            await withCheckedContinuation { completionWaiters[scope, default: []].append($0) }
        }
        try Task.checkCancellation()
        guard !inFlight.contains(scope), isCurrent() else { return false }
        if !force, let lastAttempt, lastAttempt.scope == scope,
           now - lastAttempt.time < 30 { return false }
        inFlight.insert(scope)
        lastAttempt = (scope, now)
        defer {
            inFlight.remove(scope)
            let waiters = completionWaiters.removeValue(forKey: scope) ?? []
            for waiter in waiters { waiter.resume() }
        }
        guard let snapshot = try await fetch(), isCurrent() else { return false }
        try apply(snapshot)
        return true
    }
}

public enum ManagedConfigurationRefreshPolicy {
    public static func shouldApply(remoteVersion: Int64, localVersion: Int64, hasCredentials: Bool) -> Bool {
        remoteVersion != localVersion || !hasCredentials
    }
}
