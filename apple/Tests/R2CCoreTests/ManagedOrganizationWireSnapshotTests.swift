import XCTest
@testable import R2CCore

final class ManagedOrganizationWireSnapshotTests: XCTestCase {
    func testWireSnapshotRetainsSavedTeamsDomainAndCredentialFields() async throws {
        try await Task.detached {
            let teams = OperationalTeamsCredentialDraft(teamID: "team", credentialID: "credential",
                secret: "secret", connectKey: "connect", domain: "sartopo.com")
            let bytes = try ManagedOrganizationWireSnapshot.encode(teams: teams, trackFolder: "Incident tracks",
                mutualAid: nil, identities: [], published: .empty, appVersion: "2.5.5", appBuild: 432)
            let root = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
            let encrypted = try XCTUnwrap(root["organizationCaltopoEnc"] as? String)
            let clear = try OrgConfigTokenCodec.decryptPayload(encrypted)
            let credentials = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(clear.utf8)) as? [String: Any])
            XCTAssertEqual(credentials["domain_and_port"] as? String, "sartopo.com")
            XCTAssertEqual(credentials["credential_secret"] as? String, "secret")
            XCTAssertEqual(credentials["track_folder"] as? String, "Incident tracks")
            XCTAssertEqual(root["sourceAppBuild"] as? Int, 432)
        }.value
    }

    func testLargeSnapshotPreparationRunsOnWorkerAndPreservesAllAircraft() async throws {
        try await Task.detached {
            XCTAssertFalse(Thread.isMainThread)
            let identities = (0..<20_000).map { RidAircraftIdentity(remoteID: "\($0)", organization: "org",
                pilotCallsign: "1SAR7", droneDescription: "Test") }
            let bytes = try ManagedOrganizationWireSnapshot.encode(
                teams: .init(teamID: "team", credentialID: "id", secret: "secret", connectKey: "", domain: "caltopo.com"),
                trackFolder: "Tracks", mutualAid: nil, identities: identities, published: .empty, appVersion: "2.5.5", appBuild: 432)
            let root = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
            XCTAssertEqual((root["droneSpecs"] as? [[String: Any]])?.count, 20_000)
        }.value
    }
}
