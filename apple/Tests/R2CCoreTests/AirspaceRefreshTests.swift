import Testing
@testable import R2CCore

struct AirspaceRefreshTests {
    @Test func successfulLookupWaitsTwentyMinutes() {
        for elapsed in [0.0, 2, 32, 60, 900, 1199.9] {
            #expect(!OperationalAirspaceRefreshPolicy.shouldRefresh(
                autoRefresh: true, hasCompletedAttempt: true, elapsedSinceAttempt: elapsed))
        }
        #expect(OperationalAirspaceRefreshPolicy.shouldRefresh(
            autoRefresh: true, hasCompletedAttempt: true, elapsedSinceAttempt: 1200))
    }
    @Test func disabledAutoRefreshOnlyAllowsInitialLookup() {
        #expect(OperationalAirspaceRefreshPolicy.shouldRefresh(
            autoRefresh: false, hasCompletedAttempt: false, elapsedSinceAttempt: 0))
        #expect(!OperationalAirspaceRefreshPolicy.shouldRefresh(
            autoRefresh: false, hasCompletedAttempt: true, elapsedSinceAttempt: 2400))
    }
}
