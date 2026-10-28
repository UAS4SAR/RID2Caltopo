import XCTest
@testable import R2CCore

@MainActor
final class CaltopoPersonalMarkerTestTests: XCTestCase {
    let id = UUID(uuidString: "f31f915d-cc63-48ab-854f-9643c0c80c01")!
    func snapshot(_ feature: String = "") -> CaltopoPersonalMarkerTest.Reply {
        .init(code: 200, data: Data("{\"status\":\"ok\",\"result\":{\"state\":{\"features\":[\(feature)]}}}".utf8))
    }
    func testCreateReadDeleteVerifyAndJournalBeforeWrite() async throws {
        var methods: [String] = []
        var present = false
        var pending = false
        let result = try await CaltopoPersonalMarkerTest.run(id: id, cleanupOnly: false, send: { method, path, payload in
            methods.append(method)
            XCTAssertTrue(path.hasPrefix("/api/v1/map/G00CPSS/"))
            switch method {
            case "POST":
                XCTAssertTrue(pending)
                XCTAssertNotNil(payload)
                present = true
            case "DELETE":
                XCTAssertEqual(path, CaltopoPersonalMarkerTest.markerPath(self.id))
                present = false
            default: break
            }
            return self.snapshot(present ? try CaltopoPersonalMarkerTest.payload(self.id) : "")
        }, savePending: { _ in pending = true }, clearPending: { pending = false })
        XCTAssertEqual(methods, ["GET", "POST", "GET", "DELETE", "GET"])
        XCTAssertFalse(pending)
        XCTAssertTrue(result.contains("verified absent"))
    }
    func testAmbiguousCreateKeepsJournalAndDoesNotRetry() async throws {
        var pending = false
        var methods: [String] = []
        do {
            _ = try await CaltopoPersonalMarkerTest.run(id: id, cleanupOnly: false, send: { method, _, _ in
                methods.append(method)
                return method == "POST" ? .init(code: 503, data: Data()) : self.snapshot()
            }, savePending: { _ in pending = true }, clearPending: { pending = false })
            XCTFail("Expected failure")
        } catch {}
        XCTAssertTrue(pending)
        XCTAssertEqual(methods, ["GET", "POST"])
    }
    func testCleanupRefusesForeignMarkerAndMalformedMap() async throws {
        let foreign = try CaltopoPersonalMarkerTest.payload(id).replacingOccurrences(of: CaltopoPersonalMarkerTest.title(id), with: "Existing operator marker")
        var writes = 0
        do {
            _ = try await CaltopoPersonalMarkerTest.run(id: id, cleanupOnly: true, send: { method, _, _ in
                if method != "GET" { writes += 1 }
                return self.snapshot(foreign)
            }, savePending: { _ in }, clearPending: {})
            XCTFail("Expected failure")
        } catch {}
        XCTAssertEqual(writes, 0)
        XCTAssertThrowsError(try CaltopoPersonalMarkerTest.presence(.init(code: 200, data: Data("<html>Login</html>".utf8)), id: id))
    }
    func testCleanupAbsentClearsJournalWithoutWriting() async throws {
        var cleared = false
        _ = try await CaltopoPersonalMarkerTest.run(id: id, cleanupOnly: true, send: { method, _, _ in
            XCTAssertEqual(method, "GET")
            return self.snapshot()
        }, savePending: { _ in }, clearPending: { cleared = true })
        XCTAssertTrue(cleared)
    }
    func testFailedDurableSavePreventsPublishing() async throws {
        var methods: [String] = []
        do {
            _ = try await CaltopoPersonalMarkerTest.run(id: id, cleanupOnly: false, send: { method, _, _ in
                methods.append(method)
                return self.snapshot()
            }, savePending: { _ in throw CocoaError(.fileWriteNoPermission) }, clearPending: { XCTFail("No cleanup expected") })
            XCTFail("Expected persistence failure")
        } catch {}
        XCTAssertEqual(methods, ["GET"])
    }

    func testAsynchronousSaveCompletesBeforePostAndClearAfterVerification() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = CaltopoPendingMarkerStore(location: { url })
        defer { try? FileManager.default.removeItem(at: url) }
        var present = false
        _ = try await CaltopoPersonalMarkerTest.run(id: id, cleanupOnly: false, send: { method, _, _ in
            if method == "POST" {
                let saved = try await store.load()
                XCTAssertEqual(saved, self.id)
                present = true
            }
            if method == "DELETE" { present = false }
            return self.snapshot(present ? try CaltopoPersonalMarkerTest.payload(self.id) : "")
        }, savePending: { try await store.save($0) }, clearPending: { try await store.clear() })
        let pending = try await store.load()
        XCTAssertNil(pending)
    }

    func testCancellationDuringDurableSaveKeepsJournalWithoutPosting() async throws {
        let events = AsyncStream<Void>.makeStream()
        let saved = AsyncStream<Void>.makeStream()
        var methods: [String] = []
        var pending: UUID?
        let operation = Task {
            try await CaltopoPersonalMarkerTest.run(id: self.id, cleanupOnly: false, send: { method, _, _ in
                methods.append(method)
                return self.snapshot()
            }, savePending: { marker in
                events.continuation.yield(())
                for await _ in saved.stream { break }
                pending = marker
            }, clearPending: { XCTFail("Cancelled publishing must retain the saved ownership record") })
        }
        for await _ in events.stream { break }
        operation.cancel()
        saved.continuation.yield(())
        do { _ = try await operation.value; XCTFail("Expected cancellation") } catch is CancellationError {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(pending, id)
        XCTAssertEqual(methods, ["GET"])
        events.continuation.finish(); saved.continuation.finish()
    }

}
