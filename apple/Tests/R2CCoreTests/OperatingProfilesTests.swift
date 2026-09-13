import XCTest
@testable import R2CCore

final class OperatingProfilesTests: XCTestCase {
    func testIncidentBriefingReuseIsScopedAndDoesNotRewriteSnapshots() {
        var assignment = OperatingProfileAssignment()
        assignment.setScope(organization: "org", incident: "map-a")
        let briefing = IncidentBriefing(incidentName: "Search 26-30", notes: "VO at trailhead\nRTH briefed")
        assignment.remember(OperatingProfiles.standard, briefing: briefing)
        let id = assignment.assignmentID
        let snapshot = OperatingProfiles.snapshot(OperatingProfiles.standard, state: [:], pilotID: "", aircraftID: "",
            incidentID: "map-a", assignmentID: id, managed: false, checked: [], incidentBriefing: assignment.incidentBriefing, organizationScope: "org")
        assignment.setScope(organization: "org", incident: "map-a")
        XCTAssertEqual(assignment.incidentBriefing, briefing)
        XCTAssertEqual(assignment.assignmentID, id)
        XCTAssertEqual(IncidentBriefing.currentFlight(snapshot, confirmed: true, organization: "org", incident: "map-a"), briefing)
        XCTAssertNil(IncidentBriefing.currentFlight(snapshot, confirmed: false, organization: "org", incident: "map-a"))
        XCTAssertNil(IncidentBriefing.currentFlight(snapshot, confirmed: true, organization: "other", incident: "map-a"))
        XCTAssertNil(IncidentBriefing.currentFlight(snapshot, confirmed: true, organization: "org", incident: "map-b"))
        assignment.setScope(organization: "org", incident: "map-b")
        XCTAssertEqual(assignment.incidentBriefing, IncidentBriefing())
        assignment.setScope(organization: "org", incident: "map-a")
        XCTAssertEqual(assignment.incidentBriefing, IncidentBriefing())
        assignment.remember(OperatingProfiles.standard, briefing: briefing)
        assignment.end()
        XCTAssertEqual(assignment.incidentBriefing, IncidentBriefing())
        XCTAssertEqual((snapshot["incidentBriefing"] as? [String: Any])?["notes"] as? String, briefing.notes)
        XCTAssertNil((snapshot["profile"] as? [String: Any])?["incidentBriefing"])
    }
    func testIncidentNotesOnlyChangeRetainsHistoryAndCanBeCleared() {
        func flight(_ briefing: IncidentBriefing) -> FlightReadiness {
            var result = FlightReadiness()
            result.operatingProfileJSON = OperatingProfiles.json(OperatingProfiles.snapshot(OperatingProfiles.standard,
                state: [:], pilotID: "", aircraftID: "", incidentID: "map", assignmentID: "assignment", managed: false,
                checked: [], incidentBriefing: briefing, organizationScope: "org"))
            return result
        }
        let original = flight(IncidentBriefing(incidentName: "Search", notes: "Initial briefing"))
        let revised = flight(IncidentBriefing(incidentName: "Search", notes: "New VO"))
        let changed = OperatingProfiles.retainingHistory(previous: original, next: revised)
        XCTAssertEqual(changed.operatingProfileJSON, original.operatingProfileJSON)
        XCTAssertEqual((OperatingProfiles.active(changed)["incidentBriefing"] as? [String: Any])?["notes"] as? String, "New VO")
        XCTAssertEqual((changed.dictionary["operatingProfileChanges"] as? [[String: Any]])?.count, 1)
        XCTAssertEqual(OperatingProfiles.retainingHistory(previous: changed, next: revised).operatingProfileChangesJSON, changed.operatingProfileChangesJSON)
        let cleared = OperatingProfiles.retainingHistory(previous: changed, next: flight(IncidentBriefing()))
        XCTAssertEqual((cleared.dictionary["operatingProfileChanges"] as? [[String: Any]])?.count, 2)
        XCTAssertEqual(IncidentBriefing(incidentName: String(repeating: "a", count: 170), notes: String(repeating: "b", count: 4100)).notes.count, 4000)
    }

    func testSavedChoiceUsesCurrentCatalogAndKeepsMissingChoiceVisible() {
        XCTAssertEqual(OperatingProfiles.preferredProfile([:], savedID: "bvlos-pending")["id"] as? String, "bvlos-pending")
        let state: [String: Any] = ["operatingProfiles": ["profiles": [["id": "waiver", "version": 2]]]]
        XCTAssertEqual(OperatingProfiles.preferredProfile(state, savedID: "waiver")["version"] as? Int, 2)
        XCTAssertEqual(OperatingProfiles.preferredProfile([:], savedID: "waiver")["missingProfileId"] as? String, "waiver")
        XCTAssertEqual(OperatingProfiles.preferredProfile([:], savedID: nil)["id"] as? String, "standard-part-107")
        XCTAssertNotEqual(OperatingProfiles.preferenceKey(organization: "org-a"), OperatingProfiles.preferenceKey(organization: "org-b"))
        XCTAssertEqual(OperatingProfiles.preferenceKey(organization: "org-a/"), OperatingProfiles.preferenceKey(organization: "org-a"))
        let suite = "OperatingProfileTests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("bvlos-pending", forKey: OperatingProfiles.preferenceKey(organization: "org-a"))
        let reopened = UserDefaults(suiteName: suite)!
        XCTAssertEqual(OperatingProfiles.preferredProfile([:], savedID: reopened.string(forKey: OperatingProfiles.preferenceKey(organization: "org-a")))["id"] as? String, "bvlos-pending")
        XCTAssertNil(reopened.string(forKey: OperatingProfiles.preferenceKey(organization: "org-b")))
    }
    func testDefaultsAndMissingSelection() {
        XCTAssertEqual(OperatingProfiles.defaultProfile([:])["id"] as? String, "standard-part-107")
        let state: [String: Any] = ["operatingProfiles": ["defaultProfileId": "missing", "profiles": []]]
        XCTAssertEqual(OperatingProfiles.defaultProfile(state)["missingProfileId"] as? String, "missing")
    }
    func testAssignmentClearsOnScopeChangeWithoutResurrection() {
        var assignment = OperatingProfileAssignment()
        assignment.setScope(organization: "org", incident: "one")
        assignment.remember(OperatingProfiles.other)
        let id = assignment.assignmentID
        assignment.setScope(organization: "org", incident: "one")
        XCTAssertEqual(id, assignment.assignmentID)
        assignment.setScope(organization: "org", incident: "two")
        assignment.setScope(organization: "org", incident: "one")
        XCTAssertNil(assignment.profileJSON)
        assignment.remember(OperatingProfiles.other)
        assignment.setScope(organization: "other-org", incident: "one")
        XCTAssertTrue(assignment.assignmentID.isEmpty)
    }
    func testAdvisoriesNeverPreventSnapshot() {
        let profile: [String: Any] = ["id": "waiver", "version": 1, "authorityType": "part107_waiver", "effectiveUntil": "2025-01-01", "pilotIds": ["different"]]
        let issues = OperatingProfiles.warnings(profile, state: [:], pilotID: "pilot", aircraftID: "aircraft", incidentID: "incident", managed: true, now: ISO8601DateFormatter().date(from: "2026-09-11T12:00:00Z")!)
        XCTAssertTrue(issues.contains { $0.contains("effective dates") })
        XCTAssertTrue(issues.contains { $0.contains("stale") })
        XCTAssertTrue(issues.contains { $0.contains("applicability") })
        XCTAssertNotNil(OperatingProfiles.snapshot(profile, state: [:], pilotID: "", aircraftID: "", incidentID: "", assignmentID: "", managed: true, checked: [])["profile"])
    }
    func testHistoryRetainsOriginalAndDoesNotDuplicateUnchangedSelection() throws {
        var first = FlightReadiness(); first.operatingProfileJSON = OperatingProfiles.json(["profile": OperatingProfiles.standard])
        var second = FlightReadiness(); second.operatingProfileJSON = OperatingProfiles.json(["profile": OperatingProfiles.other])
        let changed = OperatingProfiles.retainingHistory(previous: first, next: second)
        XCTAssertEqual(changed.operatingProfileJSON, first.operatingProfileJSON)
        XCTAssertEqual((OperatingProfiles.active(changed)["profile"] as? [String: Any])?["id"] as? String, "other-pending")
        let unchanged = OperatingProfiles.retainingHistory(previous: changed, next: second)
        XCTAssertEqual(changed.operatingProfileChangesJSON, unchanged.operatingProfileChangesJSON)
        let returned = OperatingProfiles.retainingHistory(previous: changed, next: first)
        XCTAssertEqual((returned.dictionary["operatingProfileChanges"] as? [[String: Any]])?.count, 2)
        let encoded = try JSONEncoder().encode(returned)
        XCTAssertEqual(try JSONDecoder().decode(FlightReadiness.self, from: encoded), returned)
        let legacy = try JSONEncoder().encode(FlightReadiness())
        XCTAssertNil(try JSONDecoder().decode(FlightReadiness.self, from: legacy).operatingProfileJSON)
    }
    func testLaterBriefingAndLegacyUpdateRetainTimeline() {
        var first = FlightReadiness(); first.operatingProfileJSON = OperatingProfiles.json(["profile": OperatingProfiles.standard, "checkedConditions": []])
        var second = FlightReadiness(); second.operatingProfileJSON = OperatingProfiles.json(["profile": OperatingProfiles.standard, "checkedConditions": [0]])
        let changed = OperatingProfiles.retainingHistory(previous: first, next: second)
        XCTAssertEqual((changed.dictionary["operatingProfileChanges"] as? [[String: Any]])?.count, 1)
        let legacyUpdate = OperatingProfiles.retainingHistory(previous: changed, next: FlightReadiness())
        XCTAssertEqual(legacyUpdate.operatingProfileJSON, first.operatingProfileJSON)
        XCTAssertEqual(legacyUpdate.operatingProfileChangesJSON, changed.operatingProfileChangesJSON)
    }
    func testOlderPeerIdentityCannotEraseLocalProfileSnapshot() {
        var readiness = FlightReadiness(); readiness.operatingProfileJSON = OperatingProfiles.json(["profile": OperatingProfiles.other])
        let local = RidAircraftIdentity(remoteID: "RID", organization: "org", pilotCallsign: "RPIC", droneDescription: "drone", flightReadiness: readiness)
        let peer = RidAircraftIdentity(remoteID: "RID", organization: "org", pilotCallsign: "Updated name", droneDescription: "drone")
        let merged = peer.preservingLocalFlightReadiness(from: local)
        XCTAssertEqual(merged.pilotCallsign, "Updated name")
        XCTAssertEqual(merged.flightReadiness, readiness)
        let unrelated = RidAircraftIdentity(remoteID: "OTHER", organization: "org", pilotCallsign: "RPIC", droneDescription: "drone")
        XCTAssertNil(unrelated.preservingLocalFlightReadiness(from: local).flightReadiness)
    }
}
