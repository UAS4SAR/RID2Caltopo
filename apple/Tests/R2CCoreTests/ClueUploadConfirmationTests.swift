import Testing
@testable import R2CCore

@Suite("ClueUploadConfirmation")
struct ClueUploadConfirmationTests {
    @Test func publishedOrReconciledIsConfirmed() {
        #expect(ClueUploadConfirmation.isConfirmed(uploadState: .published, partsReconciled: false))
        #expect(ClueUploadConfirmation.isConfirmed(uploadState: .pending, partsReconciled: true))
        #expect(!ClueUploadConfirmation.isConfirmed(uploadState: .pending, partsReconciled: false))
        #expect(!ClueUploadConfirmation.isConfirmed(uploadState: .failed, partsReconciled: false))
    }

    @Test func legacySuccessMarkerIdConfirmsNonPending() {
        #expect(
            ClueUploadConfirmation.isConfirmed(
                uploadState: .localOnly,
                partsReconciled: false,
                hasLegacyCaltopoIds: true
            )
        )
        #expect(
            !ClueUploadConfirmation.isConfirmed(
                uploadState: .pending,
                partsReconciled: false,
                hasLegacyCaltopoIds: true
            )
        )
    }

    @Test func onlyUnconfirmedPendingMayAutoUpload() {
        #expect(ClueUploadConfirmation.mayAutoUpload(uploadState: .pending, confirmed: false, remotelyDeleted: false))
        #expect(!ClueUploadConfirmation.mayAutoUpload(uploadState: .pending, confirmed: true, remotelyDeleted: false))
        #expect(!ClueUploadConfirmation.mayAutoUpload(uploadState: .pending, confirmed: false, remotelyDeleted: true))
        #expect(!ClueUploadConfirmation.mayAutoUpload(uploadState: .published, confirmed: true, remotelyDeleted: false))
    }

    @Test func confirmedMarkerMissingMeansMapDeletedEvenWhenMediaRemains() {
        #expect(
            ClueUploadConfirmation.decideProbe(
                confirmed: true,
                markerPresent: false,
                mediaPresent: true,
                linkPresent: false
            ) == .markRemotelyDeleted
        )
    }

    @Test func confirmedAllPresentBackfillsReconciled() {
        #expect(
            ClueUploadConfirmation.decideProbe(
                confirmed: true,
                markerPresent: true,
                mediaPresent: true,
                linkPresent: true
            ) == .confirmReconciled
        )
    }

    @Test func unconfirmedAbsentMayAutoUpload() {
        #expect(
            ClueUploadConfirmation.decideProbe(
                confirmed: false,
                markerPresent: false,
                mediaPresent: false,
                linkPresent: false
            ) == .mayAutoUpload
        )
    }

    @Test func unconfirmedPartialMayRepair() {
        #expect(
            ClueUploadConfirmation.decideProbe(
                confirmed: false,
                markerPresent: true,
                mediaPresent: false,
                linkPresent: false
            ) == .repairPartial
        )
    }
}
