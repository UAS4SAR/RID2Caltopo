import Foundation

/// Local Drone Ownership, edited like a RID Map entry.
/// Standalone devices and a current config administrator may change it on this device.
/// Other organization members may read it. Organization distribution uses the existing
/// System Admin configuration upload.
public enum DroneOwnershipPolicy {
    public static let all = "all_detected"
    public static let streamingDevice = "streaming_device"
    public static let sharedIncident = "streaming_shared_incident"
    public static let standalone = "standalone"

    public struct Record: Equatable, Sendable {
        public var mode: String
        public var appliedMode: String
        public var staged: Bool
        public init(mode: String, appliedMode: String, staged: Bool) {
            self.mode = mode
            self.appliedMode = appliedMode
            self.staged = staged
        }
        public static let initial = Record(mode: all, appliedMode: all, staged: false)
    }

    public static func normalize(_ value: String?) -> String {
        switch value {
        case streamingDevice, sharedIncident: return value!
        default: return all
        }
    }

    public static func canChange(belongsToOrganization: Bool, configAdminVerified: Bool) -> Bool {
        !belongsToOrganization || configAdminVerified
    }

    public static func edit(_ record: Record, to mode: String) -> Record {
        let next = normalize(mode)
        return Record(mode: next, appliedMode: record.appliedMode, staged: next != record.appliedMode)
    }

    public static func receive(_ record: Record, published: String) -> Record {
        let incoming = normalize(published)
        if record.staged && incoming == record.appliedMode { return record }
        return Record(mode: incoming, appliedMode: incoming, staged: false)
    }

    public static func scope(enrolled: Bool, trackerPrefix: String) -> String {
        guard enrolled else { return standalone }
        let prefix = trackerPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmed = prefix.hasSuffix("/") ? String(prefix.dropLast()) : prefix
        return trimmed.isEmpty ? standalone : trimmed
    }
}
