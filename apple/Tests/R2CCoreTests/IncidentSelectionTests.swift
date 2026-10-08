import Testing
@testable import R2CCore

@Test func incidentSelectionUsesMapOnlyWhileConnected() {
    #expect(OperationalIncidentSelection.name(mapID: "", mapTitle: "Old map", standaloneName: " Airport Exercise ") == "Airport Exercise")
    #expect(OperationalIncidentSelection.name(mapID: "map1", mapTitle: " Current map ", standaloneName: "Airport Exercise") == "Current map")
    #expect(OperationalIncidentSelection.name(mapID: "", mapTitle: "Current map", standaloneName: "Airport Exercise") == "Airport Exercise")
}
@Test func incidentSelectionHasConsistentFallback() {
    #expect(OperationalIncidentSelection.name(mapID: "", mapTitle: "Old map", standaloneName: "  ") == "Training")
    #expect(OperationalIncidentSelection.name(mapID: "map1", mapTitle: "", standaloneName: "Exercise") == "Exercise")
}
