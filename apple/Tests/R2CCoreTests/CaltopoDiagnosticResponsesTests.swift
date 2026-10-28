import Foundation
import XCTest
@testable import R2CCore

@MainActor
final class CaltopoDiagnosticResponsesTests: XCTestCase {
    private func reply(_ features: String = "", code: Int = 200, status: String = "ok") -> CaltopoPersonalMarkerTest.Reply {
        .init(code: code, data: Data("{\"status\":\"\(status)\",\"result\":{\"state\":{\"features\":[\(features)]}}}".utf8))
    }
    func testRealDecodeOfLargeResponseRunsOffMain() async throws {
        let worker = CaltopoDiagnosticResponses(beforeDecode: { XCTAssertFalse(Thread.isMainThread) })
        let id = UUID()
        let data = try await Task.detached {
            let feature = "{\"id\":\"other\",\"properties\":{\"class\":\"Marker\"}}"
            let features = Array(repeating: feature, count: 20_000).joined(separator: ",")
            let own = try CaltopoPersonalMarkerTest.payload(id)
            return Data("{\"status\":\"ok\",\"result\":{\"state\":{\"features\":[\(features),\(own)]}}}".utf8)
        }.value
        let result = try await worker.result(code: 200, data: data)
        XCTAssertTrue(result.readable); XCTAssertEqual(result.featureCount, 20_001)
        let presence = try await worker.presence(.init(code: 200, data: data), id: id)
        XCTAssertEqual(presence, .own)
    }
    func testPresencePreservesIdentityAndRequiresRecognizedResponse() async throws {
        let worker = CaltopoDiagnosticResponses(beforeDecode: { XCTAssertFalse(Thread.isMainThread) })
        let id = UUID()
        let absent = try await worker.presence(reply(), id: id)
        XCTAssertEqual(absent, .absent)
        let own = try CaltopoPersonalMarkerTest.payload(id)
        let foreign = own.replacingOccurrences(of: CaltopoPersonalMarkerTest.title(id), with: "Existing operator marker")
        let conflict = try await worker.presence(reply(foreign), id: id)
        XCTAssertEqual(conflict, .conflict)
        for response in [reply(code: 401), reply(status: "error"), .init(code: 200, data: Data("<html>Login</html>".utf8))] {
            do { _ = try await worker.presence(response, id: id); XCTFail("Unrecognized response cannot prove absence") } catch {}
        }
        let denied = try await worker.result(code: 403, data: reply().data)
        XCTAssertFalse(denied.readable)
    }
    func testBlockedDecodeLeavesMainActorAvailable() async throws {
        let entered = AsyncStream<Void>.makeStream()
        let release = DispatchSemaphore(value: 0)
        let worker = CaltopoDiagnosticResponses(beforeDecode: {
            XCTAssertFalse(Thread.isMainThread)
            entered.continuation.yield(())
            release.wait()
        })
        let response = reply()
        let decode = Task { try await worker.result(code: response.code, data: response.data) }
        for await _ in entered.stream { break }
        XCTAssertTrue(Thread.isMainThread) // MainActor progresses while actual decoding worker waits.
        release.signal()
        let decoded = try await decode.value
        XCTAssertTrue(decoded.readable)
        entered.continuation.finish()
    }
    func testCancellationDuringDecodeCannotAcknowledgeCleanup() async throws {
        let entered = AsyncStream<Void>.makeStream()
        let release = DispatchSemaphore(value: 0)
        let worker = CaltopoDiagnosticResponses(beforeDecode: {
            entered.continuation.yield(())
            release.wait()
        })
        var methods: [String] = []
        var pending = true
        let test = Task {
            try await CaltopoPersonalMarkerTest.run(id: UUID(), cleanupOnly: true, responses: worker,
                send: { method, _, _ in methods.append(method); return self.reply() },
                savePending: { _ in XCTFail("Cleanup does not create") }, clearPending: { pending = false })
        }
        for await _ in entered.stream { break }
        test.cancel(); release.signal()
        do { _ = try await test.value; XCTFail("Expected cancellation") } catch is CancellationError {} catch { XCTFail("Unexpected: \(error)") }
        XCTAssertTrue(pending); XCTAssertEqual(methods, ["GET"])
        entered.continuation.finish()
    }
    func testMalformedAndDeniedResponsesRetainPendingOwnership() async throws {
        for response in [reply(code: 403), reply(status: "error"), .init(code: 200, data: Data("bad JSON".utf8))] {
            var pending = true
            var methods: [String] = []
            do {
                _ = try await CaltopoPersonalMarkerTest.run(id: UUID(), cleanupOnly: true,
                    send: { method, _, _ in methods.append(method); return response },
                    savePending: { _ in XCTFail("No save") }, clearPending: { pending = false })
                XCTFail("Expected invalid map failure")
            } catch {}
            XCTAssertTrue(pending); XCTAssertEqual(methods, ["GET"])
        }
    }
}
