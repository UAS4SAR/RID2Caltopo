import Foundation
import Testing
@testable import R2CCore

@Suite struct CluePhotoUploadPolicyTests {
    @Test func photoTimeoutScalesWithSizeAndCaps() {
        #expect(CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: 0) == 45)
        #expect(CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: 100_000) == 75)
        #expect(CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: 853 * 1024) == 75)
        #expect(CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: 1024 * 1024) == 75)
        #expect(CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: 2 * 1024 * 1024) == 105)
        #expect(CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: 20 * 1024 * 1024) == 180)
    }

    @Test func sendBudgetCoversJsonStepsPlusPhotoTimeout() {
        let overhead = CluePhotoUploadPolicy.jsonTimeoutSeconds * 5
        #expect(CluePhotoUploadPolicy.attemptBudgetOverheadSeconds == overhead)
        #expect(CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: 0) == 45 + overhead)
        #expect(CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: 100_000) == 75 + overhead)
        #expect(CluePhotoUploadPolicy.submitDeadlineSeconds(jpegBytes: 0) == 45 + overhead)
        #expect(CluePhotoUploadPolicy.submitDeadlineSeconds(jpegBytes: 100_000) == 75 + overhead)
        #expect(
            CluePhotoUploadPolicy.submitDeadlineSeconds(jpegBytes: 0)
                == CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: 0)
        )
        #expect(
            CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: 100_000)
                >= CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: 100_000) + overhead
        )
    }

    @Test func existingBackendMediaMessageIsClassified() {
        #expect(CluePhotoUploadPolicy.isExistingBackendMediaError(
            statusCode: 400,
            message: #"{"message":"Cannot update existing backendMedia"}"#
        ))
        #expect(!CluePhotoUploadPolicy.isExistingBackendMediaError(
            statusCode: 400,
            message: "Personal map request failed."
        ))
        #expect(!CluePhotoUploadPolicy.isExistingBackendMediaError(
            statusCode: 404,
            message: #"{"message":"Cannot update existing backendMedia"}"#
        ))
        #expect(CluePhotoUploadPolicy.isAmbiguousMediaState(
            statusCode: 409,
            message: "Cannot update existing backendMedia; media state ambiguous"
        ))
    }

    @Test func plainUploadErrorIsOperatorFacing() {
        #expect(
            CluePhotoUploadPolicy.plainUploadError(#"{"message":"Cannot update existing backendMedia"}"#)
                == "Photo upload incomplete; replacing photo id"
        )
        #expect(CluePhotoUploadPolicy.plainUploadError("") == "Waiting to upload")
        #expect(CluePhotoUploadPolicy.plainUploadError("Needs attention") == "Needs attention")
        #expect(CluePhotoUploadPolicy.plainUploadError("Photo missing") == "Photo missing")
        #expect(CluePhotoUploadPolicy.plainUploadError("missing-photo") == "Photo missing")
    }

    @Test func missingPhotoStopsAutoRetryAndNotesLostPhotoOnce() {
        #expect(CluePhotoUploadPolicy.stopsAutoRetry("Photo missing"))
        #expect(CluePhotoUploadPolicy.stopsAutoRetry("Needs attention"))
        #expect(CluePhotoUploadPolicy.stopsAutoRetry("missing-photo"))
        #expect(!CluePhotoUploadPolicy.stopsAutoRetry("Upload timed out; will retry"))
        let noted = CluePhotoUploadPolicy.descriptionWithLostPhotoNote("Found beside trail")
        #expect(CluePhotoUploadPolicy.descriptionNotesLostPhoto(noted))
        #expect(CluePhotoUploadPolicy.descriptionWithLostPhotoNote(noted) == noted)
    }

    @Test func markerTimeIncludesLocalZoneAndUTC() {
        let date = Date(timeIntervalSince1970: 1_730_401_140) // 2024-10-31 19:19:00 PDT-ish
        let pacific = TimeZone(identifier: "America/Los_Angeles")!
        let text = CluePhotoUploadPolicy.formatClueMarkerTime(date, timeZone: pacific)
        #expect(text.contains("UTC"))
        #expect(text.contains("(") && text.contains(")"))
    }

    @Test func clueJsonTimeoutMatchesV254WhileArchiveStaysBounded() {
        #expect(CluePhotoUploadPolicy.jsonTimeoutSeconds == 60)
        #expect(CaltopoLiveClient.requestTimeoutSeconds == 15)
    }

    @Test func clueStepFailureMessageIncludesStepAndScrubsIds() {
        let line = CluePhotoUploadPolicy.clueStepFailureMessage(
            step: "media-post",
            method: "POST",
            path: "/api/v1/media/19fe1364-dc45-4275-a6ae-7fcfef8aef78?signature=secret",
            elapsedMs: 15_012,
            timeoutSeconds: 60
        )
        #expect(line.contains("step=media-post"))
        #expect(line.contains("method=POST"))
        #expect(line.contains("path=/api/v1/media/…"))
        #expect(!line.contains("19fe1364"))
        #expect(!line.contains("signature"))
        #expect(line.contains("elapsedMs=15012"))
        #expect(line.contains("timeoutSeconds=60"))
    }
}
