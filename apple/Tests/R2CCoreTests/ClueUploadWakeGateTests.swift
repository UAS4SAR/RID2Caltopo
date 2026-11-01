import Testing
@testable import R2CCore

@Suite struct ClueUploadWakeGateTests {
    @Test func idleOwnerStartsFreshLoop() {
        let decision = ClueUploadWakeGate.decide(ownerActive: false, inFlightIds: [])
        #expect(decision.startOwner)
        #expect(decision.skipLogIds == nil)
    }

    @Test func activeOwnerWithUploadIdsLogsThoseIds() {
        let decision = ClueUploadWakeGate.decide(
            ownerActive: true,
            inFlightIds: ["fda322b4", "1eb73175"]
        )
        #expect(!decision.startOwner)
        #expect(decision.skipLogIds == ["fda322b4", "1eb73175"])
    }

    @Test func activeOwnerWithEmptyIdsNeverClaimsUploadInFlight() {
        let decision = ClueUploadWakeGate.decide(ownerActive: true, inFlightIds: [])
        #expect(!decision.startOwner)
        #expect(decision.skipLogIds == nil)
    }
}
