import Foundation
import Security

/// Serial private-store I/O with an independent, short-held snapshot lock.
/// A UI read never takes the worker's lock or waits for Security.framework.
public final class PrivateCredentialStore: @unchecked Sendable {
    public struct Key: Hashable, Sendable {
        public let service: String
        public let account: String
        public init(_ service: String, _ account: String) { self.service = service; self.account = account }
    }

    public static let live = PrivateCredentialStore(read: readKeychain, write: writeKeychain)
    private let worker = OperationalWorkQueue(label: "r2c.private-credentials")
    private let lock = NSLock()
    private struct Value: Sendable {
        let data: Data
        let text: String
        init(_ data: Data) throws {
            dispatchPrecondition(condition: .notOnQueue(.main))
            guard let text = String(data: data, encoding: .utf8) else {
                throw NSError(domain: "PrivateCredentialStore", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Stored credentials could not be decoded"])
            }
            self.data = data; self.text = text
        }
    }
    private var snapshot: [Key: Value] = [:]
    private let read: @Sendable (Key) throws -> Data?
    private let write: @Sendable (Key, Data?) throws -> Void

    public init(read: @escaping @Sendable (Key) throws -> Data?, write: @escaping @Sendable (Key, Data?) throws -> Void) {
        self.read = read; self.write = write
    }

    public func cached(_ key: Key) -> Data? {
        lock.withLock { snapshot[key]?.data }
    }

    public func cachedString(_ key: Key) -> String? {
        lock.withLock { snapshot[key]?.text }
    }

    @MainActor public func preload(_ keys: [Key]) async throws {
        try await execute { [self] in
            // Publish atomically only after every read succeeds. Unavailable != absent.
            var loaded: [Key: Value] = [:]
            for key in keys { loaded[key] = try read(key).map(Value.init) }
            lock.withLock {
                for key in keys { snapshot[key] = loaded[key] }
            }
        }
    }

    @MainActor public func store(_ data: Data?, for key: Key) async throws {
        try await execute { [self] in
            let prepared = try data.map(Value.init)
            try write(key, data)
            lock.withLock { snapshot[key] = prepared }
        }
    }

    /// Related secrets become visible together. On failure retain the prior
    /// snapshot and attempt to restore any already-written Keychain items.
    @MainActor public func storeBatch(_ values: [(Key, Data?)]) async throws {
        try await execute { [self] in
            let prepared = try values.map { ($0.0, try $0.1.map(Value.init)) }
            let previous = try values.map { ($0.0, try read($0.0)) }
            var committed = 0
            do {
                for (key, data) in values { try write(key, data); committed += 1 }
            } catch {
                for (key, data) in previous.prefix(committed).reversed() { try? write(key, data) }
                throw error
            }
            lock.withLock { for (key, value) in prepared { snapshot[key] = value } }
        }
    }

    @MainActor private func execute<Value: Sendable>(_ operation: @escaping @Sendable () throws -> Value) async throws -> Value {
        // Enqueue synchronously on the caller's actor before suspending. Concurrent
        // UI edits and resets reach the durable store in invocation order.
        try await withCheckedThrowingContinuation { continuation in
            worker.enqueue { continuation.resume(with: Result(catching: operation)) }
        }
    }

    private static func readKeychain(_ key: Key) throws -> Data? {
        var query = attributes(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status == errSecSuccess ? errSecDecode : status))
        }
        return data
    }

    private static func writeKeychain(_ key: Key, _ data: Data?) throws {
        let query = attributes(key)
        guard let data else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
            }
            return
        }
        let values: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let updated = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(updated)) }
        let status = SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    private static func attributes(_ key: Key) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: key.service,
         kSecAttrAccount as String: key.account]
    }
}
