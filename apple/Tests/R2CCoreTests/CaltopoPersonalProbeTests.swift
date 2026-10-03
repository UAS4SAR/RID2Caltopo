import XCTest
@testable import R2CCore
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

final class CaltopoPersonalProbeTests: XCTestCase {
    func testCaltopoLinkImportRouting() {
        let signup = "https://caltopo.com/group/ABC123/signup/EXAMPLE"
        XCTAssertEqual(CaltopoPersonalProbe.caltopoLinkURL("  \(signup)\n")?.absoluteString, signup)
        for value in ["https://caltopo.com", "https://caltopo.com/m/ABC123#ll=1,2",
                      "https://caltopo.com/account/login?next=%2Fm%2FABC123", "https://caltopo.com:443/anything",
                      "HTTPS://CALTOPO.COM/arbitrary/path"] {
            XCTAssertEqual(CaltopoPersonalProbe.caltopoLinkURL(value)?.absoluteString, value)
        }
        for value in ["http://caltopo.com/group/ABC123/signup/EXAMPLE",
                      "https://caltopo.com.evil.test/group/ABC123/signup/EXAMPLE",
                      "https://user@caltopo.com/group/ABC123/signup/EXAMPLE",
                      "https://caltopo.com:444/group/ABC123/signup/EXAMPLE",
                      "https://caltopo.com@evil.test/", "https://www.caltopo.com/", "R2C2:test"] {
            XCTAssertNil(CaltopoPersonalProbe.caltopoLinkURL(value))
        }
    }

    func testOnlyCaltopoInvitationAndMapLinksAreAccepted() {
        XCTAssertNotNil(CaltopoPersonalProbe.browserURL("https://caltopo.com/group/ABC123/signup/EXAMPLE"))
        XCTAssertEqual(CaltopoPersonalProbe.mapID("https://caltopo.com/m/ABC123#ll=1,2"), "ABC123")
        for value in ["http://caltopo.com/m/ABC123", "https://caltopo.com.evil.test/m/ABC123",
                      "https://user@caltopo.com/m/ABC123", "https://caltopo.com:443/m/ABC123",
                      "https://caltopo.com/api/v1/map/ABC123", "javascript:alert(1)",
                      "https://caltopo.com/m/ABC123/../../account/login"] {
            XCTAssertNil(CaltopoPersonalProbe.browserURL(value))
        }
        XCTAssertNil(CaltopoPersonalProbe.mapID("../account/login"))
        XCTAssertNil(CaltopoPersonalProbe.mapID("https://caltopo.com/group/ABC123/signup/EXAMPLE"))
    }
    func testHtmlAndMalformedSuccessNeverCountAsAccess() {
        for body in ["<html>Log in</html>", "{\"status\":\"error\"}", "{\"status\":\"ok\",\"result\":{}}"] {
            XCTAssertFalse(CaltopoPersonalProbe.result(code: 200, data: Data(body.utf8)).readable)
        }
    }
    func testAnonymousControlDistinguishesPublicMap() {
        let data = Data("{\"status\":\"ok\",\"result\":{\"state\":{\"features\":[]}}}".utf8)
        let valid = CaltopoPersonalProbe.result(code: 200, data: data)
        XCTAssertTrue(valid.readable)
        XCTAssertEqual(valid.featureCount, 0)
        XCTAssertTrue(CaltopoPersonalProbe.comparison(personal: valid, anonymous: valid).contains("Both requests"))
        XCTAssertTrue(CaltopoPersonalProbe.comparison(personal: valid, anonymous: .init(code: 403, featureCount: nil)).contains("anonymous access was denied"))
        XCTAssertTrue(CaltopoPersonalProbe.comparison(personal: valid, anonymous: .init(code: 500, featureCount: nil)).contains("inconclusive"))
    }
    func testCookieScopeAndExpiry() throws {
        func cookie(_ domain: String, _ path: String = "/", _ expires: Date? = nil) throws -> HTTPCookie {
            var properties: [HTTPCookiePropertyKey: Any] = [.name: "session", .value: "test-only", .domain: domain, .path: path, .secure: "TRUE"]
            if let expires { properties[.expires] = expires }
            return try XCTUnwrap(HTTPCookie(properties: properties))
        }
        let url = try XCTUnwrap(CaltopoPersonalProbe.endpoint("ABC123"))
        XCTAssertNotNil(CaltopoPersonalProbe.cookieHeader([try cookie(".caltopo.com")], for: url))
        XCTAssertNil(CaltopoPersonalProbe.cookieHeader([try cookie("accounts.google.com")], for: url))
        XCTAssertNil(CaltopoPersonalProbe.cookieHeader([try cookie("caltopo.com.evil.test")], for: url))
        XCTAssertNil(CaltopoPersonalProbe.cookieHeader([try cookie("caltopo.com", "/account")], for: url))
        XCTAssertNil(CaltopoPersonalProbe.cookieHeader([try cookie("caltopo.com", "/api/v1/ma")], for: url))
        XCTAssertNil(CaltopoPersonalProbe.cookieHeader([try cookie("caltopo.com", "/", .distantPast)], for: url))
        XCTAssertNil(CaltopoPersonalProbe.cookieHeader([try cookie("caltopo.com")], for: URL(string: "https://evil.test/api/v1/map/ABC123/since/0")!))
    }
}
