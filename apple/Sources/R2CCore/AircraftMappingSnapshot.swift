import Foundation

/// Prepared on a worker. Original records survive duplicate/conflict recovery unchanged.
public struct AircraftMappingSnapshot: Sendable {
    public let records: [[String: String]]
    public let identities: [String: RidAircraftIdentity]
    public let orderedIdentities: [RidAircraftIdentity]
    /// Includes conflicting records for explicit editing and lossless backup.
    public let allIdentities: [RidAircraftIdentity]
    public let duplicateIDs: [String]
    public let conflictingIDs: [String]
    public let recoveryMessage: String?
    public let preferredPilotCallsign: String
    private let conflictDescription: String

    public init(records: [[String: String]], preferredPilotCallsign: String? = nil) {
        self.preferredPilotCallsign = PilotDisplayPreference.normalizePilotCallsign(preferredPilotCallsign) ?? ""
        self.records = records
        var identities: [String: RidAircraftIdentity] = [:]
        var all: [RidAircraftIdentity] = []
        var duplicates: Set<String> = []
        var conflicts: Set<String> = []
        for entry in records {
            guard let remoteID = entry["remoteID"]?.trimmingCharacters(in: .whitespacesAndNewlines), !remoteID.isEmpty else { continue }
            let identity = RidAircraftIdentity(remoteID: remoteID,
                organization: entry["organization"] ?? "", ownerName: entry["ownerName"] ?? entry["owner"] ?? "",
                pilotCallsign: entry["ownerCallsign"] ?? RidAircraftIdentity.guessPilotCallsign(
                    mappedID: entry["mappedID"] ?? "", model: entry["model"] ?? "", remoteID: remoteID),
                droneDescription: entry["model"] ?? "", mappedIDOverride: entry["mappedID"],
                readiness: AircraftReadiness.decode(entry["readiness"]))
            all.append(identity)
            if let prior = identities[remoteID] {
                duplicates.insert(remoteID)
                if prior != identity { conflicts.insert(remoteID) }
            } else { identities[remoteID] = identity }
        }
        for id in conflicts { identities.removeValue(forKey: id) }
        self.identities = identities
        orderedIdentities = identities.values.sorted { $0.remoteID < $1.remoteID }
        allIdentities = all
        duplicateIDs = duplicates.sorted()
        conflictingIDs = conflicts.sorted()
        let preview = conflictingIDs.prefix(5).map { String(decoding: $0.utf8.prefix(64), as: UTF8.self) }.joined(separator: ", ")
        let remainder = conflicts.count > 5 ? " (+\(conflicts.count - 5) more)" : ""
        conflictDescription = "Conflicting aircraft mappings for \(preview)\(remainder). Existing mappings were preserved. Correct the duplicate IDs and try again."
        if duplicates.isEmpty { recoveryMessage = nil }
        else if conflicts.isEmpty {
            recoveryMessage = "Repeated aircraft IDs were combined for display. Original saved mappings were preserved."
        } else {
            recoveryMessage = "Conflicting aircraft IDs were left unassigned: \(preview)\(remainder). Original mappings were preserved. Review Aircraft Mappings before using these aircraft."
        }
    }

    public func requireUnambiguous() throws {
        guard conflictingIDs.isEmpty else { throw Conflict(ids: conflictingIDs, summary: conflictDescription) }
    }

    public struct Conflict: LocalizedError, Sendable {
        public let ids: [String]
        private let summary: String
        fileprivate init(ids: [String], summary: String) { self.ids = ids; self.summary = summary }
        public var errorDescription: String? { summary }
    }
}
