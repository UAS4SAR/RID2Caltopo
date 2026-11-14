import Foundation

/// Team CalTopo access for an organization device is allowed only after r2c-tracker has
/// authorized the device in this launch. Matches Android `TrackerStartupGate`.
///
/// Only team-credential access is regulated. Personal-account CalTopo access is never
/// gated, on any server. A device that is not registered with an organization is never
/// gated and keeps using its locally configured team credentials. Waiting requests are
/// suspended, never blocking the main thread.
///
/// - `notRequired`: not an organization device. Team access is allowed.
/// - `pending`: the startup check-in has not finished. Team requests wait (bounded).
/// - `checkedIn`: the Tracker authorized this device. Team access is allowed.
/// - `unreachable`: the Tracker could not be reached. Team access is refused on every
///   server; the check-in keeps retrying in the background.
/// - `rejected`: the Tracker refused this device (HTTP 401/403). Team access is refused
///   and retries stop until Tracker sign-in or re-enrollment.
///
/// Team access returns only after a later successful authorization.
public final class TrackerStartupGate: @unchecked Sendable {
    public enum State: String, Sendable { case notRequired, pending, checkedIn, unreachable, rejected }

    public static let shared = TrackerStartupGate()

    public static let waitingMessage =
        "Checking in with the organization Tracker before using the team CalTopo account…"
    public static let unreachableMessage =
        "The organization Tracker could not be reached, so team CalTopo access is unavailable. "
        + "Sign in with your personal CalTopo account. The app keeps retrying the Tracker."
    public static let rejectedMessage =
        "The organization Tracker has not authorized this device, so team CalTopo access is "
        + "unavailable. Sign in with your personal CalTopo account."
    public static let maximumWaitSeconds: TimeInterval = 180

    public struct Refused: LocalizedError, Sendable, Equatable {
        public let message: String
        public var errorDescription: String? { message }
    }

    private let lock = NSLock()
    private var current: State = .notRequired
    private var waiters: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var observers: [UUID: @Sendable (State) -> Void] = [:]

    public init() {}

    public var state: State { lock.withLock { current } }

    /// Launch, before anything can open a CalTopo session.
    public func beginStartupCheckIn() { set(.pending) }

    public func set(_ next: State) {
        let (changed, resume, notify): (Bool, [CheckedContinuation<Void, Never>], [@Sendable (State) -> Void]) = lock.withLock {
            let changed = current != next
            current = next
            var resume: [CheckedContinuation<Void, Never>] = []
            if next != .pending {
                resume = Array(waiters.values)
                waiters.removeAll()
            }
            return (changed, resume, Array(observers.values))
        }
        resume.forEach { $0.resume() }
        if changed { notify.forEach { $0(next) } }
    }

    @discardableResult
    public func observe(_ observer: @escaping @Sendable (State) -> Void) -> UUID {
        let id = UUID()
        lock.withLock { observers[id] = observer }
        return id
    }

    public func removeObserver(_ id: UUID) { _ = lock.withLock { observers.removeValue(forKey: id) } }

    /// Nil when team access may proceed; otherwise the reason it may not.
    public static func blockReason(_ state: State) -> String? {
        switch state {
        case .notRequired, .checkedIn: nil
        case .pending: waitingMessage
        case .unreachable: unreachableMessage
        case .rejected: rejectedMessage
        }
    }

    public var teamAccessBlockReason: String? { Self.blockReason(state) }

    /// Refused for an outcome, not merely waiting for one.
    public var teamAccessRefused: Bool {
        let value = state
        return value != .pending && Self.blockReason(value) != nil
    }

    /// Suspends until the check-in has an outcome (or the wait limit). Never throws.
    public func waitForDecision(maximumWait: TimeInterval = maximumWaitSeconds) async {
        guard state == .pending else { return }
        let id = UUID()
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await withCheckedContinuation { continuation in
                    let resumeNow: Bool = self.lock.withLock {
                        if self.current != .pending { return true }
                        self.waiters[id] = continuation
                        return false
                    }
                    if resumeNow { continuation.resume() }
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(0, maximumWait) * 1_000_000_000))
                let waiter: CheckedContinuation<Void, Never>? = self.lock.withLock { self.waiters.removeValue(forKey: id) }
                waiter?.resume()
            }
            await group.next()
            group.cancelAll()
        }
        let leftover: CheckedContinuation<Void, Never>? = lock.withLock { waiters.removeValue(forKey: id) }
        leftover?.resume()
    }

    /// Suspends until the check-in has an outcome (or the wait limit), then throws
    /// `Refused` if team access may not proceed.
    public func awaitTeamAccess(maximumWait: TimeInterval = maximumWaitSeconds) async throws {
        await waitForDecision(maximumWait: maximumWait)
        if let reason = teamAccessBlockReason { throw Refused(message: reason) }
    }

    /// Tests only.
    public func resetForTesting() {
        let pending: [CheckedContinuation<Void, Never>] = lock.withLock {
            current = .notRequired
            observers.removeAll()
            let values = Array(waiters.values)
            waiters.removeAll()
            return values
        }
        pending.forEach { $0.resume() }
    }
}

/// Outcome of one organization-configuration request to r2c-tracker. Matches Android.
public enum TrackerCheckInOutcome: Equatable, Sendable {
    case checkedIn
    case rejected
    case unreachable(String)
}

public enum TrackerStartupCheckIn {
    /// Waits before attempts 2 and 3. A cold Tracker usually answers within one timeout.
    public static let attemptBackoffSeconds: [TimeInterval] = [3, 8]
    public static let requestTimeoutSeconds: TimeInterval = 60
    public static let backgroundRetryFirstSeconds: TimeInterval = 30
    public static let backgroundRetryMaximumSeconds: TimeInterval = 300

    /// A rejection stops at once; an unreachable Tracker is tried `backoff.count + 1` times.
    public static func checkInWithRetries(
        isolation: isolated (any Actor)? = #isolation,
        backoff: [TimeInterval] = attemptBackoffSeconds,
        sleep: (TimeInterval) async -> Void,
        fetch: (Int) async -> TrackerCheckInOutcome
    ) async -> TrackerCheckInOutcome {
        var last = TrackerCheckInOutcome.unreachable("not attempted")
        for attempt in 1 ... (backoff.count + 1) {
            last = await fetch(attempt)
            guard case .unreachable = last else { return last }
            if attempt <= backoff.count { await sleep(backoff[attempt - 1]) }
        }
        return last
    }

    public static func gateState(for outcome: TrackerCheckInOutcome) -> TrackerStartupGate.State {
        switch outcome {
        case .checkedIn: .checkedIn
        case .rejected: .rejected
        case .unreachable: .unreachable
        }
    }

    /// Only the first check-in of a launch decides team access. A later refresh that cannot
    /// reach the Tracker leaves an earlier successful check-in in place.
    public static func nextGateState(previous: TrackerStartupGate.State, outcome: TrackerCheckInOutcome) -> TrackerStartupGate.State {
        if previous == .checkedIn, case .unreachable = outcome { return .checkedIn }
        return gateState(for: outcome)
    }

    public static func nextBackgroundRetry(after seconds: TimeInterval?) -> TimeInterval {
        guard let seconds else { return backgroundRetryFirstSeconds }
        return min(seconds * 2, backgroundRetryMaximumSeconds)
    }
}
