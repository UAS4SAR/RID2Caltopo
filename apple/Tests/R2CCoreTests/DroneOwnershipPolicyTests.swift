import Foundation
import Testing
@testable import R2CCore

@Suite struct DroneOwnershipPolicyTests {
    @Test func standaloneEditsDirectlyAndOrganizationMembersNeedConfigAdmin() {
        #expect(DroneOwnershipPolicy.canChange(belongsToOrganization: false, configAdminVerified: false))
        #expect(DroneOwnershipPolicy.canChange(belongsToOrganization: true, configAdminVerified: true))
        #expect(!DroneOwnershipPolicy.canChange(belongsToOrganization: true, configAdminVerified: false))
    }

    @Test func localEditTakesEffectAndARefreshDoesNotEraseIt() {
        let edited = DroneOwnershipPolicy.edit(.initial, to: DroneOwnershipPolicy.streamingDevice)
        #expect(edited.mode == DroneOwnershipPolicy.streamingDevice)
        #expect(edited.staged)
        #expect(DroneOwnershipPolicy.receive(edited, published: DroneOwnershipPolicy.all) == edited)
        let published = DroneOwnershipPolicy.receive(edited, published: DroneOwnershipPolicy.sharedIncident)
        #expect(published.mode == DroneOwnershipPolicy.sharedIncident)
        #expect(!published.staged)
    }

    @Test func organizationScopesStaySeparateFromStandalone() {
        #expect(DroneOwnershipPolicy.scope(enrolled: false, trackerPrefix: "https://r2c-tracker.com/ncssar") == DroneOwnershipPolicy.standalone)
        #expect(DroneOwnershipPolicy.scope(enrolled: true, trackerPrefix: "https://r2c-tracker.com/ncssar/") == "https://r2c-tracker.com/ncssar")
        #expect(DroneOwnershipPolicy.scope(enrolled: true, trackerPrefix: "https://r2c-tracker.com/other") == "https://r2c-tracker.com/other")
    }

    @Test func snapshotIncludesTheLocalValueAndDoesNotRequireADedicatedSave() async throws {
        let bytes = try await Task.detached {
            try ManagedOrganizationWireSnapshot.encode(
                teams: .init(teamID: "team", credentialID: "id", secret: "secret", connectKey: "", domain: "caltopo.com"),
                trackFolder: "Tracks", mutualAid: nil, identities: [], published: .empty,
                appVersion: "2.5.10", appBuild: 439, droneOwnership: DroneOwnershipPolicy.streamingDevice)
        }.value
        let root = try #require(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        #expect(root["droneOwnership"] as? String == DroneOwnershipPolicy.streamingDevice)
        #expect(root["organization-config/drone-ownership"] == nil)
    }
}
