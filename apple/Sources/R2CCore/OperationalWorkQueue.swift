import Foundation

/// A serial worker for blocking file/image operations. Enqueueing never waits
/// for the worker, so callers can publish UI state without holding a disk lock.
public final class OperationalWorkQueue: Sendable {
    private let queue: DispatchQueue
    public init(label: String) { queue = DispatchQueue(label: label, qos: .userInitiated) }

    public func enqueue(_ operation: @escaping @Sendable () -> Void) {
        queue.async(execute: operation)
    }

    public func perform<Value: Sendable>(_ operation: @escaping @Sendable () -> Value) async -> Value {
        await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: operation()) }
        }
    }

    public func run<Value: Sendable>(_ operation: @escaping @Sendable () throws -> Value) async throws -> Value {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { continuation.resume(with: Result(catching: operation)) }
        }
    }
}
