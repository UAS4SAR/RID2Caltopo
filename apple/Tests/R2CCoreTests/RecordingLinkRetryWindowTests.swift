import Testing
@testable import R2CCore

@Test func recordingLinkWaitsForSlowFinalizationThenHasBoundedGrace() {
    var window = RecordingLinkRetryWindow(now: 0)
    let activity = RecordingFinalizationActivity()
    let queued = activity.begin(path: "camera/1SAR7DJMN4PR")
    let candidates = ["unrelated-rid", "1sar7djmn4pr"]
    for second in stride(from: 2, through: 96, by: 2) {
        let retry = window.shouldRetry(now: Double(second), finalizing: activity.isActive(matching: candidates))
        #expect(retry)
    }
    activity.finish(queued)
    let withinGrace = window.shouldRetry(now: 120, finalizing: activity.isActive(matching: candidates))
    let expired = window.shouldRetry(now: 126, finalizing: activity.isActive(matching: candidates))
    #expect(withinGrace)
    #expect(!expired)
}

@Test func recordingLinkWithoutMatchingFinalizationStillExpires() {
    var window = RecordingLinkRetryWindow(now: 0)
    let activity = RecordingFinalizationActivity()
    let token = activity.begin(path: "other-camera")
    defer { activity.finish(token) }
    let retry = window.shouldRetry(now: 30, finalizing: activity.isActive(matching: ["this-camera"]))
    #expect(!retry)
}

@Test func recordingFinalizationCountsOverlappingWorkAndDuplicateCleanup() {
    let activity = RecordingFinalizationActivity()
    let first = activity.begin(path: "camera")
    let second = activity.begin(path: "camera")
    activity.finish(first)
    activity.finish(first)
    #expect(activity.isActive(matching: ["CAMERA"]))
    activity.finish(second)
    #expect(!activity.isActive(matching: ["camera"]))
}
