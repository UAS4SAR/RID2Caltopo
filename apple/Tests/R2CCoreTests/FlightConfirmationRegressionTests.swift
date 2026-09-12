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
