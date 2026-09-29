import XCTest
@testable import R2CCore

final class PendingFlightConfirmationTests: XCTestCase {
    func testUnansweredPanelIsRetiredWithFlightAndCannotSurviveReturnToScreen() {
        var lifecycle = CurrentFlightConfirmationLifecycle()
        var panel = PendingFlightConfirmation()
        panel.present(remoteID: lifecycle.reconcile(orderedRemoteIDs: ["MINI"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID)
        XCTAssertEqual(panel.remoteID, "MINI")
        lifecycle.endFlight(remoteID: "MINI")
        XCTAssertTrue(panel.endFlight(remoteID: "MINI"))
        // Navigation/foreground restoration reads the retained presentation state.
        XCTAssertNil(panel.remoteID)
        XCTAssertFalse(panel.endFlight(remoteID: "MINI"))
        XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: [], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID)
        // A genuinely new flight is still confirmable, even with the same Remote ID.
        panel.present(remoteID: lifecycle.reconcile(orderedRemoteIDs: ["MINI"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID)
        XCTAssertEqual(panel.remoteID, "MINI")
    }

    func testAnotherFlightEndingDoesNotDismissActiveOperatorConfirmation() {
        var panel = PendingFlightConfirmation()
        panel.present(remoteID: "ACTIVE")
        XCTAssertFalse(panel.endFlight(remoteID: "OTHER"))
        XCTAssertEqual(panel.remoteID, "ACTIVE")
        panel.present(remoteID: nil)
        XCTAssertNil(panel.remoteID)
    }

    func testVideoOnlyReconciliationRetiresMatchingPresentation() {
        var lifecycle = CurrentFlightConfirmationLifecycle()
        var panel = PendingFlightConfirmation()
        panel.present(remoteID: lifecycle.reconcile(orderedRemoteIDs: ["VIDEO"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID)
        let result = lifecycle.reconcile(orderedRemoteIDs: [], confirmedRemoteIDs: [], ignoredRemoteIDs: [])
        for ended in result.endedRemoteIDs { panel.endFlight(remoteID: ended) }
        XCTAssertNil(panel.remoteID)
        XCTAssertNil(result.candidateRemoteID)
    }
}
