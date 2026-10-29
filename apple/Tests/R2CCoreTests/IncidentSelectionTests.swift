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
    #expect(OperationalIncidentSelection.displayMapTitle("Ken 1SAR7 Taylor: Taylor Map", personalName: "Ken 1SAR7 Taylor") == "Taylor Map")
    #expect(OperationalIncidentSelection.displayMapTitle("Taylor Map", personalName: "Ken 1SAR7 Taylor") == "Taylor Map")
    #expect(OperationalIncidentSelection.mapOptionsText(personal: true, accountName: "Ken 1SAR7 Taylor", mapTitle: "Taylor Map") == "Credentials: Personal: Ken 1SAR7 Taylor\nMap: Taylor Map")
    #expect(OperationalIncidentSelection.mapOptionsText(personal: false, accountName: "NCSSAR", mapTitle: "Taylor Map") == "Credentials: Team: NCSSAR\nMap: Taylor Map")
}
