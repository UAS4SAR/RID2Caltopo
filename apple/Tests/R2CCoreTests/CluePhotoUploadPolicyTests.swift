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
    }

    @Test func plainUploadErrorIsOperatorFacing() {
        #expect(
            CluePhotoUploadPolicy.plainUploadError(#"{"message":"Cannot update existing backendMedia"}"#)
                == "Photo already on CalTopo; finishing link"
        )
        #expect(CluePhotoUploadPolicy.plainUploadError("") == "Waiting to upload")
    }

    @Test func jsonTimeoutStaysShort() {
        #expect(CluePhotoUploadPolicy.jsonTimeoutSeconds == 15)
        #expect(CaltopoLiveClient.requestTimeoutSeconds == 15)
    }
}
