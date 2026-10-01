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

@Test func zoomPreservesManualViewportWhileWaitingForPosition() {
    var adjusted = false
    // Pinch zoom is an intentional viewport choice even before RID arrives.
    if OperationalMapFocusPolicy.shouldSuspendFollow(
        isOperatorGesture: true, isZoomGesture: true
    ) { adjusted = true }
    #expect(adjusted)
    #expect(OperationalMapFocusPolicy.initialStreamFocus(
        followEnabled: true, focusedAircraftID: nil, operatorAdjustedViewport: adjusted,
        liveStreamAircraftIDs: [nil]) == nil)
    #expect(OperationalMapFocusPolicy.initialStreamFocus(
        followEnabled: true, focusedAircraftID: nil, operatorAdjustedViewport: adjusted,
        liveStreamAircraftIDs: ["mini-rid"]) == nil)
    #expect(OperationalMapFocusPolicy.shouldReleaseFocus(
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

@Test func telemetryArrivalOverridesEarlierPanAndFocusWithoutRepeating() {
    var arrivals = OperationalStreamFocusArrival()
    var focus: String? = "other-drone"
    var adjusted = true
    #expect(arrivals.observe(resolvedAircraftIDs: [nil]) == nil)
    if let aircraft = arrivals.observe(resolvedAircraftIDs: ["mini-rid"]) {
        focus = aircraft
        adjusted = false
    }
    #expect(focus == "mini-rid")
    #expect(!adjusted)
    // A later pan must not be undone by updates, loss, or reconnect.
    #expect(arrivals.observe(resolvedAircraftIDs: ["mini-rid"]) == nil)
    #expect(arrivals.observe(resolvedAircraftIDs: []) == nil)
    #expect(arrivals.observe(resolvedAircraftIDs: ["mini-rid"]) == nil)
}

@Test func telemetryArrivalSelectsDroneIndependentlyOfFollowSetting() {
    var arrivals = OperationalStreamFocusArrival()
    #expect(arrivals.observe(resolvedAircraftIDs: ["mini-rid"]) == "mini-rid")
}

@Test func telemetryArrivalAvoidsAmbiguityButAcceptsOneNewMatch() {
    var arrivals = OperationalStreamFocusArrival()
    #expect(arrivals.observe(resolvedAircraftIDs: ["a", "b"]) == nil)
    #expect(arrivals.observe(resolvedAircraftIDs: ["b"]) == nil)
    #expect(arrivals.observe(resolvedAircraftIDs: ["b", "c", nil]) == "c")
    #expect(arrivals.observe(resolvedAircraftIDs: ["b", "c"]) == nil)
}


@Test func pipFollowsVideoWithoutFullMapSelection() {
    #expect(OperationalMapFocusPolicy.presentationFocus(inset: true, mapAircraftID: nil,
        focusedVideoID: "video-a", videoAircraftID: "aircraft-a") == "aircraft-a")
    #expect(OperationalMapFocusPolicy.presentationFocus(inset: true, mapAircraftID: "old",
        focusedVideoID: "video-b", videoAircraftID: "aircraft-b") == "aircraft-b")
    #expect(OperationalMapFocusPolicy.presentationFocus(inset: true, mapAircraftID: "old",
        focusedVideoID: "video-b", videoAircraftID: nil) == nil)
    #expect(OperationalMapFocusPolicy.presentationFocus(inset: false, mapAircraftID: "map",
        focusedVideoID: "video-b", videoAircraftID: "aircraft-b") == "map")
    #expect(OperationalMapFocusPolicy.presentationFocus(inset: true, mapAircraftID: "map",
        focusedVideoID: nil, videoAircraftID: nil) == "map")
}

@Test func pipFollowIgnoresFullMapPanButRespectsFollowToggle() {
    #expect(OperationalMapFocusPolicy.shouldFollow(inset: true, enabled: true, operatorAdjustedViewport: true))
    #expect(!OperationalMapFocusPolicy.shouldFollow(inset: false, enabled: true, operatorAdjustedViewport: true))
    #expect(!OperationalMapFocusPolicy.shouldFollow(inset: true, enabled: false, operatorAdjustedViewport: false))
    #expect(OperationalMapFocusPolicy.shouldFollow(inset: false, enabled: true, operatorAdjustedViewport: false))
}
