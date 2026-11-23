import Testing
@testable import R2CCore

@Test func hasRequiredPilotCallsignRejectsBlankAndWhitespace() {
    #expect(DroneConfirmationPolicy.hasRequiredPilotCallsign("") == false)
    #expect(DroneConfirmationPolicy.hasRequiredPilotCallsign("   ") == false)
    #expect(DroneConfirmationPolicy.hasRequiredPilotCallsign("\t") == false)
    #expect(DroneConfirmationPolicy.hasRequiredPilotCallsign("1SAR7"))
    #expect(DroneConfirmationPolicy.hasRequiredPilotCallsign(" 1sar7 "))
    #expect(DroneConfirmationPolicy.pilotCallsignRequiredHint == "Pilot callsign required")
}

@Test func preferredCallsignToPersistSkipsBlankValues() {
    #expect(DroneConfirmationPolicy.preferredCallsignToPersist("") == nil)
    #expect(DroneConfirmationPolicy.preferredCallsignToPersist("   ") == nil)
    #expect(DroneConfirmationPolicy.preferredCallsignToPersist(" 1sar7 ") == "1SAR7")
}

@Test func canConfirmIdentityRequiresNonBlankCallsignEvenWhenUnresolved() {
    let complete = RidAircraftIdentity(
        remoteID: "1581F8HGX255S00A0FZT",
        organization: "NCSSAR",
        pilotCallsign: "1SAR7",
        droneDescription: "DJI Matrice 4TD"
    )
    let blankCallsign = RidAircraftIdentity(
        remoteID: "1581F8HGX255S00A0FZT",
        organization: "NCSSAR",
        pilotCallsign: "   ",
        droneDescription: "DJI Matrice 4TD"
    )
    let unresolvedButEntered = RidAircraftIdentity(
        remoteID: "1581F8HGX255S00A0FZT",
        organization: "",
        pilotCallsign: "1SAR7",
        droneDescription: "DJI Matrice 4TD"
    )
    #expect(DroneConfirmationPolicy.canConfirmIdentity(complete, recordUnresolvedPilot: false))
    #expect(DroneConfirmationPolicy.canConfirmIdentity(complete, recordUnresolvedPilot: true))
    #expect(DroneConfirmationPolicy.canConfirmIdentity(blankCallsign, recordUnresolvedPilot: false) == false)
    #expect(DroneConfirmationPolicy.canConfirmIdentity(blankCallsign, recordUnresolvedPilot: true) == false)
    #expect(DroneConfirmationPolicy.canConfirmIdentity(unresolvedButEntered, recordUnresolvedPilot: false) == false)
    #expect(DroneConfirmationPolicy.canConfirmIdentity(unresolvedButEntered, recordUnresolvedPilot: true))
}
