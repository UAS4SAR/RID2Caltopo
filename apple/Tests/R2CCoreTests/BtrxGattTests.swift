import XCTest
@testable import R2CCore

/// Swift copy of Android's BtrxGattTest.kt and BtrxLinkPolicyTest.kt (backoff).
final class BtrxGattTests: XCTestCase {
    var started: [String] = []
    var failed: [String] = []
    var refuse: String?

    func makeQueue() -> BtrxGattQueue<String> {
        BtrxGattQueue<String>(start: { [unowned self] op in self.started.append(op); return op != self.refuse }, onStartFailed: { [unowned self] in self.failed.append($0) })
    }

    func testOnlyOneOperationIsInFlightAndOrderIsKept() {
        let q = makeQueue()
        q.enqueue("mtu"); q.enqueue("discover"); q.enqueue("notify")
        XCTAssertEqual(["mtu"], started)
        XCTAssertEqual(3, q.count)
        XCTAssertEqual("mtu", q.complete())
        XCTAssertEqual(["mtu", "discover"], started)
        q.complete(); q.complete()
        XCTAssertEqual(["mtu", "discover", "notify"], started)
        XCTAssertNil(q.inFlight)
        XCTAssertEqual(0, q.count)
    }

    func testHeartbeatQueuedDuringACommandWaitsForIt() {
        let q = makeQueue()
        q.enqueue("write:PAIR_CONFIRM")
        q.enqueue("write:heartbeat")
        XCTAssertEqual(["write:PAIR_CONFIRM"], started)
        q.complete()
        XCTAssertEqual(["write:PAIR_CONFIRM", "write:heartbeat"], started)
    }

    func testLateCallbackWithNothingInFlightIsHarmless() {
        let q = makeQueue()
        XCTAssertNil(q.complete())
        q.enqueue("read"); q.complete()
        XCTAssertNil(q.complete())
        XCTAssertEqual(["read"], started)
    }

    func testRefusedStartIsReportedAndDoesNotRunTheNextOp() {
        refuse = "write"
        let q = makeQueue()
        q.enqueue("write"); q.enqueue("read")
        XCTAssertEqual(["write"], failed)
        XCTAssertEqual(["write"], started)
        XCTAssertNil(q.inFlight)
    }

    func testClearDropsPendingAndIgnoresLaterOps() {
        let q = makeQueue()
        q.enqueue("a"); q.enqueue("b")
        q.clear()
        q.enqueue("c")
        XCTAssertNil(q.complete())
        XCTAssertEqual(["a"], started)
    }

    func testDrainReadsUntilEmpty() {
        var reads = 0
        let d = BtrxFromRadioDrain { reads += 1 }
        d.kick()
        XCTAssertEqual(1, reads)
        XCTAssertTrue(d.onRead([1]))
        XCTAssertTrue(d.onRead([2]))
        XCTAssertEqual(3, reads)
        XCTAssertFalse(d.onRead([]))
        XCTAssertEqual(3, reads)
        XCTAssertFalse(d.draining)
    }

    func testNotifyDuringADrainAddsOnePass() {
        var reads = 0
        let d = BtrxFromRadioDrain { reads += 1 }
        d.kick(); d.kick()
        XCTAssertEqual(1, reads)
        XCTAssertTrue(d.onRead([1]))
        XCTAssertFalse(d.onRead([]))
        XCTAssertEqual(3, reads)
        XCTAssertTrue(d.draining)
        XCTAssertFalse(d.onRead([]))
        XCTAssertFalse(d.draining)
    }

    func testDrainAndQueueTogetherNeverOverlapAReadWithAWrite() {
        var ops: [String] = []
        let q = BtrxGattQueue<String>(start: { ops.append("start \($0)"); return true }, onStartFailed: { _ in })
        let d = BtrxFromRadioDrain { q.enqueue("read") }
        q.enqueue("write want_config")
        d.kick()
        q.enqueue("write heartbeat")
        XCTAssertEqual(["start write want_config"], ops)
        q.complete()
        XCTAssertEqual("read", q.inFlight)
        q.complete(); _ = d.onRead([9])
        XCTAssertEqual("write heartbeat", q.inFlight)
        q.complete()
        XCTAssertEqual("read", q.inFlight)
        q.complete(); _ = d.onRead([])
        XCTAssertNil(q.inFlight)
        XCTAssertEqual(["start write want_config", "start read", "start write heartbeat", "start read"], ops)
    }

    func testBackoffDoublesToThirtySecondsAndResets() {
        var b = BtrxReconnectBackoff()
        XCTAssertEqual([2_000, 4_000, 8_000, 16_000, 30_000, 30_000], (0..<6).map { _ in b.nextDelayMs() })
        b.reset()
        XCTAssertEqual(2_000, b.nextDelayMs())
    }
}
