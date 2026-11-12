import Foundation

/// One GATT operation at a time (CoreBluetooth on the iPad; Android's BtrxGattQueue is
/// the same). Two overlapping writes on the bench left one stuck forever and stopped all
/// reads. Every write, read and notification change goes through this queue, and the
/// next one starts only when the delegate callback for the current one arrives
/// (``complete()``). Not thread-safe: confine it to the Bluetooth queue.
public final class BtrxGattQueue<Op> {
    private var pending: [Op] = []
    private var closed = false
    private let start: (Op) -> Bool
    private let onStartFailed: (Op) -> Void

    /// The operation waiting for its delegate callback, or nil when idle.
    public private(set) var inFlight: Op?

    public init(start: @escaping (Op) -> Bool, onStartFailed: @escaping (Op) -> Void) {
        self.start = start
        self.onStartFailed = onStartFailed
    }

    public var count: Int { pending.count + (inFlight == nil ? 0 : 1) }

    public func enqueue(_ op: Op) {
        guard !closed else { return }
        pending.append(op)
        pump()
    }

    /// The delegate callback for the in-flight operation arrived. Returns that operation.
    @discardableResult
    public func complete() -> Op? {
        guard let done = inFlight else { return nil }
        inFlight = nil
        pump()
        return done
    }

    /// Drops everything (session ended). Later ``enqueue(_:)`` calls are ignored.
    public func clear() {
        closed = true
        pending.removeAll()
        inFlight = nil
    }

    private func pump() {
        while !closed, inFlight == nil, !pending.isEmpty {
            let op = pending.removeFirst()
            inFlight = op
            if !start(op) {
                inFlight = nil
                closed = true
                pending.removeAll()
                onStartFailed(op)
                return
            }
        }
    }
}

/// FromRadio drain: after want_config_id and after each FromNum notification, read
/// FromRadio until it comes back empty. A notification during a drain adds one more pass.
public final class BtrxFromRadioDrain {
    public private(set) var draining = false
    private var again = false
    public private(set) var reads: Int64 = 0
    private let requestRead: () -> Void

    public init(requestRead: @escaping () -> Void) { self.requestRead = requestRead }

    public func kick() {
        if draining { again = true; return }
        draining = true
        requestRead()
    }

    /// A FromRadio read finished. Returns true when `value` is a message to deliver.
    public func onRead(_ value: [UInt8]) -> Bool {
        reads += 1
        if !value.isEmpty {
            requestRead()
            return true
        }
        if again {
            again = false
            requestRead()
        } else {
            draining = false
        }
        return false
    }

    public func reset() { draining = false; again = false }
}

/// Bluetooth reconnect delays: 2, 4, 8, 16, then every 30 s; back to 2 s once a session
/// reaches READY. The firmware never reconnects by itself.
public struct BtrxReconnectBackoff: Sendable {
    public private(set) var failures = 0
    private let firstMs: Int64
    private let maxMs: Int64

    public init(firstMs: Int64 = 2_000, maxMs: Int64 = 30_000) { self.firstMs = firstMs; self.maxMs = maxMs }

    public mutating func nextDelayMs() -> Int64 {
        let d = min(firstMs << Int64(min(failures, 10)), maxMs)
        failures += 1
        return d
    }

    public mutating func reset() { failures = 0 }
}
