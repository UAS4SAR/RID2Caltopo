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
}
