import Foundation

/// Only tiny status mutations use the lock; recording/storage work runs outside it.
public final class RecordingFinalizationActivity: @unchecked Sendable {
    public static let shared = RecordingFinalizationActivity()
    private let lock = NSLock()
    private var pending: [UUID: String] = [:]

    public init() {}

    public func begin(path: String) -> UUID {
        let token = UUID()
        let key = Self.normalize(path)
        lock.lock()
        pending[token] = key
        lock.unlock()
        return token
    }

    public func finish(_ token: UUID) {
        lock.lock()
        pending.removeValue(forKey: token)
        lock.unlock()
    }

    public func isActive(matching designators: [String]) -> Bool {
        let keys = Set(designators.map(Self.normalize).filter { !$0.isEmpty })
        lock.lock()
        defer { lock.unlock() }
        return pending.values.contains { keys.contains($0) }
    }

    /// True while any recording normalize/localize token is outstanding.
    public var hasPending: Bool {
        lock.lock(); defer { lock.unlock() }
        return !pending.isEmpty
    }

    private static func normalize(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased().split(separator: "/").last.map(String.init) ?? ""
    }
}

/// Monotonic idle grace period; queued/running finalization does not consume it.
public struct RecordingLinkRetryWindow: Sendable {
    private let grace: TimeInterval
    private var deadline: TimeInterval

    public init(now: TimeInterval, grace: TimeInterval = 30) {
        self.grace = grace
        deadline = now + grace
    }

    public mutating func shouldRetry(now: TimeInterval, finalizing: Bool) -> Bool {
        if finalizing { deadline = now + grace }
        return now < deadline
    }
}
