import Foundation

/// In-memory, request/resource-specific grants. Server hints never grant consent.
/// Revoked objects stay revoked even if advance authorization is enabled again.
public final class RemoteMediaConsent: @unchecked Sendable {
    public final class Grant: @unchecked Sendable {
        public let key: String
        public let resource: String
        public let automatic: Bool
        private let lock = NSLock()
        private var valid: Bool
        private var cancellation: [@Sendable () -> Void] = []

        fileprivate init(key: String, resource: String, automatic: Bool, valid: Bool) {
            self.key = key; self.resource = resource
            self.automatic = automatic; self.valid = valid
        }

        public var isAllowed: Bool {
            lock.lock(); defer { lock.unlock() }
            return valid
        }

        public func onRevocation(_ action: @escaping @Sendable () -> Void) {
            lock.lock()
            if valid { cancellation.append(action); lock.unlock() }
            else { lock.unlock(); action() }
        }

        fileprivate func invalidate() -> [@Sendable () -> Void] {
            lock.lock(); defer { lock.unlock() }
            guard valid else { return [] }
            valid = false
            let actions = cancellation
            cancellation.removeAll()
            return actions
        }
    }

    private let lock = NSLock()
    private var enabled = false
    private var grants: [String: Grant] = [:]

    public init() {}

    public func update(enabled value: Bool) {
        lock.lock()
        enabled = value
        let actions = value ? [] : grants.values.filter(\.automatic).flatMap { $0.invalidate() }
        lock.unlock()
        actions.forEach { $0() }
    }

    @discardableResult
    public func receive(key: String, resource: String, serverRequiresConsent: Bool) -> Grant {
        lock.lock(); defer { lock.unlock() }
        if let old = grants[key], old.resource == resource { return old }
        // A reused ID cannot redirect a grant to a different source or upload.
        if grants[key] != nil || key.isEmpty || resource.isEmpty || grants.count >= 2048 {
            return Grant(key: key, resource: resource, automatic: true, valid: false)
        }
        let automatic = !serverRequiresConsent && enabled
        let grant = Grant(key: key, resource: resource, automatic: automatic, valid: automatic)
        grants[key] = grant
        return grant
    }

    public func find(key: String, resource: String) -> Grant? {
        lock.lock(); defer { lock.unlock() }
        guard let grant = grants[key], grant.resource == resource else { return nil }
        return grant
    }

    /// Only call for a fresh local approval gesture, never a server acknowledgement.
    @discardableResult
    public func approve(key: String) -> Grant? {
        lock.lock()
        guard let old = grants[key] else { lock.unlock(); return nil }
        let actions = old.invalidate()
        let grant = Grant(key: key, resource: old.resource, automatic: false, valid: true)
        grants[key] = grant
        lock.unlock()
        actions.forEach { $0() }
        return grant
    }

    public func cancel(key: String) {
        lock.lock()
        let actions = grants[key]?.invalidate() ?? []
        lock.unlock()
        actions.forEach { $0() }
    }

    /// Retire a completed transfer without reporting a consent withdrawal.
    public func finish(key: String, grant: Grant) {
        lock.lock(); defer { lock.unlock() }
        if grants[key] === grant { _ = grant.invalidate() }
    }

    /// Transport/incident changes require new requests and fresh local grants.
    public func reset() {
        lock.lock()
        let actions = grants.values.flatMap { $0.invalidate() }
        // Keep tombstones so reconnect/replayed IDs cannot revive an old grant.
        lock.unlock()
        actions.forEach { $0() }
    }
}
