import Testing
@testable import R2CCore

@Suite struct CluePartsReconcilePolicyTests {
    @Test func publishedUnreconciledNeedsAutoReverify() {
        #expect(CluePartsReconcilePolicy.needsAutoReverify(uploadState: .published, partsReconciled: false))
    }

    @Test func publishedReconciledStopsAutoReverify() {
        #expect(!CluePartsReconcilePolicy.needsAutoReverify(uploadState: .published, partsReconciled: true))
    }

    @Test func pendingNeverAutoReverifyAsPublished() {
        #expect(!CluePartsReconcilePolicy.needsAutoReverify(uploadState: .pending, partsReconciled: false))
        #expect(!CluePartsReconcilePolicy.needsAutoReverify(uploadState: .failed, partsReconciled: false))
    }

    @Test func retryClearsOnlyReconciledFlag() {
        #expect(CluePartsReconcilePolicy.clearReconciledOnRetry(partsReconciled: true))
        #expect(!CluePartsReconcilePolicy.clearReconciledOnRetry(partsReconciled: false))
    }
}
