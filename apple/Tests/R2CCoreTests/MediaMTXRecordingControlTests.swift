import Foundation
import Testing
@testable import R2CCore

private actor Calls {
    var applied: [MediaMTXRecordingState] = []
    var results: [Bool] = []
    var disableFailures = 0
    var sleeps: [UInt64] = []
    init(results: [Bool] = []) { self.results = results }
    func next(_ state: MediaMTXRecordingState) -> Bool {
        applied.append(state)
        return results.isEmpty ? true : results.removeFirst()
    }
    func failedDisable() { disableFailures += 1 }
    func slept(_ ms: UInt64) { sleeps.append(ms) }
}

@Suite("MediaMTX recording control (P5)")
struct MediaMTXRecordingControlTests {
    let root = URL(fileURLWithPath: "/tmp/r2c-captures")

    private func reconciler(_ calls: Calls) -> MediaMTXRecordingReconciler {
        MediaMTXRecordingReconciler(
            apply: { await calls.next($0) },
            sleep: { await calls.slept($0) },
            onDisableFailed: { await calls.failedDisable() }
        )
    }

    @Test func patchBodyCarriesRecordPathAndFormat() throws {
        let body = MediaMTXRecordingAPI.patchBody(MediaMTXRecordingState(record: true, recordingRoot: root))
        let json = try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        #expect(json["record"] as? Bool == true)
        #expect(json["recordPath"] as? String == "/tmp/r2c-captures/%Y-%m-%d/%path/%path_%Y-%m-%d_%H-%M-%S-%f")
        #expect(json["recordFormat"] as? String == "fmp4")
        #expect(json["recordDeleteAfter"] as? String == "0s")
    }

    @Test func requestIsLocalPatch() {
        let request = MediaMTXRecordingAPI.request(MediaMTXRecordingState(record: false, recordingRoot: nil))
        #expect(request.httpMethod == "PATCH")
        #expect(request.url?.absoluteString == "http://127.0.0.1:9997/v3/config/pathdefaults/patch")
    }

    @Test func runtimeConfigWritesRecordSettingsButHoldsRecordingOff() throws {
        let base = Data("pathDefaults:\n  record: no\n".utf8)
        let held = String(decoding: try MediaMTXRuntimeConfiguration.build(
            base: base, captureStreams: true, recordingRoot: root, recordNow: false), as: UTF8.self)
        #expect(held.contains("record: no"))
        #expect(held.contains("recordPath: '/tmp/r2c-captures/%Y-%m-%d/"))
        let live = String(decoding: try MediaMTXRuntimeConfiguration.build(
            base: base, captureStreams: true, recordingRoot: root), as: UTF8.self)
        #expect(live.contains("record: yes"))
    }

    @Test func storageClearingTurnsRecordingOnWithoutRestart() async {
        let calls = Calls()
        let r = reconciler(calls)
        await r.serverStarted(configured: .init(record: false, recordingRoot: root),
                              captureEnabled: true, storageBlocked: true, recordingRoot: root)
        #expect(await calls.applied.isEmpty)
        await r.updateStorageBlocked(false)
        #expect(await calls.applied == [.init(record: true, recordingRoot: root)])
        #expect(await r.appliedState?.record == true)
    }

    @Test func overLimitTurnsRecordingOffPromptly() async {
        let calls = Calls()
        let r = reconciler(calls)
        await r.serverStarted(configured: .init(record: true, recordingRoot: root),
                              captureEnabled: true, storageBlocked: false, recordingRoot: root)
        await r.updateStorageBlocked(true)
        #expect(await calls.applied.map(\.record) == [false])
        #expect(await calls.sleeps.isEmpty)
        #expect(await calls.disableFailures == 0)
    }

    @Test func disableRetriesThenFallsBackToRestart() async {
        let calls = Calls(results: Array(repeating: false, count: 8))
        let r = reconciler(calls)
        await r.serverStarted(configured: .init(record: true, recordingRoot: root),
                              captureEnabled: true, storageBlocked: false, recordingRoot: root)
        await r.updateStorageBlocked(true)
        #expect(await calls.applied.count == 8)
        #expect(await calls.sleeps == [50, 100, 200, 400, 800, 1_600, 3_200])
        #expect(await calls.disableFailures == 1)
    }

    @Test func enableFailureNeverRestarts() async {
        let calls = Calls(results: Array(repeating: false, count: 8))
        let r = reconciler(calls)
        await r.serverStarted(configured: .init(record: false, recordingRoot: root),
                              captureEnabled: true, storageBlocked: true, recordingRoot: root)
        await r.updateStorageBlocked(false)
        #expect(await calls.disableFailures == 0)
        #expect(await r.appliedState?.record == false)
    }

    @Test func nothingIsSentWhileServerStopped() async {
        let calls = Calls()
        let r = reconciler(calls)
        await r.updateStorageBlocked(false)
        await r.updateCaptureEnabled(true)
        #expect(await calls.applied.isEmpty)
    }

    @Test func startEstimateHonoursAllowanceAndReserve() {
        let max: Int64 = 10_000_000_000
        #expect(!AppleFlightStorage.recordingStartSnapshot(used: 1, available: 5_000_000_000, maximum: max, scannedBlocked: false).blocked)
        #expect(AppleFlightStorage.recordingStartSnapshot(used: 9_000_000_001, available: 5_000_000_000, maximum: max, scannedBlocked: false).blocked)
        let low = AppleFlightStorage.recordingStartSnapshot(used: 1, available: FlightStoragePolicy.reserveBytes - 1, maximum: max, scannedBlocked: false)
        #expect(low.blocked && !low.allowanceInsufficient)
        #expect(AppleFlightStorage.recordingStartSnapshot(used: 1, available: nil, maximum: max, scannedBlocked: true).blocked)
    }

    @Test func startEstimateNeverWaitsOnTheWorkQueue() async {
        // The estimate must answer while a sweep holds the storage queue.
        let started = Date()
        let defaults = UserDefaults(suiteName: "r2c-start-estimate-\(UUID().uuidString)")!
        defaults.set(Int64(123), forKey: AppleFlightStorage.estimatedUsedKey)
        let snapshot = AppleFlightStorage.recordingStartEstimate(defaults: defaults)
        #expect(Date().timeIntervalSince(started) < 0.5)
        #expect(snapshot.failures.isEmpty)
    }
}
