import Foundation
import Testing
@testable import R2CCore

@Test func readinessKeepsDescriptionOnlyPayloadUnknownUntilCompleted() {
    var aircraft = AircraftReadiness()
    aircraft.baseWeightGrams = 1200
    aircraft.accessories = [AircraftAccessory(id: "std", name: "Standard battery", weightGrams: 400, group: "battery"),
                           AircraftAccessory(id: "ext", name: "Extended battery", weightGrams: 600, group: "battery")]
    #expect(aircraft.totalWeight(selected: ["std"], payloadDescription: "water bottle", payloadGrams: nil) == nil)
    #expect(aircraft.totalWeight(selected: ["std"], payloadDescription: "water bottle", payloadGrams: 535) == 2135)
    #expect(aircraft.totalWeight(selected: ["std", "ext"], payloadDescription: "", payloadGrams: nil) == nil)
    #expect(aircraft.totalWeight(selected: ["missing"], payloadDescription: "", payloadGrams: nil) == nil)
    #expect(aircraft.totalWeight(selected: ["std"], payloadDescription: "payload", payloadGrams: .nan) == nil)
}

@Test func aircraftReadinessRoundTripRetainsMeasurementsAndIdentity() throws {
    var aircraft = AircraftReadiness()
    aircraft.recordId = UUID().uuidString
    aircraft.serialNumber = "SERIAL"
    aircraft.registrationNumber = "FA123"
    aircraft.baseWeightGrams = 1200
    aircraft.monitoringEquipment = "Controller ADS-B"
    aircraft.accessories = [AircraftAccessory(name: "Strobe", weightGrams: 30, required: true)]
    #expect(AircraftReadiness.decode(aircraft.dictionary) == aircraft)
    let mapping = OrgConfigRIDMapping(remoteID: "RID", mappedID: "1SAR7", organization: "SAR", model: "Test", owner: "1SAR7", readiness: aircraft)
    #expect(mapping.readiness == aircraft)
    let bundle: [String: Any] = ["format": "rid2caltopo_org_config", "version": 2, "configs": [
        ["type": "ct_ridmap", "map": [["remoteId": "RID", "mappedId": "1SAR7", "org": "SAR", "model": "Test",
            "owner": "1SAR7", "ownerName": "Pilot Example", "ownerCallsign": "1SAR7", "readiness": aircraft.dictionary]]]
    ]]
    let imported = try OrgConfigTokenCodec.parseBundle(JSONSerialization.data(withJSONObject: bundle))
    #expect(imported.mappings.first?.readiness == aircraft)
    #expect(imported.mappings.first?.ownerName == "Pilot Example")
    var flight = FlightReadiness()
    flight.aircraft = aircraft
    flight.payloadDescription = "water bottle"
    #expect(flight.dictionary["payloadWeightGrams"] is NSNull)
    #expect(flight.dictionary["takeoffWeightGrams"] is NSNull)
}

@Test func unresolvedPilotPreservesEnteredCallsignAndDoesNotReusePriorMember() {
    var flight = FlightReadiness()
    flight.pilotJSON = "{\"memberId\":\"prior-pilot\",\"callsign\":\"OLD\"}"
    let reported = flight.withReportedPilot(callsign: " Night Pilot ", matched: false)
    let pilot = reported.dictionary["pilot"] as? [String: Any]
    #expect(pilot?["callsign"] as? String == "Night Pilot")
    #expect(pilot?["memberId"] == nil)
    #expect(reported.dictionary["pilotAttribution"] as? String == "unresolved")
}

@Test func ridMappingEditIgnoresUnrelatedInvalidEntriesButChecksDuplicates() {
    let valid = RidAircraftIdentity(remoteID: "RID1", organization: "SAR", ownerName: "Owner", pilotCallsign: "1SAR7", droneDescription: "Model")
    var readiness = AircraftReadiness()
    readiness.accessories = [AircraftAccessory(name: "")]
    let invalid = RidAircraftIdentity(remoteID: "RID2", organization: "SAR", ownerName: "Legacy", pilotCallsign: "Legacy", droneDescription: "Other", readiness: readiness)
    #expect(RidMappingEditValidation.errors(valid, others: [invalid]).isEmpty)
    #expect(!RidMappingEditValidation.errors(invalid, others: [valid]).isEmpty)
    #expect(RidMappingEditValidation.errors(valid, others: [valid]).contains("Remote ID is already listed."))
    #expect(RidMappingEditValidation.errors(valid, others: [valid]).contains("Model must be unique for this owner callsign."))
}
