import Testing
@testable import R2CCore

@Test func mapDroneFirstTapFocusesAndSecondTapInspects() {
    #expect(!OperationalMapFocusPolicy.shouldInspectAircraft(
        focusedAircraftID: nil, tappedAircraftID: "drone-a"
    ))
    #expect(OperationalMapFocusPolicy.shouldInspectAircraft(
        focusedAircraftID: "drone-a", tappedAircraftID: "drone-a"
    ))
    #expect(!OperationalMapFocusPolicy.shouldInspectAircraft(
        focusedAircraftID: "drone-a", tappedAircraftID: "drone-b"
    ))
}

@Test func mapDroneGestureReleaseMakesNextTapFocusAgain() {
    var focus: String? = "drone-a"
    if OperationalMapFocusPolicy.shouldReleaseFocus(
        hasFocusedAircraft: focus != nil, isOperatorGesture: true
    ) {
        focus = nil
    }
    #expect(!OperationalMapFocusPolicy.shouldInspectAircraft(
        focusedAircraftID: focus, tappedAircraftID: "drone-a"
    ))
}
