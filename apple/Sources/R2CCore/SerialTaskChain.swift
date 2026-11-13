import Foundation

/// Runs async jobs one after another, in enqueue order, without the caller waiting.
/// Used to take slow recording finalization (localize/normalize) off the MediaMTX event
/// loop so publisher lifecycle events are never queued behind it.
public final class SerialTaskChain: @unchecked Sendable {
    private let lock = NSLock()
    private var tail: Task<Void, Never>?

    public init() {}

    @discardableResult
    public func enqueue(
        priority: TaskPriority? = nil,
        _ operation: @escaping @Sendable () async -> Void
    ) -> Task<Void, Never> {
        lock.lock()
        defer { lock.unlock() }
        let previous = tail
        let task = Task(priority: priority) {
            await previous?.value
            await operation()
        }
        tail = task
        return task
    }
}
