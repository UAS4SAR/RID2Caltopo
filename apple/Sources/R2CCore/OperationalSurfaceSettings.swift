import Foundation

/// Confined to the owning UI actor. Busy requests are dropped, never queued.
public struct OperationalSurfaceWorkGate {
    private var busy = false
    private var lastStarted: [String: TimeInterval] = [:]
    public init() {}
    public mutating func begin(aircraft: String, enabled: Bool, now: TimeInterval) -> Bool {
        guard enabled, !busy else { return false }
        if let last = lastStarted[aircraft], now - last < 1 { return false }
        busy = true
        lastStarted[aircraft] = now
        return true
    }
    public func lastStartedAt(aircraft: String) -> TimeInterval { lastStarted[aircraft] ?? -.infinity }
    public mutating func finish() { busy = false }
    public mutating func forget(aircraft: String) { lastStarted.removeValue(forKey: aircraft) }
}
