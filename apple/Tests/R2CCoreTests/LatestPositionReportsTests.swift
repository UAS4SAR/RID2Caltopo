import Testing
@testable import R2CCore

private actor PositionRecorder {
    var values: [Int] = []
    var times: [ContinuousClock.Instant] = []
    var release: CheckedContinuation<Void, Never>?
    func first() async {
        values.append(1)
        times.append(ContinuousClock.now)
        await withCheckedContinuation { release = $0 }
    }
    func record(_ value: Int) {
        values.append(value)
        times.append(ContinuousClock.now)
    }
    func unblock() { release?.resume(); release = nil }
}

@Test func latestPositionReportsDropBacklogAndRespectCooldown() async throws {
    let queue = LatestPositionReports(interval: .milliseconds(50))
    let recorder = PositionRecorder()
    let first = Task { try await queue.submit(key: "a") { await recorder.first() } }
    while await recorder.release == nil { await Task.yield() }
    let middle = Task { try await queue.submit(key: "a") { await recorder.record(2) } }
    // Wait until the middle sample occupies the pending slot before superseding it.
    while await queue.pendingCountForTesting == 0 { await Task.yield() }
    let newest = Task { try await queue.submit(key: "a") { await recorder.record(3) } }
    let middleSent = try await middle.value
    #expect(!middleSent)
    let releasedAt = ContinuousClock.now
    await recorder.unblock()
    #expect(try await first.value)
    #expect(try await newest.value)
    #expect(await recorder.values == [1, 3])
    let times = await recorder.times
    #expect(releasedAt.duration(to: times[1]) >= .milliseconds(50))
}

@Test func latestPositionReportsCancelPendingBeforeFinalShape() async throws {
    let queue = LatestPositionReports(interval: .seconds(5))
    let recorder = PositionRecorder()
    #expect(try await queue.submit(key: "a") { await recorder.record(1) })
    let pending = Task { try await queue.submit(key: "a") { await recorder.record(2) } }
    while await queue.pendingCountForTesting == 0 { await Task.yield() }
    await queue.cancel(key: "a")
    #expect(try await pending.value == false)
    #expect(await recorder.values == [1])
}
