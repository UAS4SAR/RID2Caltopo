import Foundation
import XCTest
@testable import R2CCore

private final class CredentialBackendFixture: @unchecked Sendable {
    let lock = NSLock()
    var value: Data? = Data("original".utf8)
    var unavailable = false
    let entered = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    var stall = false
    func read() throws -> Data? {
        XCTAssertFalse(Thread.isMainThread)
        let (stall, unavailable, value) = lock.withLock { (self.stall, self.unavailable, self.value) }
        if stall { entered.signal(); release.wait() }
        if unavailable { throw NSError(domain: "unavailable", code: 1) }
        return value
    }
    func waitUntilEntered() { entered.wait() }
    func write(_ data: Data?) throws {
        XCTAssertFalse(Thread.isMainThread)
        try lock.withLock {
            if unavailable { throw NSError(domain: "unavailable", code: 1) }
            value = data
        }
    }
}

final class PrivateCredentialStoreTests: XCTestCase {
    private final class BatchBackend: @unchecked Sendable {
        let lock = NSLock()
        var values: [String: Data] = ["id": Data("old-id".utf8), "secret": Data("old-secret".utf8)]
        var failSecretOnce = true
        func read(_ key: PrivateCredentialStore.Key) -> Data? { lock.withLock { values[key.account] } }
        func write(_ key: PrivateCredentialStore.Key, _ value: Data?) throws {
            try lock.withLock {
                if key.account == "secret", failSecretOnce { failSecretOnce = false; throw NSError(domain: "fixture", code: 1) }
                values[key.account] = value
            }
        }
    }

    @MainActor func testRelatedSecretFailureRollsBackAndRetryCommitsTogether() async throws {
        let backend = BatchBackend()
        let store = PrivateCredentialStore(read: { backend.read($0) }, write: { try backend.write($0, $1) })
        let id = PrivateCredentialStore.Key("fixture", "id"), secret = PrivateCredentialStore.Key("fixture", "secret")
        try await store.preload([id, secret])
        let batch: [(PrivateCredentialStore.Key, Data?)] = [(id, Data("new-id".utf8)), (secret, Data("new-secret".utf8))]
        do { try await store.storeBatch(batch); XCTFail("Expected failed second write") } catch {}
        XCTAssertEqual(store.cached(id), Data("old-id".utf8))
        XCTAssertEqual(backend.read(id), Data("old-id".utf8))
        XCTAssertEqual(store.cached(secret), Data("old-secret".utf8))
        try await store.storeBatch(batch)
        XCTAssertEqual(store.cached(id), Data("new-id".utf8))
        XCTAssertEqual(store.cached(secret), Data("new-secret".utf8))
    }

    @MainActor func testMalformedStoredTextIsUnavailableRatherThanMissing() async throws {
        let backend = CredentialBackendFixture()
        backend.lock.withLock { backend.value = Data([0xff, 0xfe]) }
        let store = PrivateCredentialStore(read: { _ in try backend.read() }, write: { _, data in try backend.write(data) })
        let key = PrivateCredentialStore.Key("fixture", "secret")
        do { try await store.preload([key]); XCTFail("Malformed credential was treated as missing") } catch {}
        backend.lock.withLock { backend.value = Data("repaired".utf8) }
        try await store.preload([key])
        XCTAssertEqual(store.cachedString(key), "repaired")
    }

    @MainActor func testUnavailableIsNotMissingAndFailedWritePreservesSnapshot() async throws {
        let backend = CredentialBackendFixture()
        let store = PrivateCredentialStore(read: { _ in try backend.read() }, write: { _, data in try backend.write(data) })
        let key = PrivateCredentialStore.Key("fixture", "secret")
        try await store.preload([key])
        backend.lock.withLock { backend.unavailable = true }
        do { try await store.preload([key]); XCTFail("Expected unavailable") } catch {}
        do { try await store.store(Data("changed".utf8), for: key); XCTFail("Expected unavailable") } catch {}
        XCTAssertEqual(store.cached(key), Data("original".utf8))
        backend.lock.withLock { backend.unavailable = false }
        try await store.store(nil, for: key)
        XCTAssertNil(store.cached(key))
        XCTAssertNil(backend.lock.withLock { backend.value })
    }

    @MainActor func testDelayedCredentialReadDoesNotBlockMainOrCachedLookup() async throws {
        let backend = CredentialBackendFixture()
        let store = PrivateCredentialStore(read: { _ in try backend.read() }, write: { _, data in try backend.write(data) })
        let key = PrivateCredentialStore.Key("fixture", "secret")
        try await store.preload([key])
        backend.lock.withLock { backend.stall = true }
        let load = Task { try await store.preload([key]) }
        // Waiting for the fixture belongs on a worker, never on MainActor.
        await Task.detached { backend.waitUntilEntered() }.value
        let started = Date()
        XCTAssertEqual(store.cached(key), Data("original".utf8))
        XCTAssertEqual(store.cachedString(key), "original")
        XCTAssertLessThan(Date().timeIntervalSince(started), 0.1)
        backend.release.signal()
        try await load.value
    }
}
