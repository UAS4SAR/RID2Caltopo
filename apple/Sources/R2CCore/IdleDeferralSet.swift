import Foundation

/// Work that keeps the app busy and pauses the maximum-idle timeout while it runs: offline map
/// downloads and every local mapping path (original import, Pilot 2 transfer, reconstruction,
/// GeoTIFF export, original-photo preparation). Mirrors Android's MappingWorkTracker plus
/// MapOfflinePrepRuntime: while any lease is held the timeout is suspended; when the last one
/// finishes or fails, the full countdown restarts from that moment (Ken, Nov 18 2026).
public struct IdleDeferralSet: Sendable, Equatable {
    public struct Token: Hashable, Sendable {
        public let source: String
        fileprivate let id: UUID
    }

    private var leases: Set<Token> = []
    public private(set) var lastActivityAt: Date?

    public init() {}

    public var active: Bool { !leases.isEmpty }
    public var activeSources: [String] { Array(Set(leases.map(\.source))).sorted() }

    /// Starts a lease for `source`. Each lease ends exactly once via `end`.
    public mutating func begin(_ source: String, at date: Date) -> Token {
        let token = Token(source: source, id: UUID())
        leases.insert(token)
        note(date)
        return token
    }

    /// Ends a lease; returns false for an unknown or already-ended token.
    @discardableResult
    public mutating func end(_ token: Token, at date: Date) -> Bool {
        guard leases.remove(token) != nil else { return false }
        note(date)
        return true
    }

    /// Level-style control for callers that track a single on/off state (offline downloads):
    /// active holds one lease for `source`; inactive ends every lease held for it.
    public mutating func set(_ source: String, active: Bool, at date: Date) {
        let held = leases.filter { $0.source == source }
        if active {
            if held.isEmpty { _ = begin(source, at: date) } else { note(date) }
        } else {
            leases.subtract(held)
            note(date)
        }
    }

    private mutating func note(_ date: Date) {
        lastActivityAt = max(lastActivityAt ?? date, date)
    }
}
