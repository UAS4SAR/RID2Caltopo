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

@Test func zoomPreservesFocusAndVideoFirstPendingFollow() {
    var adjusted = false
    // A simultaneous MapKit pan recognizer during pinch must not suspend follow,
    // even when video is waiting for its first RID position.
    if OperationalMapFocusPolicy.shouldSuspendFollow(
        isOperatorGesture: true, isZoomGesture: true
    ) { adjusted = true }
    #expect(!adjusted)
    #expect(OperationalMapFocusPolicy.initialStreamFocus(
        followEnabled: true, focusedAircraftID: nil, operatorAdjustedViewport: adjusted,
        liveStreamAircraftIDs: [nil]) == nil)
    #expect(OperationalMapFocusPolicy.initialStreamFocus(
        followEnabled: true, focusedAircraftID: nil, operatorAdjustedViewport: adjusted,
        liveStreamAircraftIDs: ["mini-rid"]) == "mini-rid")
    #expect(!OperationalMapFocusPolicy.shouldReleaseFocus(
        hasFocusedAircraft: true, isOperatorGesture: true, isZoomGesture: true))
    #expect(OperationalMapFocusPolicy.shouldReleaseFocus(
        hasFocusedAircraft: true, isOperatorGesture: true, isZoomGesture: false))
}

@Test func singleLiveStreamInitiallyFocusesWithoutOverridingOperator() {
    func candidate(_ streams: [String?], follow: Bool = true, focus: String? = nil,
                   adjusted: Bool = false) -> String? {
        OperationalMapFocusPolicy.initialStreamFocus(followEnabled: follow,
            focusedAircraftID: focus, operatorAdjustedViewport: adjusted,
            liveStreamAircraftIDs: streams)
    }
    #expect(candidate(["drone-a"]) == "drone-a")
    #expect(candidate([]) == nil)
    #expect(candidate([nil]) == nil)
    #expect(candidate([""]) == nil)
    #expect(candidate(["drone-a", nil]) == nil)
    #expect(candidate(["drone-a", "drone-b"]) == nil)
    #expect(candidate(["drone-a"], follow: false) == nil)
    #expect(candidate(["drone-a"], focus: "drone-b") == nil)
    #expect(candidate(["drone-a"], adjusted: true) == nil)
}

@Test func initialStreamFocusReevaluatesOnArrivalBindingAndPreferenceChanges() {
    func state(_ ids: [String?], follow: Bool = true, focus: String? = nil,
               adjusted: Bool = false) -> OperationalInitialStreamFocusState {
        .init(followEnabled: follow, focusedAircraftID: focus,
              operatorAdjustedViewport: adjusted, liveStreamAircraftIDs: ids)
    }
    let waiting = state([])
    let unbound = state([nil])
    let bound = state(["drone-a"])
    #expect(waiting != unbound)
    #expect(unbound != bound)
    #expect(waiting.candidate == nil)
    #expect(unbound.candidate == nil)
    #expect(bound.candidate == "drone-a")
    #expect(bound != state(["drone-a"], follow: false))
    #expect(state(["drone-a"], follow: false).candidate == nil)
    #expect(state(["drone-a"], focus: "drone-a").candidate == nil)
    #expect(state(["drone-a"], adjusted: true).candidate == nil)
}

@Test func singleStreamArrivalClearsOnlyPreStreamMapAdjustment() {
    var arrivals = OperationalStreamFocusArrival()
    var adjusted = true // The operator moved the map before video arrived.
    let begins = arrivals.observe(liveStreamIDs: ["mini"], followEnabled: true, hasFocus: false)
    if begins { adjusted = false }
    #expect(begins)
    #expect(OperationalMapFocusPolicy.initialStreamFocus(followEnabled: true, focusedAircraftID: nil,
        operatorAdjustedViewport: adjusted, liveStreamAircraftIDs: [nil]) == nil)
    // RID arrives later and resolves the existing video stream.
    #expect(OperationalMapFocusPolicy.initialStreamFocus(followEnabled: true, focusedAircraftID: nil,
        operatorAdjustedViewport: adjusted, liveStreamAircraftIDs: ["mini-rid"]) == "mini-rid")
    adjusted = true // A later operator pan releases follow.
    let repeated = arrivals.observe(liveStreamIDs: ["mini"], followEnabled: true, hasFocus: false)
    #expect(!repeated)
    _ = arrivals.observe(liveStreamIDs: [], followEnabled: true, hasFocus: false)
    let reconnected = arrivals.observe(liveStreamIDs: ["mini"], followEnabled: true, hasFocus: false)
    #expect(!reconnected)
    #expect(OperationalMapFocusPolicy.initialStreamFocus(followEnabled: true, focusedAircraftID: nil,
        operatorAdjustedViewport: adjusted, liveStreamAircraftIDs: ["mini-rid"]) == nil)
}

@Test func streamArrivalPreservesExistingFocusAndMultipleStreamChoices() {
    var arrivals = OperationalStreamFocusArrival()
    let multiple = arrivals.observe(liveStreamIDs: ["a", "b"], followEnabled: true, hasFocus: false)
    #expect(!multiple)
    let remaining = arrivals.observe(liveStreamIDs: ["b"], followEnabled: true, hasFocus: false)
    #expect(!remaining)
    let focused = arrivals.observe(liveStreamIDs: ["c"], followEnabled: true, hasFocus: true)
    #expect(!focused)
    let disabled = arrivals.observe(liveStreamIDs: ["d"], followEnabled: false, hasFocus: false)
    #expect(!disabled)
}
