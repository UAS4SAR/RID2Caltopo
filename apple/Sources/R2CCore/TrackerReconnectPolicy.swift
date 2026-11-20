import Foundation

/// Reconnect delay and acknowledgement budget for the tracker websocket.
///
/// The ladder is 2, 4, 8, then 10 seconds. An acknowledgement timeout uses
/// that same ladder. The delay returns to 2 seconds only when `hello_ack`
/// arrives. Opening the socket is not acknowledgement.
///
/// A socket that is still open waits 30 seconds for `hello_ack`. The load
/// test delayed that ack by 12 seconds; closing at 10 seconds discarded the
/// in-flight ack and the tablets redialed together. Thirty seconds covers
/// that delay with margin and still bounds a socket that never gets an ack.
/// Heartbeat acks stay at 10 seconds. The budget does not grow on later
/// attempts; the ladder spaces those dials. Websocket messages are unchanged.
///
/// Legacy mode restores the 2.5.8 Android retry: close a missing hello at
/// 10 seconds, redial immediately, and clear the ladder when the socket
/// opens. It is off unless Developer Tools turns it on. iOS and macOS share
/// this policy; their previous client already used the ladder and already
/// waited for `hello_ack` before resetting it.
public struct TrackerReconnectPolicy: Sendable, Equatable {
    public static let baseDelayMilliseconds: Int64 = 2_000
    public static let maxDelayMilliseconds: Int64 = 10_000
    public static let helloAckOpenSocketTimeoutMilliseconds: Int64 = 30_000
    public static let helloAckLegacyTimeoutMilliseconds: Int64 = 10_000
    public static let heartbeatAckTimeoutMilliseconds: Int64 = 10_000
    public static let legacyImmediateAckRetryDefaultsKey = "tracker.legacyImmediateAckRetry"
    public static let reconnectJitterDefaultsKey = "tracker.reconnectJitterEnabled"

    public enum RetryKind: Equatable, Sendable {
        case connectFailure
        case ackTimeout
    }

    public private(set) var nextDelayMilliseconds: Int64

    public init() {
        nextDelayMilliseconds = Self.baseDelayMilliseconds
    }

    public func peekDelayMilliseconds(
        kind: RetryKind,
        legacyImmediateAckRetry: Bool
    ) -> Int64 {
        selectDelay(kind: kind, legacyImmediateAckRetry: legacyImmediateAckRetry, commit: false).delay
    }

    public mutating func takeRetryDelayMilliseconds(
        kind: RetryKind,
        legacyImmediateAckRetry: Bool,
        jitterEnabled: Bool = false,
        randomUnit: Double = 0
    ) -> Int64 {
        let selected = selectDelay(
            kind: kind,
            legacyImmediateAckRetry: legacyImmediateAckRetry,
            commit: true
        )
        nextDelayMilliseconds = selected.next
        guard selected.delay > 0, jitterEnabled else { return selected.delay }
        return Self.equalJitterMilliseconds(selected.delay, randomUnit: randomUnit)
    }

    /// Equal jitter of one ladder step. `randomUnit` is clamped to 0...1.
    /// The result runs from half the step through the full step, and is never
    /// above 10 seconds. A zero delay stays zero.
    public static func equalJitterMilliseconds(_ delayMilliseconds: Int64, randomUnit: Double) -> Int64 {
        let capped = min(max(delayMilliseconds, 0), maxDelayMilliseconds)
        let half = capped / 2
        let span = capped - half
        let unit = min(max(randomUnit, 0), 1)
        var jitter = Int64((unit * Double(span + 1)).rounded(.down))
        if jitter > span { jitter = span }
        if jitter < 0 { jitter = 0 }
        return half + jitter
    }

    /// Absent storage keeps jitter on. `UserDefaults.bool` would treat a
    /// missing key as off.
    public static func reconnectJitterEnabled(in defaults: UserDefaults) -> Bool {
        guard defaults.object(forKey: reconnectJitterDefaultsKey) != nil else { return true }
        return defaults.bool(forKey: reconnectJitterDefaultsKey)
    }

    public mutating func onTransportOpened(legacyImmediateAckRetry: Bool) {
        if legacyImmediateAckRetry {
            nextDelayMilliseconds = Self.baseDelayMilliseconds
        }
    }

    public mutating func onHelloAcknowledged() {
        nextDelayMilliseconds = Self.baseDelayMilliseconds
    }

    public mutating func reset() {
        nextDelayMilliseconds = Self.baseDelayMilliseconds
    }

    public static func helloAckTimeoutMilliseconds(
        socketOpen: Bool,
        legacyImmediateAckRetry: Bool
    ) -> Int64 {
        if legacyImmediateAckRetry || !socketOpen {
            return helloAckLegacyTimeoutMilliseconds
        }
        return helloAckOpenSocketTimeoutMilliseconds
    }

    public static func helloAckTimedOut(
        sentAtMilliseconds: Int64,
        nowMilliseconds: Int64,
        socketOpen: Bool,
        legacyImmediateAckRetry: Bool
    ) -> Bool {
        guard sentAtMilliseconds > 0, nowMilliseconds >= sentAtMilliseconds else { return false }
        let limit = helloAckTimeoutMilliseconds(
            socketOpen: socketOpen,
            legacyImmediateAckRetry: legacyImmediateAckRetry
        )
        return nowMilliseconds - sentAtMilliseconds > limit
    }

    private func selectDelay(
        kind: RetryKind,
        legacyImmediateAckRetry: Bool,
        commit: Bool
    ) -> (delay: Int64, next: Int64) {
        if kind == .ackTimeout && legacyImmediateAckRetry {
            return (0, nextDelayMilliseconds)
        }
        let delay = nextDelayMilliseconds
        let next = commit
            ? min(nextDelayMilliseconds * 2, Self.maxDelayMilliseconds)
            : nextDelayMilliseconds
        return (delay, next)
    }
}
