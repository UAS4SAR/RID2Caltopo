import XCTest
@testable import R2CCore
import Foundation

private let testMapURL = URL(string: "https://caltopo.com/api/v1/map/ABC123/since/0")!

private final class FullReadClock: @unchecked Sendable {
    private let lock = NSLock()
    private var time: TimeInterval = 0
    private var waits: [TimeInterval] = []
    func now() -> TimeInterval { lock.lock(); defer { lock.unlock() }; return time }
    func advance(_ seconds: TimeInterval) { lock.lock(); defer { lock.unlock() }; time += seconds; waits.append(seconds) }
    func delays() -> [TimeInterval] { lock.lock(); defer { lock.unlock() }; return waits }
    func limiter() -> CaltopoFullMapReads {
        CaltopoFullMapReads(now: { self.now() }, sleep: { seconds in self.advance(seconds); try Task.checkCancellation() })
    }
}
final class CaltopoFullMapReadsTests: XCTestCase {
    func testFirstReadImmediateThenNinetySecondsAfterCompletion() async throws {
        let clock = FullReadClock()
        let gate = clock.limiter()
        let one = try await gate.perform(mapURL: testMapURL) { clock.advance(5); return clock.now() }
        let two = try await gate.perform(mapURL: testMapURL) { clock.now() }
        XCTAssertEqual(one, 5); XCTAssertEqual(two, 95)
        XCTAssertEqual(clock.delays(), [5, 90])
    }
    func testFailedAttemptStillConsumesBudget() async throws {
        let clock = FullReadClock()
        let limiter = clock.limiter()
        do { let _: Int = try await limiter.perform(mapURL: testMapURL) { throw URLError(.timedOut) }; XCTFail("Expected failure") } catch { }
        let next = try await limiter.perform(mapURL: testMapURL) { clock.now() }
        XCTAssertEqual(next, 90)
    }
    func testCancelledWaitNeverDispatchesAndDoesNotHoldGate() async throws {
        let gate = CaltopoFullMapReads()
        _ = try await gate.perform(mapURL: testMapURL) { 1 }
        let pending = Task { try await gate.perform(mapURL: testMapURL) { XCTFail("Cancelled request dispatched"); return 2 } }
        await Task.yield(); pending.cancel()
        do { _ = try await pending.value; XCTFail("Expected cancellation") } catch is CancellationError { }
    }
    func testConcurrentReadersAreSerializedAndSpaced() async throws {
        let gate = CaltopoFullMapReads(interval: 0.02)
        let times = try await withThrowingTaskGroup(of: TimeInterval.self) { group in
            for _ in 0..<3 {
                group.addTask { try await gate.perform(mapURL: testMapURL) {
                    try await Task.sleep(for: .milliseconds(5))
                    return ProcessInfo.processInfo.systemUptime
                } }
            }
            var times = [TimeInterval]()
            for try await time in group { times.append(time) }
            return times.sorted()
        }
        XCTAssertGreaterThanOrEqual(times[1] - times[0], 0.02)
        XCTAssertGreaterThanOrEqual(times[2] - times[1], 0.02)
    }
    func testDifferentMapCanReadWhileAnotherMapIsActive() async throws {
        let gate = CaltopoFullMapReads()
        let pending = Task { try await gate.perform(mapURL: testMapURL) {
            try await Task.sleep(for: .seconds(30))
            return 1
        } }
        await Task.yield()
        let other = URL(string: "https://caltopo.com/api/v1/map/DEF456/since/0")!
        let value = try await gate.perform(mapURL: other) { 2 }
        XCTAssertEqual(value, 2)
        pending.cancel()
        _ = await pending.result
    }
    func testMapKeysIgnoreAccountsButSeparateMapsAndServers() {
        XCTAssertEqual(CaltopoFullMapReads.mapKey(testMapURL),
            CaltopoFullMapReads.mapKey(URL(string: "https://CALTOPO.COM:443/api/v1/map/ABC123/since/0")!))
        XCTAssertNotEqual(CaltopoFullMapReads.mapKey(testMapURL),
            CaltopoFullMapReads.mapKey(URL(string: "https://another.example/api/v1/map/ABC123/since/0")!))
    }
    func testFullReadClassificationExcludesDeltasCatalogsAndWrites() {
        XCTAssertTrue(CaltopoFullMapReads.isFullMapRead(method: "GET", path: "/api/v1/map/ABC123/since/0"))
        for path in ["/api/v1/map/ABC123/since/1000", "/api/v1/acct/USER01/since/0", "/api/v1/media/id", "/api/v1/position/report/key"] {
            XCTAssertFalse(CaltopoFullMapReads.isFullMapRead(method: "GET", path: path))
        }
        XCTAssertFalse(CaltopoFullMapReads.isFullMapRead(method: "POST", path: "/api/v1/map/ABC123/since/0"))
    }
    func testDifferentMapsBypassCooldownWhileSameMapClientsShareIt() async throws {
        let clock = FullReadClock(); let gate = clock.limiter()
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [FullReadTransport.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let one = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "fake", credentialSecretBase64: "dGVzdA=="), session: session, fullMapReads: gate)
        let two = try CaltopoLiveClient(configuration: .init(mapID: "DEF456", credentialID: "fake", credentialSecretBase64: "dGVzdA=="), session: session, fullMapReads: gate)
        _ = try await one.fetchMapArtifacts()
        _ = try await one.fetchMapArtifactChanges(sinceMilliseconds: 1_000)
        XCTAssertEqual(clock.now(), 0)
        _ = try await two.fetchMapArtifacts()
        XCTAssertEqual(clock.now(), 0)
        _ = try await one.fetchMapArtifactChanges(sinceMilliseconds: 0, forceFullRead: true)
        XCTAssertEqual(clock.now(), 90)
    }
    func testMissingPersonalAuthorizationRejectsBeforeCooldown() async throws {
        let clock = FullReadClock(); let gate = clock.limiter()
        _ = try await gate.perform(mapURL: testMapURL) { 1 }
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [NeverFullReadTransport.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "", credentialSecretBase64: "", personalSessionID: UUID()), session: session, fullMapReads: gate)
        do { _ = try await client.fetchMapArtifacts(); XCTFail("Missing personal grant accepted") } catch { }
        XCTAssertEqual(clock.now(), 0) // No request is made and no budget is consumed for an invalid local grant.
    }

    func testSharedStartupSnapshotAvoidsExtraFullReadButManualReloadStillWaits() async throws {
        let clock = FullReadClock(); let gate = clock.limiter(); let snapshots = CaltopoSnapshotReads()
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [FullReadTransport.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let settings = CaltopoLiveConfiguration(mapID: "ABC123", credentialID: "fake", credentialSecretBase64: "dGVzdA==")
        let one = try CaltopoLiveClient(configuration: settings, session: session, fullMapReads: gate, snapshotReads: snapshots)
        let two = try CaltopoLiveClient(configuration: settings, session: session, fullMapReads: gate, snapshotReads: snapshots)
        _ = try await one.fetchMapArtifacts()
        _ = try await two.fetchMapArtifacts()
        XCTAssertEqual(clock.now(), 0)
        _ = try await two.fetchMapArtifactChanges(sinceMilliseconds: 0, forceFullRead: true)
        XCTAssertEqual(clock.now(), 90)
    }

    func testPersonalAuthorizationIsRecheckedAfterCooldown() async throws {
        let token = UUID()
        let epoch = await CaltopoPersonalSessions.shared.epoch()
        await CaltopoPersonalSessions.shared.register(token, epoch: epoch) { _ in "fake-test-cookie" }
        let clock = FullReadClock()
        let gate = CaltopoFullMapReads(now: { clock.now() }, sleep: { seconds in
            clock.advance(seconds)
            await CaltopoPersonalSessions.shared.clear()
        })
        _ = try await gate.perform(mapURL: testMapURL) { 1 }
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [NeverFullReadTransport.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "", credentialSecretBase64: "", personalSessionID: token), session: session, fullMapReads: gate)
        do { _ = try await client.fetchMapArtifacts(); XCTFail("Revoked grant after waiting was accepted") } catch { }
        XCTAssertEqual(clock.now(), 90)
    }
}
private final class FullReadTransport: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let data = Data(#"{"status":"ok","result":{"state":{"type":"FeatureCollection","features":[]}}}"#.utf8)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
private final class NeverFullReadTransport: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { XCTFail("Revoked/missing personal authorization dispatched"); client?.urlProtocol(self, didFailWithError: URLError(.cancelled)) }
    override func stopLoading() {}
}
