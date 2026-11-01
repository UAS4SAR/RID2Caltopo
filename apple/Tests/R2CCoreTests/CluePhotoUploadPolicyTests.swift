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

    @Test func sendBudgetStartsAtFirstRequestWithNoFolderAllowance() {
        #expect(CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: 0) == 90)
        #expect(CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: 100_000) == 120)
        #expect(CluePhotoUploadPolicy.submitDeadlineSeconds(jpegBytes: 0) == 90)
        #expect(CluePhotoUploadPolicy.submitDeadlineSeconds(jpegBytes: 100_000) == 120)
        #expect(
            CluePhotoUploadPolicy.submitDeadlineSeconds(jpegBytes: 0)
                == CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: 0)
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
    }

    @Test func markerTimeIncludesLocalZoneAndUTC() {
        let date = Date(timeIntervalSince1970: 1_730_401_140) // 2024-10-31 19:19:00 PDT-ish
        let pacific = TimeZone(identifier: "America/Los_Angeles")!
        let text = CluePhotoUploadPolicy.formatClueMarkerTime(date, timeZone: pacific)
        #expect(text.contains("UTC"))
        #expect(text.contains("(") && text.contains(")"))
    }

    @Test func jsonTimeoutStaysShort() {
        #expect(CluePhotoUploadPolicy.jsonTimeoutSeconds == 15)
        #expect(CaltopoLiveClient.requestTimeoutSeconds == 15)
    }
}
