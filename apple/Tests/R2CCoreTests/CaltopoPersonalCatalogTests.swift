import XCTest
@testable import R2CCore
import Foundation

final class CaltopoPersonalCatalogTests: XCTestCase {
    let fixture = #"{"status":"ok","result":{"account":{"id":"USER01","folders":[{"id":"FOLDER1","properties":{"class":"UserFolder","label":"Searches"}}],"tenants":[{"id":"ABC123","properties":{"class":"CollaborativeMap","title":"Private test","folderId":"FOLDER1","updated":123}}],"bookmarks":[{"id":"REL001","properties":{"class":"UserAccountMapRel","mapId":"DEF456","title":"Shared map","mapUpdated":456}}],"groupAccounts":[{"id":"WORK01","alias":"Incident Workspace","isWorkspace":true,"tenants":[{"id":"GHI789","properties":{"class":"CollaborativeMap","title":"Incident"}}]},{"id":"OTHER1","alias":"Other","isWorkspace":false,"tenants":[{"id":"JKL123","properties":{"class":"CollaborativeMap","title":"Excluded"}}]}]}}}"#
    func testPhotoOwnershipUsesWritableWorkspaceOtherwisePersonal() throws {
        let writable = fixture.replacingOccurrences(of: "\"isWorkspace\":true", with: "\"isWorkspace\":true,\"type\":16")
        let data = try CaltopoPersonalCatalog.normalize(Data(writable.utf8), expectedID: "USER01", username: "pilot")
        let root = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let owners = root["mediaOwners"] as! [String: String]
        XCTAssertEqual(owners["ABC123"], "USER01")
        XCTAssertEqual(owners["GHI789"], "WORK01")
        let readonly = writable.replacingOccurrences(of: "\"type\":16", with: "\"type\":10")
        let other = try CaltopoPersonalCatalog.normalize(Data(readonly.utf8), expectedID: "USER01", username: "pilot")
        XCTAssertEqual((try JSONSerialization.jsonObject(with: other) as! [String: Any])["mediaOwners"] as? [String: String], ["ABC123":"USER01", "DEF456":"USER01", "GHI789":"USER01"])
    }
    func testPreservesFoldersSharedMapsAndWorkspaceMembership() throws {
        let data = try CaltopoPersonalCatalog.normalize(Data(fixture.utf8), expectedID: "USER01", username: "pilot")
        let roots = try CaltopoTeamMapDecoder.decode(data: data)
        let personal = try XCTUnwrap(roots.first { $0.title == "pilot" }?.children)
        let folder = try XCTUnwrap(personal.first { $0.title == "Searches" }?.children)
        XCTAssertEqual(folder.first?.id, "ABC123")
        let shared = try XCTUnwrap(personal.first { $0.id == "DEF456" }?.map)
        XCTAssertEqual(shared.updatedMilliseconds, 456)
        XCTAssertEqual(roots.first { $0.title == "Incident Workspace" }?.children?.first?.id, "GHI789")
        XCTAssertEqual(roots.count, 2)
    }
    func testRejectsChangedOrMalformedAccountIdentity() {
        for (id, username) in [("OTHER1", "pilot"), ("../USER01", "pilot"), ("USER01", "")] {
            XCTAssertThrowsError(try CaltopoPersonalCatalog.normalize(Data(fixture.utf8), expectedID: id, username: username))
        }
    }
    func testRejectsServerErrorsAndUnwrappedOrMalformedResponses() throws {
        for body in [#"{"account":{}}"#, #"{"status":"error","result":{"account":{}}}"#,
                     #"{"status":"ok","error":"denied","result":{"account":{}}}"#, "<html>Sign in</html>"] {
            XCTAssertThrowsError(try CaltopoPersonalCatalog.account(Data(body.utf8)))
        }
        XCTAssertEqual(try CaltopoPersonalCatalog.account(Data(fixture.utf8))["id"] as? String, "USER01")
    }

}
