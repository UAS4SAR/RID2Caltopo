import Testing
@testable import R2CCore

@Test func recordingWaitsThroughNegotiationAndStartsOnce() {
    var gate = ManagedVideoPlaybackStartGate()
    let negotiationStarts = (0..<100).map { _ in gate.shouldStart(connected: false) }
    #expect(negotiationStarts.allSatisfy { !$0 })
    let transitions = [true, true, false, true].map { gate.shouldStart(connected: $0) }
    #expect(transitions == [true, false, false, false])
}

@Test func cancelledRecordingCannotStartOnLateConnectionCallback() {
    var retired = ManagedVideoPlaybackStartGate()
    retired.cancel()
    let lateStart = retired.shouldStart(connected: true)
    #expect(!lateStart)
    var replacement = ManagedVideoPlaybackStartGate()
    let replacementStart = replacement.shouldStart(connected: true)
    #expect(replacementStart)
}
