#if DEBUG
import Foundation
import R2CCore

/// Explicit administrator capture of the app-wide registration for release builds.
/// Uses the same encoded transport as FAA configuration; no plaintext file/log.
public enum PilotCloudDeployment {
    public static func exportAppRegistration(requestID: String) throws {
        guard UUID(uuidString: requestID) != nil else { throw MappingMission.Failure("Invalid registration capture request.") }
        let folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
            .appendingPathComponent("PilotCloudProvisioning", isDirectory: true)
        guard let credentials = try PilotCloudCredentialStore.load() else { throw MappingMission.Failure("Saved DJI app registration is unavailable.") }
        let bytes = try PilotAppRegistration.encode(credentials)
        try bytes.write(to: folder.appendingPathComponent("app-registration.json"), options: [.atomic, .completeFileProtection])
        try pilotJSON(["requestID": requestID, "registrationSha256": pilotHash(bytes)], to: folder.appendingPathComponent("app-registration-receipt.json"))
    }
}
#endif
