import Foundation

/// In-memory log dedup / rate-limit helper.
///
/// Lock is held only while updating in-memory counters — never across disk IO or
/// other slow work. Safe to call from the main actor.
public enum LogThrottle {
    private struct Entry {
        var lastMessage: String
        var lastEmitMs: Int64
        var repeatCount: Int
    }

    private static let lock = NSLock()
    // Protected by `lock`; brief critical sections only.
    nonisolated(unsafe) private static var entries: [String: Entry] = [:]
    nonisolated(unsafe) private static var rateLimitAtMs: [String: Int64] = [:]

    /// Injectable clock for unit tests (milliseconds since epoch).
    nonisolated(unsafe) public static var clockMs: () -> Int64 = {
        Int64(Date().timeIntervalSince1970 * 1000.0)
    }

    /// Returns `message` (optionally with a `(repeated N×)` suffix) when it should be
    /// logged, or `nil` when suppressed.
    ///
    /// - `onChangeOnly`: identical messages are suppressed until they change, unless
    ///   `minIntervalMs` forces a periodic summary.
    /// - `minIntervalMs` > 0: gates identical (or all, when not onChangeOnly) emissions.
    public static func emit(
        key: String,
        message: String,
        minIntervalMs: Int64 = 0,
        onChangeOnly: Bool = false
    ) -> String? {
        let now = clockMs()
        lock.lock()
        defer { lock.unlock() }

        guard var existing = entries[key] else {
            entries[key] = Entry(lastMessage: message, lastEmitMs: now, repeatCount: 0)
            return message
        }

        let same = existing.lastMessage == message
        if same && onChangeOnly {
            if minIntervalMs > 0 && now - existing.lastEmitMs >= minIntervalMs {
                let repeats = existing.repeatCount
                existing.repeatCount = 0
                existing.lastEmitMs = now
                entries[key] = existing
                return repeats > 0 ? "\(message) (repeated \(repeats)×)" : message
            }
            existing.repeatCount += 1
            entries[key] = existing
            return nil
        }

        if same && minIntervalMs > 0 {
            if now - existing.lastEmitMs < minIntervalMs {
                existing.repeatCount += 1
                entries[key] = existing
                return nil
            }
            let repeats = existing.repeatCount
            existing.repeatCount = 0
            existing.lastEmitMs = now
            entries[key] = existing
            return repeats > 0 ? "\(message) (repeated \(repeats)×)" : message
        }

        if same {
            existing.lastEmitMs = now
            existing.repeatCount = 0
            entries[key] = existing
            return message
        }

        existing.lastMessage = message
        existing.lastEmitMs = now
        existing.repeatCount = 0
        entries[key] = existing
        return message
    }

    /// Simple interval gate: true at most once per `intervalMs` for `key`.
    public static func shouldLog(key: String, intervalMs: Int64) -> Bool {
        if intervalMs <= 0 { return true }
        let now = clockMs()
        lock.lock()
        defer { lock.unlock() }
        if let last = rateLimitAtMs[key], now - last < intervalMs {
            return false
        }
        rateLimitAtMs[key] = now
        return true
    }

    public static func resetForTest() {
        lock.lock()
        entries.removeAll(keepingCapacity: false)
        rateLimitAtMs.removeAll(keepingCapacity: false)
        lock.unlock()
        clockMs = { Int64(Date().timeIntervalSince1970 * 1000.0) }
    }
}
