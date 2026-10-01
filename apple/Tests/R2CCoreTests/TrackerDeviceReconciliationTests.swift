import Foundation
import XCTest
@testable import R2CCore

final class TrackerDeviceReconciliationTests: XCTestCase {
    func testAuthorizationFailureOffersTrustedSignInInsteadOfSilentlyDeferring() throws {
        let data = Data(#"{"detail":{"code":"reauthentication_required","reauthentication_url":"https://r2c-tracker.com/ncssar/reauthenticate?token=test"}}"#.utf8)
        XCTAssertThrowsError(try TrackerDeviceReconciliation.validateResponse(data, statusCode: 403)) {
            XCTAssertEqual($0 as? TrackerDeviceAuthorizationError, .reauthenticationRequired(URL(string: "https://r2c-tracker.com/ncssar/reauthenticate?token=test")!))
        }
        XCTAssertNoThrow(try TrackerDeviceReconciliation.validateResponse(Data(), statusCode: 200))
    }
    func testRejectedAuthorizationAndServerFailureRemainDistinct() {
        for status in [401, 403] {
            XCTAssertThrowsError(try TrackerDeviceReconciliation.validateResponse(Data(), statusCode: status)) {
                XCTAssertEqual($0 as? TrackerDeviceAuthorizationError, .authorizationRejected)
            }
        }
        let untrusted = Data(#"{"detail":{"code":"reauthentication_required","reauthentication_url":"https://example.com/login"}}"#.utf8)
        XCTAssertThrowsError(try TrackerDeviceReconciliation.validateResponse(untrusted, statusCode: 403)) {
            XCTAssertEqual($0 as? TrackerDeviceAuthorizationError, .authorizationRejected)
        }
        XCTAssertThrowsError(try TrackerDeviceReconciliation.validateResponse(Data(), statusCode: 503)) {
            XCTAssertEqual($0 as? TrackerDeviceAuthorizationError, .httpStatus(503))
        }
    }
    func testCandidateCheckUsesCurrentAuthorizationAndDropsQuery() throws {
        let request = try TrackerDeviceReconciliation.request(baseURL: "https://ncssar.r2c-tracker.com/old?token=old#fragment", token: "current")
        XCTAssertEqual(request.url?.absoluteString, "https://ncssar.r2c-tracker.com/api/v1/device-authorization/replacement-candidates")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-SAR-Token"), "current")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertNil(request.httpBody)
    }
    func testReplacementRequiresExplicitCandidate() throws {
        let request = try TrackerDeviceReconciliation.request(baseURL: "https://r2c-tracker.com", token: "current", replacementID: "earlier-device")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v1/device-authorization/replace")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: String])
        XCTAssertEqual(body, ["replacement_credential_id": "earlier-device"])
        XCTAssertThrowsError(try TrackerDeviceReconciliation.request(baseURL: "https://r2c-tracker.com", token: "current", replacementID: ""))
    }
    func testRejectsUntrustedDestinationsAndMissingCredential() {
        for url in ["http://r2c-tracker.com", "https://r2c-tracker.com.evil.test", "https://user@r2c-tracker.com"] {
            XCTAssertThrowsError(try TrackerDeviceReconciliation.request(baseURL: url, token: "current"))
        }
        XCTAssertThrowsError(try TrackerDeviceReconciliation.request(baseURL: "https://r2c-tracker.com", token: " "))
    }
    func testCandidatesFilterMalformedAndDuplicateRows() throws {
        let data = Data(#"{"supported_platforms":["android","ios"],"candidates":[{"credential_id":"one","device_name":"Ken's iPad","device_model":"iPad13,4"},{"credential_id":"one","device_name":"duplicate"},{"device_name":"no ID"},{"credential_id":"two","device_name":" "}]}"#.utf8)
        let values = try TrackerDeviceReconciliation.candidates(from: data)
        XCTAssertEqual(values.count, 1)
        XCTAssertEqual(values.first?.id, "one")
        XCTAssertEqual(values.first?.deviceName, "Ken's iPad")
        XCTAssertEqual(values.first?.deviceModel, "iPad13,4")
        XCTAssertThrowsError(try TrackerDeviceReconciliation.candidates(from: Data(#"{"detail":"not authorized"}"#.utf8)))
    }
    func testOldServerDoesNotSilentlyCompleteThePendingIdentityCheck() throws {
        XCTAssertThrowsError(try TrackerDeviceReconciliation.candidates(from: Data(#"{"candidates":[]}"#.utf8)))
        XCTAssertTrue(try TrackerDeviceReconciliation.candidates(from: Data(#"{"supported_platforms":["android","ios"],"candidates":[]}"#.utf8)).isEmpty)
    }
    func testCanonicalNameMustComeFromSuccessfulReplacementResponse() throws {
        XCTAssertEqual(try TrackerDeviceReconciliation.canonicalName(from: Data(#"{"canonical_device_name":"Ken's iPad"}"#.utf8)), "Ken's iPad")
        XCTAssertThrowsError(try TrackerDeviceReconciliation.canonicalName(from: Data(#"{"canonical_device_name":" "}"#.utf8)))
    }
}
