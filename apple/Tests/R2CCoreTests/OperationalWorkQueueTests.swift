import Foundation
import Testing
@testable import R2CCore

private final class OrderedWorkValues: @unchecked Sendable {
    // Accessed only by one OperationalWorkQueue.
    var values: [Int] = []
}

@Test @MainActor func fileWorkerRunsAwayFromUIThread() async throws {
    let worker = OperationalWorkQueue(label: "test.file-worker")
    let ranOnMain = try await worker.run { Thread.isMainThread }
    #expect(!ranOnMain)
}

@Test @MainActor func fileWorkerCommitsSnapshotsInSubmissionOrder() async throws {
    let worker = OperationalWorkQueue(label: "test.ordered-file-worker")
    let state = OrderedWorkValues()
    for index in 0..<100 { worker.enqueue { state.values.append(index) } }
    let result = try await worker.run { state.values }
    #expect(result == Array(0..<100))
}

@Test @MainActor func fileWorkerPropagatesFailureAndContinues() async throws {
    let worker = OperationalWorkQueue(label: "test.file-worker-failure")
    do {
        let _: Int = try await worker.run { throw CocoaError(.fileWriteOutOfSpace) }
        Issue.record("Expected the disk error")
    } catch { #expect((error as? CocoaError)?.code == .fileWriteOutOfSpace) }
    let result = try await worker.run { 42 }
    #expect(result == 42)
}
