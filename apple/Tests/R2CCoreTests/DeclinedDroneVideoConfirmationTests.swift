import Testing
@testable import R2CCore

@Test func declinedDroneRequiresNewLocalPublisherToPromptAgain() {
    var video = DeclinedDroneVideoConfirmation()
    var lifecycle = CurrentFlightConfirmationLifecycle()
    let ignored: Set<String> = ["drone"]
    _ = lifecycle.reconcile(orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: [])
    video.markHandled("drone")
    lifecycle.endFlight(remoteID: "drone")
    #expect(lifecycle.reconcile(orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored).candidateRemoteID == nil)
    video.update(["drone": ["mini|publisher-1"]])
    #expect(video.hasNewPublisher("drone"))
    #expect(lifecycle.reconcile(orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored,
        videoReconfirmationRemoteIDs: ["drone"], allowPrompt: false).candidateRemoteID == nil)
    #expect(lifecycle.reconcile(orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored,
        videoReconfirmationRemoteIDs: ["drone"]).candidateRemoteID == "drone")
    video.markHandled("drone")
    #expect(!video.hasNewPublisher("drone"))
    #expect(lifecycle.reconcile(orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored).candidateRemoteID == nil)
    video.update([:])
    #expect(!video.hasNewPublisher("drone"))
    video.update(["drone": ["mini|publisher-1"]])
    #expect(!video.hasNewPublisher("drone"))
    video.update(["drone": ["mini|publisher-2"]])
    #expect(video.hasNewPublisher("drone"))
}

@Test func unknownOrRetainedPublisherDoesNotReopenDeclinedConfirmation() {
    var video = DeclinedDroneVideoConfirmation()
    video.update(["drone": ["mini|unknown"]])
    #expect(!video.hasNewPublisher("drone"))
    video.markHandled("drone")
    video.update(["drone": ["mini|resolved"]])
    #expect(!video.hasNewPublisher("drone"))
    #expect(!video.hasNewPublisher("other"))
    video.update(["drone": ["mini|new-publisher"]])
    #expect(video.hasNewPublisher("drone"))
}
