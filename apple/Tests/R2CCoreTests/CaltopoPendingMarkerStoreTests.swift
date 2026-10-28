import Foundation
import XCTest
@testable import R2CCore

@MainActor
final class CaltopoPendingMarkerStoreTests: XCTestCase {
    func testColdReadAndDurableSaveClearRunOffMain() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = root.appendingPathComponent("pending.txt")
        let store = CaltopoPendingMarkerStore(location: {
            XCTAssertFalse(Thread.isMainThread)
            return url
        })
        defer { try? FileManager.default.removeItem(at: root) }
        let empty = try await store.load()
        XCTAssertNil(empty)
        let id = UUID()
        try await store.save(id)
        let restored = try await store.load()
        XCTAssertEqual(restored, id)
        try await store.clear()
        try await store.clear()
        let cleared = try await store.load()
        XCTAssertNil(cleared)
    }

    func testSlowColdFileOperationDoesNotBlockMainActor() async throws {
        let events = AsyncStream<Void>.makeStream()
        let release = DispatchSemaphore(value: 0)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = CaltopoPendingMarkerStore(location: {
            XCTAssertFalse(Thread.isMainThread)
            events.continuation.yield(())
            release.wait()
            return url
        })
        let read = Task { try await store.load() }
        for await _ in events.stream { break }
        // MainActor executes while the actual file worker remains blocked.
        XCTAssertTrue(Thread.isMainThread)
        release.signal()
        let result = try await read.value
        XCTAssertNil(result)
        events.continuation.finish()
    }

    func testMalformedRecordIsKeptForRecovery() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = CaltopoPendingMarkerStore(location: { url })
        let data = Data("invalid pending ID".utf8)
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        do { _ = try await store.load(); XCTFail("Expected recovery failure") } catch {}
        XCTAssertEqual(try Data(contentsOf: url), data)
    }
}
