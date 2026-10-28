import XCTest
@testable import R2CCore
import Foundation

final class CaltopoPersonalCatalogTests: XCTestCase {
    func testOnlyAuthenticationRejectionsOpenInteractiveLogin() {
        for status in [401, 403] {
            XCTAssertTrue(CaltopoPersonalCatalog.requiresInteractiveLogin(
                after: CaltopoLiveClientError.httpStatus(status, "Sign in required")
            ))
        }
        let retryable: [Error] = [
            URLError(.notConnectedToInternet), URLError(.timedOut), CancellationError(),
            CaltopoLiveClientError.invalidConfiguration,
            CaltopoLiveClientError.httpStatus(429, "Retry later"),
            CaltopoLiveClientError.httpStatus(500, "Server error"),
            CaltopoLiveClientError.httpStatus(503, "Unavailable"),
        ]
        for error in retryable {
            XCTAssertFalse(CaltopoPersonalCatalog.requiresInteractiveLogin(after: error))
        }
    }
    var fixture: Data { get throws {
        try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: "caltopo-personal-account-api", withExtension: "json", subdirectory: "Fixtures")))
    } }
    func changed(_ change: (inout [String: Any]) -> Void) throws -> Data {
        var root = try JSONSerialization.jsonObject(with: fixture) as! [String: Any]
        change(&root)
        return try JSONSerialization.data(withJSONObject: root)
    }
    func normalized(_ data: Data) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: CaltopoPersonalCatalog.normalize(data, expectedID: "USER01")) as! [String: Any]
    }
    func testPreservesFoldersBookmarksAndWorkspaceMembership() throws {
        let prepared = try CaltopoPersonalCatalog.prepare(fixture, expectedID: "USER01")
        XCTAssertEqual(prepared.username, "Test Pilot")
        let personal = try XCTUnwrap(prepared.maps.first { $0.id == "USER01" }?.children)
        let folder = try XCTUnwrap(personal.first { $0.title == "Searches" }?.children)
        XCTAssertEqual(Set(folder.map(\.id)), ["ABC123", "DEF456"])
        XCTAssertEqual(folder.first { $0.id == "DEF456" }?.map?.updatedMilliseconds, 456)
        XCTAssertEqual(prepared.maps.first { $0.title == "Incident Workspace" }?.children?.first?.children?.first?.id, "GHI789")
        XCTAssertEqual(prepared.maps.count, 2) // Unrelated accounts/owners are not catalog roots.
    }
    func testPhotoOwnershipUsesWritableWorkspaceOtherwisePersonal() throws {
        let root = try normalized(fixture)
        XCTAssertEqual(root["mediaOwners"] as? [String: String], ["ABC123":"USER01", "DEF456":"USER01", "GHI789":"WORK01"])
        let readonly = try changed { root in
            var result = root["result"] as! [String: Any]
            for key in ["groups", "rels"] {
                var entries = result[key] as! [[String: Any]]
                var props = entries[0]["properties"] as! [String: Any]; props["type"] = 10
                entries[0]["properties"] = props; result[key] = entries
            }
            root["result"] = result
        }
        XCTAssertEqual((try normalized(readonly))["mediaOwners"] as? [String: String], ["ABC123":"USER01", "DEF456":"USER01", "GHI789":"USER01"])
    }
    func testConflictingMembershipCannotEscalatePhotoOwnership() throws {
        let conflicting = try changed { root in
            var result = root["result"] as! [String: Any]; var groups = result["groups"] as! [[String: Any]]
            var props = groups[0]["properties"] as! [String: Any]; props["type"] = 10
            groups[0]["properties"] = props; result["groups"] = groups; root["result"] = result
        }
        XCTAssertEqual(((try normalized(conflicting))["mediaOwners"] as? [String: String])?["GHI789"], "USER01")
    }
    func testForeignMembershipDoesNotGrantWorkspaceAccess() throws {
        let foreign = try changed { root in
            var result = root["result"] as! [String: Any]
            for key in ["groups", "rels"] {
                var entries = result[key] as! [[String: Any]]
                var props = entries[0]["properties"] as! [String: Any]; props["accountId"] = "OTHER1"
                entries[0]["properties"] = props; result[key] = entries
            }
            root["result"] = result
        }
        XCTAssertEqual((try normalized(foreign))["accounts"].flatMap { $0 as? [[String: Any]] }?.count, 1)
    }
    func testRejectsChangedOrMalformedAccountIdentity() throws {
        for id in ["ABSENT", "../USER01", "", "USER010"] {
            XCTAssertThrowsError(try CaltopoPersonalCatalog.normalize(fixture, expectedID: id))
        }
    }
    func testRejectsErrorsLegacyCatalogsAndMalformedResponses() throws {
        for body in [#"{"account":{}}"#, #"{"status":"error","result":{"accounts":[],"features":[]}}"#,
                     #"{"status":"ok","error":"denied","result":{}}"#,
                     #"{"status":"ok","result":{"account":{"id":"USER01"}}}"#, "<html>Sign in</html>"] {
            XCTAssertThrowsError(try CaltopoPersonalCatalog.normalize(Data(body.utf8), expectedID: "USER01"))
        }
    }
    func testEmptyCatalogRetainsIdentityAndDisplayFallback() throws {
        let data = Data(#"{"status":"ok","result":{"accounts":[{"id":"USER01","properties":{"class":"UserAccount"}}],"features":[]}}"#.utf8)
        let prepared = try CaltopoPersonalCatalog.prepare(data, expectedID: "USER01")
        XCTAssertEqual(prepared.username, "USER01"); XCTAssertTrue(prepared.maps.isEmpty)
    }
}
