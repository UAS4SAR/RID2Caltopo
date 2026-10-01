import XCTest
@testable import R2CCore
import Foundation

private actor AuditCookieGate {
    private var entered = false
    private var waiter: CheckedContinuation<Void, Never>?
    private var release: CheckedContinuation<String?, Never>?
    func read() async -> String? {
        entered = true
        waiter?.resume(); waiter = nil
        return await withCheckedContinuation { release = $0 }
    }
    func waitForRead() async {
        if entered { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func finish() { release?.resume(returning: "audit=fake-cookie-only"); release = nil }
}

final class CaltopoPersonalRevocationTests: XCTestCase {
    func testClearCancelsDispatchedTransport() async throws {
        let registry = CaltopoPersonalSessions()
        let id = UUID()
        let url = CaltopoPersonalProbe.endpoint("ABC123")!
        let started = expectation(description: "Transport started")
        let stopped = expectation(description: "Transport cancelled")
        PendingPersonalRequest.events.configure(started: started, stopped: stopped)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PendingPersonalRequest.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        await registry.register(id) { _ in "fake=test" }
        let pending = Task { try await registry.perform(id, cookieURL: url, request: URLRequest(url: url), session: session, sendCookie: true) }
        await fulfillment(of: [started], timeout: 2)
        await registry.clear()
        do { _ = try await pending.value; XCTFail("Cleared transport succeeded") } catch { }
        await fulfillment(of: [stopped], timeout: 2)
    }
    func testRevocationDuringAuthorizationNeverDispatches() async throws {
        let registry = CaltopoPersonalSessions()
        let gate = AuditCookieGate()
        let id = UUID()
        let url = CaltopoPersonalProbe.endpoint("ABC123")!
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UnexpectedPersonalRequest.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        await registry.register(id) { _ in await gate.read() }
        let pending = Task { try await registry.perform(id, cookieURL: url, request: URLRequest(url: url), session: session, sendCookie: true) }
        await gate.waitForRead()
        await registry.clear()
        await gate.finish()
        do { _ = try await pending.value; XCTFail("Revoked request dispatched") } catch { }
    }
    func testPendingCookieRejectedAfterClear() async throws {
        let registry = CaltopoPersonalSessions()
        let gate = AuditCookieGate()
        let id = UUID()
        await registry.register(id) { _ in await gate.read() }
        let pending = Task { try await registry.cookie(id, url: CaltopoPersonalProbe.endpoint("ABC123")!) }
        await gate.waitForRead()
        await registry.clear()
        await gate.finish()
        do { _ = try await pending.value; XCTFail("Revoked lookup returned cookies") } catch { }
    }
    func testReplacingSameHandleRejectsOldPendingCookie() async throws {
        let registry = CaltopoPersonalSessions()
        let gate = AuditCookieGate()
        let id = UUID()
        await registry.register(id) { _ in await gate.read() }
        let pending = Task { try await registry.cookie(id, url: CaltopoPersonalProbe.endpoint("ABC123")!) }
        await gate.waitForRead()
        await registry.register(id) { _ in "new=fake" }
        await gate.finish()
        do { _ = try await pending.value; XCTFail("Replaced grant returned old cookies") } catch { }
        let cookie = try await registry.cookie(id, url: CaltopoPersonalProbe.endpoint("ABC123")!)
        XCTAssertEqual(cookie, "new=fake")
    }
    func testStaleCatalogCannotPublishAfterAccountABA() async throws {
        let registry = CaltopoPersonalSessions()
        let epoch = await registry.epoch()
        await registry.clear() // A -> B
        await registry.clear() // B -> A: same identity is still a new session
        let id = UUID()
        let accepted = await registry.register(id, epoch: epoch) { _ in "old=fake" }
        XCTAssertFalse(accepted)
        do { _ = try await registry.cookie(id, url: CaltopoPersonalProbe.endpoint("ABC123")!); XCTFail("Stale catalog grant accepted") } catch { }
    }
}

private final class UnexpectedPersonalRequest: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTFail("Transport must not run after clear during cookie lookup")
        client?.urlProtocol(self, didFailWithError: URLError(.cancelled))
    }
    override func stopLoading() {}
}

private final class PersonalTransportEvents: @unchecked Sendable {
    private let lock = NSLock()
    private var started: XCTestExpectation?
    private var stopped: XCTestExpectation?
    func configure(started: XCTestExpectation, stopped: XCTestExpectation) {
        lock.lock(); defer { lock.unlock() }
        self.started = started; self.stopped = stopped
    }
    func start() { lock.lock(); defer { lock.unlock() }; started?.fulfill(); started = nil }
    func stop() { lock.lock(); defer { lock.unlock() }; stopped?.fulfill(); stopped = nil }
}
private final class PendingPersonalRequest: URLProtocol, @unchecked Sendable {
    static let events = PersonalTransportEvents()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { Self.events.start() }
    override func stopLoading() { Self.events.stop() }
}
