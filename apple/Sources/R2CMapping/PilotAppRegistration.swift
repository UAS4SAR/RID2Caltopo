import Foundation
import CryptoKit
import R2CCore

/// App-wide DJI registration, packaged using the same transport obfuscation as
/// FAA configuration. Each installation then saves it in its own protected store.
/// The packaged client registration is not a device-enrollment secret.
public enum PilotAppRegistration {
    public static func decode(_ bytes: Data) throws -> PilotCloudCredentials {
        guard bytes.count <= 32768,
              let value = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              Set(value.keys) == Set(["type", "version", "encoding", "enc", "sha256"]),
              value["type"] as? String == "r2c_dji_app_registration",
              let version = value["version"] as? NSNumber, CFGetTypeID(version) != CFBooleanGetTypeID(),
              value["version"] as? Int == 1,
              value["encoding"] as? String == "r2c-config",
              let encoded = value["enc"] as? String,
              let clear = try OrgConfigTokenCodec.decryptPayload(encoded).data(using: .utf8),
              pilotHash(clear) == value["sha256"] as? String,
              let fields = try JSONSerialization.jsonObject(with: clear) as? [String: Any],
              Set(fields.keys) == Set(["appId", "appKey", "appLicense"]),
              let appId = fields["appId"] as? String, let appKey = fields["appKey"] as? String,
              let license = fields["appLicense"] as? String else {
            throw MappingMission.Failure("The app's DJI registration is missing or invalid. Install a complete app build.")
        }
        return try PilotCloudCredentials(appId: appId, appKey: appKey, appLicense: license)
    }
    static func encode(_ credentials: PilotCloudCredentials) throws -> Data {
        var clear = try JSONEncoder().encode(credentials)
        defer { clear.resetBytes(in: 0..<clear.count) }
        return try JSONSerialization.data(withJSONObject: ["type": "r2c_dji_app_registration", "version": 1,
            "encoding": "r2c-config", "enc": OrgConfigTokenCodec.encryptPayload(String(decoding: clear, as: UTF8.self)),
            "sha256": pilotHash(clear)])
    }
    @discardableResult
    static func install(_ bytes: Data, receipt: URL, load: () throws -> PilotCloudCredentials?, save: (PilotCloudCredentials) throws -> Void) throws -> String {
        let credentials = try decode(bytes), hash = pilotHash(bytes)
        let hasCredentials = try load() != nil
        if (try? String(contentsOf: receipt, encoding: .utf8)) != hash || !hasCredentials {
            try save(credentials)
            // Record only after the secure store has accepted the registration.
            // iOS Data Protection is retained on device. A command-line macOS
            // host can reject this protection class even in a writable folder.
            #if os(iOS)
            let options: Data.WritingOptions = [.atomic, .completeFileProtection]
            #else
            let options: Data.WritingOptions = [.atomic]
            #endif
            try Data(hash.utf8).write(to: receipt, options: options)
        }
        return hash
    }
    static func prepare(in folder: URL) throws -> Bool {
        guard let source = Bundle.main.url(forResource: "registration", withExtension: "json", subdirectory: "dji-registration") else { return false }
        guard (try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 32768 else {
            throw MappingMission.Failure("The app's DJI registration is invalid.")
        }
        let bytes = try Data(contentsOf: source)
        let hash = try install(bytes, receipt: folder.appendingPathComponent("app-registration.sha256"),
            load: { try PilotCloudCredentialStore.loadStored() }, save: { try PilotCloudCredentialStore.save($0) })
        try pilotJSON(["configured": true, "registration": "app", "registrationSha256": hash], to: folder.appendingPathComponent("status.json"))
        return true
    }
}
