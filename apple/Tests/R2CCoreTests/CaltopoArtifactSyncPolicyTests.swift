import Testing
@testable import R2CCore

@Test func fullArtifactReadsRequireInitialLoadOrManualReload() {
    #expect(CaltopoArtifactSyncPolicy.fullRefreshRequired(lastSuccessfulCursorMilliseconds: 0, manualReload: false))
    #expect(!CaltopoArtifactSyncPolicy.fullRefreshRequired(lastSuccessfulCursorMilliseconds: 1000, manualReload: false))
    #expect(CaltopoArtifactSyncPolicy.fullRefreshRequired(lastSuccessfulCursorMilliseconds: 1000, manualReload: true))
}
