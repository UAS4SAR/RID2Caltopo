import Foundation
import Testing
@testable import R2CCore

struct ConfigurationBackupPolicyTests {
    @Test func oldPayloadCannotTransferCredentialsOrUnknownFields() throws {
        let secret = "SYNTHETIC_ACCESS_SECRET"
        let old: [String: Any] = ["caltopo": ["credential_id": secret, "credential_secret": secret, "connect_key": secret, "map_id": "map-1"],
            "organization": ["tracker_api_key": secret, "tracker_enrollment_url": secret,
                "faa": ["client_secret": secret], "mutual_aid_template": ["credential_secret": secret],
                "proximity_feet": 250, "unknown": secret], "unknown": secret,
            "rid_mappings": [["remote_id": "aircraft-1", "ownerName": "Test Pilot", "credential_secret": secret]]]
        let restored = try ConfigurationBackupPolicy.preferences(from: JSONSerialization.data(withJSONObject: old))
        let safe = try ConfigurationBackupPolicy.payload(restored)
        #expect(!String(decoding: safe, as: UTF8.self).contains(secret))
        #expect(restored.mapID == "map-1")
        #expect(restored.organization == ["proximity_feet": 250])
        #expect(restored.mappings.first?.remoteID == "aircraft-1")
    }

    @Test func encryptedSettingsRoundTripWithoutCredentialFields() throws {
        let input = ConfigurationBackupPolicy.Preferences(mapID: "map-1", mapTitle: "Test map",
            organization: ["proximity_feet": 120, "unknown": 999], mappings: [])
        let bytes = try ConfigurationBackupPolicy.encrypted(input, passphrase: "synthetic-passphrase")
        let restored = try ConfigurationBackupPolicy.decrypted(bytes, passphrase: "synthetic-passphrase")
        #expect(restored.mapID == "map-1")
        #expect(restored.organization == ["proximity_feet": 120])
        #expect(throws: (any Error).self) { try ConfigurationBackupPolicy.decrypted(bytes, passphrase: "wrong-passphrase") }
    }

    @Test func legacyCredentialPackageIsRejectedAndMapOnlyPackageRemainsValid() throws {
        for profile in ["credential-ciphertext", ["credential_secret": "synthetic"]] as [Any] {
            let data = try JSONSerialization.data(withJSONObject: ["format": "rid2caltopo_mutual_aid_package", "profile_enc": profile])
            #expect(throws: (any Error).self) { try ConfigurationBackupPolicy.validateMapPackage(data) }
        }
        let map = try JSONSerialization.data(withJSONObject: ["format": "rid2caltopo_mutual_aid_package", "profile_enc": "", "tile_entries": []])
        try ConfigurationBackupPolicy.validateMapPackage(map)
    }
}
