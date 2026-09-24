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

@Test func rememberedEquipmentDoesNotReuseFlightEvidence() {
    var previous = FlightReadiness()
    previous.selectedAccessories = ["spotlight", "speaker"]
    previous.payloadDescription = "Water"
    previous.payloadWeightGrams = 500
    previous.pilotJSON = "{\"callsign\":\"OLD\"}"
    previous.confirmedAt = "yesterday"
    let restored = FlightReadiness().restoringEquipment(previous.equipmentDictionary)
    #expect(restored.selectedAccessories == previous.selectedAccessories)
    #expect(restored.payloadDescription == "Water")
    #expect(restored.payloadWeightGrams == 500)
    #expect(restored.pilotJSON == "{}")
    #expect(restored.confirmedAt.isEmpty)
    let cleared = FlightReadiness().restoringEquipment(FlightReadiness().equipmentDictionary)
    #expect(cleared.selectedAccessories.isEmpty)
    #expect(cleared.payloadWeightGrams == nil)
    var catalog = AircraftReadiness()
    catalog.accessories = [AircraftAccessory(id: "speaker", name: "Speaker")]
    #expect(restored.withAircraft(local: catalog, published: nil).selectedAccessories == ["speaker"])
}

@Test func pilotWarningDoesNotRequireTrainingDates() {
    var readiness = FlightReadiness()
    readiness.pilotJSON = "{\"memberId\":\"member\",\"certificateDate\":\"2025-02-28\"}"
    #expect(!readiness.pilotQualificationWarning.contains("Currency date not recorded"))
    readiness.pilotJSON = "{\"callsign\":\"1SAR7\"}"
    #expect(!readiness.pilotQualificationWarning.contains("Currency date not recorded"))
    readiness.pilotJSON = "{\"memberId\":\"member\",\"initialKnowledgeDate\":\"2025-02-01\"}"
    #expect(!readiness.pilotQualificationWarning.contains("Currency date not recorded"))
}

@Test func standaloneRidMappingAcceptsBlankOrganizationButManagedMappingRequiresIt() {
    for organization in ["", "  "] {
        let identity = RidAircraftIdentity(remoteID: "1581F8HGX1234567890", organization: organization,
            pilotCallsign: "1SAR7", droneDescription: "DJI Mini 4 Pro")
        #expect(RidMappingEditValidation.errors(identity, others: [], requireOrganization: false).isEmpty)
        #expect(RidMappingEditValidation.errors(identity, others: [], requireOrganization: true) == ["Organization is required."])
        #expect(identity.organization.isEmpty)
        #expect(identity.mappedID == "1SAR7DjMn4Pr")
    }
}

@Test func standaloneRidMappingStillValidatesAircraftFieldsAndDuplicates() {
    let invalid = RidAircraftIdentity(remoteID: "BAD-RID", organization: "", pilotCallsign: "", droneDescription: "")
    let errors = RidMappingEditValidation.errors(invalid, others: [], requireOrganization: false)
    #expect(errors.contains("Remote ID must contain only A-Z and 0-9."))
    #expect(errors.contains("Pilot callsign or name is required."))
    #expect(errors.contains("Model is required."))
    let valid = RidAircraftIdentity(remoteID: "RID1", organization: "", pilotCallsign: "1SAR7", droneDescription: "DJI Mini 4 Pro")
    let duplicates = RidMappingEditValidation.errors(valid, others: [valid], requireOrganization: false)
    #expect(duplicates.contains("Remote ID is already listed."))
    #expect(duplicates.contains("Model must be unique for this owner callsign."))
}

@Test func ridMappingAcceptsPilotNamesAndArbitraryCallsigns() {
    for pilot in ["SAR7", "Ken Taylor", "Alpha-2", "O'Neil", "山田 太郎"] {
        let identity = RidAircraftIdentity(remoteID: "RID1", organization: "", pilotCallsign: pilot, droneDescription: "Mini 4 Pro")
        #expect(RidMappingEditValidation.errors(identity, others: [], requireOrganization: false).isEmpty)
        #expect(identity.pilotCallsign == pilot)
    }
}

@Test func samePilotCanOwnTwoNeoDronesWithDistinctModelDescriptions() {
    let first = RidAircraftIdentity(remoteID: "RID1", organization: "", pilotCallsign: "1sar7", droneDescription: "DJI Neo")
    let second = RidAircraftIdentity(remoteID: "RID2", organization: "", pilotCallsign: "1sar7", droneDescription: "DJI Neo - 2")
    #expect(RidMappingEditValidation.errors(second, others: [first], requireOrganization: false).isEmpty)
    #expect(first.mappedID != second.mappedID)
    let duplicate = RidAircraftIdentity(remoteID: "RID2", organization: "", pilotCallsign: " 1SAR7 ", droneDescription: " dji neo ")
    #expect(RidMappingEditValidation.errors(duplicate, others: [first], requireOrganization: false)
        .contains("Model must be unique for this owner callsign."))
}
