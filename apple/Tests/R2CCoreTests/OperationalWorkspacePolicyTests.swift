import Testing
@testable import R2CCore

struct OperationalWorkspacePolicyTests {
    @Test func requestedButNotPlayedAudioKeepsBellHidden() {
        #expect(OperationalWorkspacePolicy.alertTone(hasPlayed: false, active: true, caution: true) == .hidden)
    }
    @Test func appearedBellSurvivesClearingAndActiveWinsOverCaution() {
        #expect(OperationalWorkspacePolicy.alertTone(hasPlayed: true, active: true, caution: true) == .active)
        #expect(OperationalWorkspacePolicy.alertTone(hasPlayed: true, active: false, caution: true) == .caution)
        #expect(OperationalWorkspacePolicy.alertTone(hasPlayed: true, active: false, caution: false) == .quiet)
    }
    @Test func cautionRejectsInvalidTelemetryAndChecksBothAxes() {
        #expect(OperationalWorkspacePolicy.separationCaution(horizontal: 109, vertical: 109, threshold: 100))
        #expect(!OperationalWorkspacePolicy.separationCaution(horizontal: 111, vertical: 0, threshold: 100))
        #expect(!OperationalWorkspacePolicy.separationCaution(horizontal: 90, vertical: 111, threshold: 100))
        #expect(!OperationalWorkspacePolicy.separationCaution(horizontal: .nan, vertical: nil, threshold: 100))
        #expect(!OperationalWorkspacePolicy.separationCaution(horizontal: 10, vertical: 10, threshold: 0))
        #expect(OperationalWorkspacePolicy.separationCaution(horizontal: 105, vertical: nil, threshold: 100))
    }
    @Test func ignoredAndUnknownKeepTheirPromptsWhilePublishedOpensInspector() {
        #expect(OperationalWorkspacePolicy.droneAction(known: false, publishing: false) == .add)
        #expect(OperationalWorkspacePolicy.droneAction(known: true, publishing: false) == .confirm)
        #expect(OperationalWorkspacePolicy.droneAction(known: true, publishing: true) == .inspect)
    }
}
