import Foundation
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
    // Even a custom subsecond interval must obey the hard send floor.
    #expect(releasedAt.duration(to: times[1]) >= .seconds(1))
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

@Test func liveTrackIntervalUsesThumbnailPreferenceAndChangesWhilePending() async throws {
    let suite = "LiveTrackIntervalTests-" + UUID().uuidString
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let key = OperationalThumbnailRefreshInterval.storageKey
    let queue = LatestPositionReports(intervalProvider: {
        .seconds(OperationalThumbnailRefreshInterval.normalized(
            UserDefaults(suiteName: suite)!.object(forKey: OperationalThumbnailRefreshInterval.storageKey) as? Double))
    })
    let recorder = PositionRecorder()
    defaults.set(60.0, forKey: key)
    #expect(try await queue.submit(key: "a") { await recorder.record(1) })
    let pending = Task { try await queue.submit(key: "a") { await recorder.record(2) } }
    while await queue.pendingCountForTesting == 0 { await Task.yield() }
    defaults.set(0.5, forKey: key)
    #expect(try await pending.value)
    let times = await recorder.times
    // The saved 0.5-second thumbnail preference cannot accelerate position sends.
    #expect(times[0].duration(to: times[1]) >= .seconds(1))
    #expect(times[0].duration(to: times[1]) < .seconds(5))
    #expect(await recorder.values == [1, 2])
}

@Test func failedPositionSendStillEnforcesOneSecondFloor() async throws {
    struct SimulatedFailure: Error {}
    let queue = LatestPositionReports(interval: .zero)
    let recorder = PositionRecorder()
    do {
        _ = try await queue.submit(key: "a") {
            await recorder.record(1)
            throw SimulatedFailure()
        }
        Issue.record("Expected simulated failure")
    } catch is SimulatedFailure { }
    let failedAt = ContinuousClock.now
    #expect(try await queue.submit(key: "a") { await recorder.record(2) })
    #expect(await recorder.values == [1, 2])
    let times = await recorder.times
    #expect(times[0].duration(to: times[1]) >= .seconds(1))
    // The timing above uses the actual attempt, including the failed attempt.
    #expect(failedAt.duration(to: times[1]) < .seconds(5))
}
