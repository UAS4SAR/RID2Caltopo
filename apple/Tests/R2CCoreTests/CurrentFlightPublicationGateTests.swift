import XCTest
@testable import R2CCore

final class CurrentFlightPublicationGateTests: XCTestCase {
    func testUnconfirmedAndIgnoredNeverPublish() {
        XCTAssertEqual(CurrentFlightPublicationGate.decide(confirmed: false, ignored: false), .gatedUnconfirmed)
        XCTAssertEqual(CurrentFlightPublicationGate.decide(confirmed: false, ignored: true), .gatedIgnored)
        XCTAssertFalse(CurrentFlightPublicationGate.decide(confirmed: false, ignored: false).allowsPublication)
        XCTAssertFalse(CurrentFlightPublicationGate.decide(confirmed: true, ignored: true).allowsPublication)
    }

    func testConfirmedAllowsPublication() {
        let allowed = CurrentFlightPublicationGate.decide(confirmed: true, ignored: false)
        XCTAssertEqual(allowed, .allowed)
        XCTAssertTrue(allowed.allowsPublication)
        XCTAssertEqual(allowed.logLabel, "allowed")
    }

    func testQuickReturnWithoutPublisherRequiresNewConfirmationBeforePublish() {
        var guardPolicy = CompletedFlightConfirmationGuard()
        var lifecycle = CurrentFlightConfirmationLifecycle()
        var logs: [String] = []
        let remote = "1581F6Z9C24BH0036EJL"
        // First flight: prompt, confirm, then end with a known publisher session.
        XCTAssertEqual(
            lifecycle.reconcile(orderedRemoteIDs: [remote], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID,
            remote
        )
        guardPolicy.updateSessions([remote: ["1sar7djmn4pr|192.168.68.60:39302"]])
        guardPolicy.end(remoteID: remote, at: Date(timeIntervalSince1970: 1_000)) { logs.append($0) }
        lifecycle.endFlight(remoteID: remote)

        // Same aircraft returns ~51s later with fresh receive time and no publisher session.
        let returnAt = Date(timeIntervalSince1970: 1_051)
        XCTAssertTrue(guardPolicy.allows(remoteID: remote, receivedAt: returnAt) { logs.append($0) })
        XCTAssertTrue(logs.contains(where: { $0.contains("fresh_aircraft") }))
        XCTAssertEqual(
            CurrentFlightPublicationGate.decide(confirmed: false, ignored: false),
            .gatedUnconfirmed
        )
        XCTAssertEqual(
            lifecycle.reconcile(
                orderedRemoteIDs: [remote],
                confirmedRemoteIDs: [],
                ignoredRemoteIDs: []
            ).candidateRemoteID,
            remote
        )
        // Still gated until Confirmed.
        XCTAssertFalse(
            CurrentFlightPublicationGate.decide(confirmed: false, ignored: false).allowsPublication
        )
        XCTAssertTrue(
            CurrentFlightPublicationGate.decide(confirmed: true, ignored: false).allowsPublication
        )
    }
}
