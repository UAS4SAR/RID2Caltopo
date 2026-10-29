import Combine
import Foundation

/// Serializes complete credential/profile edits across worker suspensions.
/// Waiting never blocks Main. Once accepted, a durable edit finishes even if its
/// presenting view closes; queued work can still be cancelled before acceptance.
@MainActor public final class CredentialMutationGate: ObservableObject {
    public static let shared = CredentialMutationGate()
    @Published public private(set) var isBusy = false
    public private(set) var revision = 0
    private var lease: UUID?
    private var waiters: [CheckedContinuation<UUID, Never>] = []
    @TaskLocal private static var currentLease: UUID?

    public init() {}

    public func run<Value: Sendable>(_ operation: @escaping @MainActor () async throws -> Value) async throws -> Value {
        if let lease, lease == Self.currentLease { return try await operation() }
        try Task.checkCancellation()
        let accepted: UUID
        if lease == nil {
            accepted = UUID()
            lease = accepted
            isBusy = true
        } else { accepted = await withCheckedContinuation { waiters.append($0) } }
        // Even a cancelled waiter must hand its reserved slot to the next one.
        defer { release() }
        try Task.checkCancellation()
        revision += 1
        return try await Task { @MainActor in
            try await Self.$currentLease.withValue(accepted) { try await operation() }
        }.value
    }

    private func release() {
        if waiters.isEmpty { lease = nil; isBusy = false }
        else {
            let next = UUID()
            lease = next
            waiters.removeFirst().resume(returning: next)
        }
    }
}
