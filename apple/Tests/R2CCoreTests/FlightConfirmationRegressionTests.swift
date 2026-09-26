import XCTest
@testable import R2CCore

final class FlightConfirmationRegressionTests: XCTestCase {
    func testLocalBaseConfigurationSurvivesOlderPublishedRosterAndMatchesTypedPilot() {
        var local = AircraftReadiness(); local.baseWeightGrams = 297; local.registrationNumber = "FA-EXAMPLE"
        let roster: [[String: Any]] = [["memberId": "pilot", "callsign": "1sar7", "eligible": false]]
        let confirmed = FlightReadiness().withAircraft(local: local, published: AircraftReadiness()).resolvingPilot(callsign: "1SAR7", roster: roster)
        XCTAssertEqual(confirmed.aircraft.registrationNumber, "FA-EXAMPLE")
        XCTAssertEqual(confirmed.dictionary["takeoffWeightGrams"] as? Double, 297)
        XCTAssertEqual((confirmed.dictionary["pilot"] as? [String: Any])?["memberId"] as? String, "pilot")
        XCTAssertEqual((confirmed.dictionary["pilot"] as? [String: Any])?["eligible"] as? Bool, false)
        XCTAssertNil((confirmed.resolvingPilot(callsign: "UNKNOWN", roster: roster).dictionary["pilot"] as? [String: Any])?["memberId"])
    }
    func testBVLOSIsAvailableWithoutInventingAuthority() {
        let profile = OperatingProfiles.choices([:]).first { $0["id"] as? String == "bvlos-pending" }
        XCTAssertEqual(profile?["authorityType"] as? String, "unresolved")
    }
    func testLegacyFallbackCleanupKeepsEquipmentAndActualOwnerEdits() {
        var equipment = AircraftReadiness(); equipment.baseWeightGrams = 297
        let source: [String: Any] = ["owner": "1sar7", "mappedId": "1sar7DjMn4Pr"]
        let accidental = RidAircraftIdentity(remoteID: "RID", organization: "SAR", ownerName: "1sar7", pilotCallsign: "1sar7", droneDescription: "DJI Mini 4 Pro", readiness: equipment)
        XCTAssertEqual(accidental.preservingBlankPublishedOwnerFields(source).ownerName, "")
        XCTAssertEqual(accidental.preservingBlankPublishedOwnerFields(source).readiness.baseWeightGrams, 297)
        let edited = RidAircraftIdentity(remoteID: "RID", organization: "SAR", ownerName: "Ken Taylor", pilotCallsign: "1sar7", droneDescription: "DJI Mini 4 Pro", readiness: equipment)
        XCTAssertEqual(edited.preservingBlankPublishedOwnerFields(source).ownerName, "Ken Taylor")
        let guessed = RidAircraftIdentity(remoteID: "RID", organization: "SAR", pilotCallsign: "1sar62Mn4Pr", droneDescription: "DJI Mini 4 Pro", mappedIDOverride: "1sar62Mn4Pr")
        XCTAssertEqual(guessed.preservingBlankPublishedOwnerFields(["mappedId": "1sar62Mn4Pr", "owner": ""]).pilotCallsign, "")
    }
}

extension FlightConfirmationRegressionTests {
    func testCompletedFlightEmptySnapshotDoesNotReopenConfirmation() {
        var lifecycle = CurrentFlightConfirmationLifecycle()
        XCTAssertEqual(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID, "M4TD")
        XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: ["M4TD"], ignoredRemoteIDs: []).candidateRemoteID)
        // RTMP has stopped; telemetry aging ends the track and clears its decision.
        lifecycle.endFlight(remoteID: "M4TD")
        // The @Published callback must supply its incoming empty snapshot, not
        // the pre-assignment property that still contains the completed track.
        for _ in 0..<3 {
            XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: [], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID)
        }
        // A real subsequent flight still needs a new operator decision.
        XCTAssertEqual(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID, "M4TD")
    }

    func testExplicitTelemetryEndAllowsNewConfirmationWithVideoStillListed() {
        var lifecycle = CurrentFlightConfirmationLifecycle()
        XCTAssertEqual(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID, "M4TD")
        XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: ["M4TD"], ignoredRemoteIDs: []).candidateRemoteID)
        lifecycle.endFlight(remoteID: "M4TD")
        // The confirmation store clears its saved decision when explicitly ending the track.
        XCTAssertEqual(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID, "M4TD")
        XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID)
        lifecycle.endFlight(remoteID: "M4TD")
        XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: ["M4TD"], confirmedRemoteIDs: [], ignoredRemoteIDs: ["M4TD"]).candidateRemoteID)
    }

    func testIgnorePersistsAcrossFlightsForAppSession() {
        var lifecycle = CurrentFlightConfirmationLifecycle()
        var ignored: Set<String> = []
        XCTAssertEqual(lifecycle.reconcile(orderedRemoteIDs: ["MINI"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored).candidateRemoteID, "MINI")
        ignored.insert("MINI")
        // A continuing video stream keeps the aircraft active despite a RID gap.
        XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: ["MINI"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored).candidateRemoteID)
        let ended = lifecycle.reconcile(orderedRemoteIDs: [], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored)
        XCTAssertEqual(ended.endedRemoteIDs, ["MINI"])
        XCTAssertNil(lifecycle.reconcile(orderedRemoteIDs: ["MINI"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored).candidateRemoteID)
    }

    func testStandalonePeerConfirmationCannotSuppressLocalFlightPrompt() {
        var lifecycle = CurrentFlightConfirmationLifecycle()
        for mapID in ["", "  "] {
            XCTAssertFalse(CurrentFlightConfirmationLifecycle.acceptsPeerConfirmation(mapID: mapID))
            lifecycle.reset()
            let peerIDs: Set<String> = CurrentFlightConfirmationLifecycle.acceptsPeerConfirmation(mapID: mapID) ? ["MATRICE"] : []
            XCTAssertEqual(lifecycle.reconcile(orderedRemoteIDs: ["MATRICE"], confirmedRemoteIDs: peerIDs, ignoredRemoteIDs: []).candidateRemoteID, "MATRICE")
        }
        XCTAssertTrue(CurrentFlightConfirmationLifecycle.acceptsPeerConfirmation(mapID: "MAP1"))
    }
}
