import Foundation
import CryptoKit

/// The only portable configuration data. Authentication and service access are installation-local.
public enum ConfigurationBackupPolicy {
    public static let preferenceKeys = ["proximity_feet", "minimum_track_distance_feet", "new_track_delay_seconds", "bridge_check_distance_feet", "maximum_idle_minutes"]
    public static let legacyMessage = "Legacy credential transfers are no longer supported. Scan a tracker enrollment QR."

    public struct Preferences: Sendable {
        public let mapID: String
        public let mapTitle: String
        public let organization: [String: Int]
        public let mappings: [OrgConfigRIDMapping]
        public init(mapID: String, mapTitle: String, organization: [String: Int], mappings: [OrgConfigRIDMapping]) {
            self.mapID = mapID; self.mapTitle = mapTitle; self.organization = organization; self.mappings = mappings
        }
    }

    public static func payload(_ input: Preferences) throws -> Data {
        let mappings: [[String: Any]] = input.mappings.map {
            ["remote_id": $0.remoteID, "mapped_id": $0.mappedID, "organization": $0.organization,
             "model": $0.model, "owner": $0.owner, "ownerName": $0.ownerName,
             "ownerCallsign": $0.ownerCallsign, "readiness": $0.readiness.dictionary]
        }
        return try JSONSerialization.data(withJSONObject: [
            "version": 2, "caltopo": ["map_id": input.mapID, "map_title": input.mapTitle],
            "organization": input.organization.filter { preferenceKeys.contains($0.key) },
            "rid_mappings": mappings,
        ], options: [.sortedKeys])
    }

    /// Filters old backups too. No credential, endpoint, token, or unknown field is returned.
    public static func preferences(from data: Data) throws -> Preferences {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.invalidBackup }
        let caltopo = root["caltopo"] as? [String: Any] ?? [:]
        let organization = root["organization"] as? [String: Any] ?? [:]
        let values = Dictionary(uniqueKeysWithValues: preferenceKeys.compactMap { key in
            (organization[key] as? NSNumber).map { (key, $0.intValue) }
        })
        let mappings = (root["rid_mappings"] as? [[String: Any]] ?? []).compactMap { item -> OrgConfigRIDMapping? in
            guard let id = item["remote_id"] as? String, !id.isEmpty else { return nil }
            return .init(remoteID: id, mappedID: item["mapped_id"] as? String ?? "",
                organization: item["organization"] as? String ?? "", model: item["model"] as? String ?? "",
                owner: item["owner"] as? String ?? "", ownerName: item["ownerName"] as? String ?? "",
                ownerCallsign: item["ownerCallsign"] as? String ?? "", readiness: AircraftReadiness.decode(item["readiness"]))
        }
        return .init(mapID: caltopo["map_id"] as? String ?? "", mapTitle: caltopo["map_title"] as? String ?? "", organization: values, mappings: mappings)
    }

    public static func encrypted(_ input: Preferences, passphrase: String) throws -> Data {
        guard passphrase.count >= 8 else { throw Failure.invalidBackup }
        let salt = Data((0..<16).map { _ in UInt8.random(in: .min ... .max) })
        let key = SymmetricKey(data: SHA256.hash(data: salt + Data(passphrase.utf8)))
        let sealed = try AES.GCM.seal(payload(input), using: key)
        guard let combined = sealed.combined else { throw Failure.invalidBackup }
        return try JSONSerialization.data(withJSONObject: ["format": "rid2caltopo_apple_config_backup", "version": 2,
            "salt": salt.base64EncodedString(), "sealed": combined.base64EncodedString()], options: [.sortedKeys])
    }

    public static func decrypted(_ data: Data, passphrase: String) throws -> Preferences {
        guard passphrase.count >= 8,
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["format"] as? String == "rid2caltopo_apple_config_backup",
              let saltText = root["salt"] as? String, let salt = Data(base64Encoded: saltText),
              let sealedText = root["sealed"] as? String, let sealed = Data(base64Encoded: sealedText)
        else { throw Failure.invalidBackup }
        let key = SymmetricKey(data: SHA256.hash(data: salt + Data(passphrase.utf8)))
        return try preferences(from: AES.GCM.open(AES.GCM.SealedBox(combined: sealed), using: key))
    }

    public static func validateMapPackage(_ data: Data) throws {
        guard let manifest = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              manifest["format"] as? String == "rid2caltopo_mutual_aid_package",
              (manifest["profile_enc"] == nil || (manifest["profile_enc"] as? String) == "")
        else { throw Failure.legacyCredentials }
    }

    public enum Failure: LocalizedError {
        case invalidBackup, legacyCredentials
        public var errorDescription: String? {
            switch self {
            case .invalidBackup: "The settings backup or passphrase is invalid."
            case .legacyCredentials: "This legacy package contains credentials. Ask for a map-only package and enroll through the tracker."
            }
        }
    }
}
