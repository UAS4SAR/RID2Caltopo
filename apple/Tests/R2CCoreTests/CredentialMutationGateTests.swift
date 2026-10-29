import XCTest
@testable import R2CCore

@MainActor final class CredentialMutationGateTests: XCTestCase {
    func testSuspendedEditsStayOrderedAndNestedCallsDoNotDeadlock() async throws {
        let gate = CredentialMutationGate()
        var release: CheckedContinuation<Void, Never>?
        var events: [String] = []
        let first = Task {
            try await gate.run {
                events.append("first-start")
                await withCheckedContinuation { release = $0 }
                try await gate.run { events.append("nested") }
                events.append("first-commit")
            }
        }
        while release == nil { await Task.yield() }
        let second = Task { try await gate.run { events.append("second-commit") } }
        await Task.yield()
        XCTAssertEqual(events, ["first-start"])
        XCTAssertTrue(gate.isBusy)
        release?.resume()
        try await first.value
        try await second.value
        XCTAssertEqual(events, ["first-start", "nested", "first-commit", "second-commit"])
        XCTAssertEqual(gate.revision, 2)
        XCTAssertFalse(gate.isBusy)
    }

    func testClosingPresenterFinishesAcceptedWriteAndCancelsQueuedWrite() async throws {
        let gate = CredentialMutationGate()
        var release: CheckedContinuation<Void, Never>?
        var committed = false
        let first = Task {
            try await gate.run {
                await withCheckedContinuation { release = $0 }
                committed = true
            }
        }
        while release == nil { await Task.yield() }
        let queued = Task { try await gate.run { XCTFail("Cancelled queued edit ran") } }
        await Task.yield()
        let third = Task { try await gate.run { XCTAssertTrue(committed) } }
        queued.cancel()
        first.cancel()
        release?.resume()
        try await first.value
        do { try await queued.value; XCTFail("Expected cancellation") } catch is CancellationError {}
        try await third.value
        XCTAssertEqual(gate.revision, 2)
        XCTAssertTrue(committed)
        XCTAssertFalse(gate.isBusy)
        try await gate.run { XCTAssertTrue(gate.isBusy) }
    }
}
