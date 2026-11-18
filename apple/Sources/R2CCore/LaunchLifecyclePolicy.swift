import Foundation

/// How the process was started. iOS 15+ may prewarm the app (start the process with no UI,
/// sometimes before the first unlock) and may launch it in the background; neither is an
/// operator opening the app. Ken, Nov 18 2026: "When the user terminates the app, I don't want
/// it continuing to use resources."
public enum LaunchKind: String, Sendable, Equatable {
    case prewarm
    case background
    case foreground

    /// `activePrewarm` is the `ActivePrewarm` environment value, which iOS sets for a prewarmed
    /// launch and removes after launch completes, so read it at the start of launch.
    public static func classify(activePrewarm: String?, applicationStateIsBackground: Bool) -> LaunchKind {
        if activePrewarm == "1" { return .prewarm }
        return applicationStateIsBackground ? .background : .foreground
    }

    /// Startup storage cleanup and migration wait for the first active scene unless the
    /// operator opened the app directly.
    public var defersStartupStorageWork: Bool { self != .foreground }
}

/// Startup storage work (abandoned temporary files, legacy folders, mapping-storage check and
/// backup flags) runs once, the first time the app becomes active. A prewarmed or background
/// process that is never opened does no file work, and protected data is available by then.
public struct DeferredStartupWork: Sendable {
    public private(set) var ran = false
    public init() {}

    /// Returns true exactly once: the first time the app becomes active.
    public mutating func appBecameActive() -> Bool {
        guard !ran else { return false }
        ran = true
        return true
    }
}

/// A resource held for exactly the monitoring session: begun when the session starts and
/// ended in full cleanup. Used for CLBackgroundActivitySession so background location is
/// scoped to the open session (Apple's current direction) without changing today's behavior.
@MainActor
public final class SessionScopedLease<Handle> {
    private let make: () -> Handle?
    private let release: (Handle) -> Void
    public private(set) var handle: Handle?

    public init(make: @escaping () -> Handle?, release: @escaping (Handle) -> Void) {
        self.make = make
        self.release = release
    }

    public var isHeld: Bool { handle != nil }

    /// Begins the lease; a second begin while held is ignored. Returns true when newly held.
    @discardableResult
    public func begin() -> Bool {
        guard handle == nil, let made = make() else { return false }
        handle = made
        return true
    }

    /// Ends the lease if held. Returns true when a held lease was released.
    @discardableResult
    public func end() -> Bool {
        guard let held = handle else { return false }
        handle = nil
        release(held)
        return true
    }
}
