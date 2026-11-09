import Foundation

/// Pilot and claim facts that must survive a crash. Absent facts stay absent.
///
/// Unconfirmed flights follow `CurrentFlightPublicationGate`: they stay local and
/// do not publish. The earlier "option 1" wording was not recovered from the repo;
/// this matches the explicit gate already on both platforms and does not treat an
/// unselected flight as verified ownership.
public struct FlightOwnershipContext: Sendable, Equatable, Codable {
    public static let mine = "mine"
    public static let notMine = "not_mine"
    public static let ignored = "ignored"
    public static let unconfirmed = "unconfirmed"
    public static let verified = "verified"
    public static let pending = "pending"
    public static let rejected = "rejected"
    public static let conflict = "conflict"
    public static let expired = "expired"
    public static let none = "none"

    public var known: Bool
    public var confirmation: String
    public var claim: String
    public var pilot: String
    public var organization: String
    public var model: String
    public var mappedID: String
    public var localOnly: Bool

    public init(known: Bool, confirmation: String = "", claim: String = "", pilot: String = "",
                organization: String = "", model: String = "", mappedID: String = "", localOnly: Bool = false) {
        self.known = known
        self.confirmation = confirmation
        self.claim = claim
        self.pilot = pilot
        self.organization = organization
        self.model = model
        self.mappedID = mappedID
        self.localOnly = localOnly
    }

    public static let legacy = FlightOwnershipContext(known: false)

    public var recordedPilot: String { known && confirmation == Self.mine ? pilot.trimmingCharacters(in: .whitespacesAndNewlines) : "" }
    public var adverseClaim: Bool { Self.isAdverseClaim(claim) }
    public var requiresHold: Bool { !known || (confirmation == Self.mine && adverseClaim) }
    public var mayAutoPublish: Bool { known && confirmation == Self.mine && !adverseClaim && !localOnly }
    public var blocksOperatorPublish: Bool { known && !requiresHold && !mayAutoPublish }

    public static func isAdverseClaim(_ claim: String) -> Bool {
        [pending, rejected, conflict, expired].contains(claim)
    }

    public static func capture(confirmed: Bool, ignored: Bool, localOnly: Bool, claim: String,
                               pilot: String, organization: String, model: String, mappedID: String) -> FlightOwnershipContext {
        let confirmation: String
        if ignored { confirmation = Self.ignored }
        else if localOnly { confirmation = Self.notMine }
        else if confirmed { confirmation = Self.mine }
        else { confirmation = Self.unconfirmed }
        let normalized = [verified, pending, rejected, conflict, expired, none].contains(claim) ? claim : none
        let mine = confirmation == Self.mine
        return FlightOwnershipContext(
            known: true, confirmation: confirmation, claim: normalized,
            pilot: mine ? pilot.trimmingCharacters(in: .whitespacesAndNewlines) : "",
            organization: organization.trimmingCharacters(in: .whitespacesAndNewlines),
            model: model.trimmingCharacters(in: .whitespacesAndNewlines),
            mappedID: mappedID.trimmingCharacters(in: .whitespacesAndNewlines),
            localOnly: !mine)
    }

    public static func reviewMessage(known: Bool, claim: String, pilot: String, publicationBlocked: Bool) -> String {
        if publicationBlocked {
            return "This flight ended without Mine selected. It stays on this device and will not be published or given a pilot."
        }
        let reason: String
        if !known { reason = "This flight was recovered without a recorded ownership claim." }
        else if claim == conflict { reason = "Tracker reported a conflicting claim. Publishing will not adopt the other pilot." }
        else if claim == rejected { reason = "Tracker rejected this ownership claim." }
        else if claim == expired { reason = "The tracker ownership lease expired before it was verified." }
        else { reason = "Tracker did not verify ownership before this flight ended." }
        let who = pilot.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "No pilot was recorded. Publishing will not name an RPIC."
            : "Recorded pilot: \(pilot)."
        return "\(reason) \(who) Confirm this flight before publishing."
    }

    public func journalRow() -> [String: Any] {
        var row: [String: Any] = ["type": "context", "known": known]
        guard known else { return row }
        row["confirmation"] = confirmation
        row["claim"] = claim
        row["localOnly"] = localOnly
        if !pilot.isEmpty { row["pilot"] = pilot }
        if !organization.isEmpty { row["org"] = organization }
        if !model.isEmpty { row["model"] = model }
        if !mappedID.isEmpty { row["mappedId"] = mappedID }
        return row
    }

    public static func from(journalRow row: [String: Any]) -> FlightOwnershipContext {
        guard (row["type"] as? String) == "context", (row["known"] as? Bool) == true else { return .legacy }
        return FlightOwnershipContext(
            known: true,
            confirmation: row["confirmation"] as? String ?? "",
            claim: row["claim"] as? String ?? none,
            pilot: row["pilot"] as? String ?? "",
            organization: row["org"] as? String ?? "",
            model: row["model"] as? String ?? "",
            mappedID: row["mappedId"] as? String ?? "",
            localOnly: row["localOnly"] as? Bool ?? false)
    }
}

/// Flight ids whose operator released an unverified Mine claim for tracker upload.
public enum OwnershipReviewReleaseFile {
    public static func allowsTrackerUpload(flightID: String, fileURL: URL) -> Bool {
        guard let ids = try? read(fileURL: fileURL) else { return false }
        return ids.contains(flightID)
    }

    public static func allowTrackerUpload(flightID: String, fileURL: URL) throws {
        let id = flightID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        var ids = (try? read(fileURL: fileURL)) ?? []
        guard ids.insert(id).inserted else { return }
        try write(ids, fileURL: fileURL)
    }

    private static func read(fileURL: URL) throws -> Set<String> {
        let data = try Data(contentsOf: fileURL)
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let values = object?["trackerUploads"] as? [String] ?? []
        return Set(values.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })
    }

    private static func write(_ ids: Set<String>, fileURL: URL) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONSerialization.data(withJSONObject: ["trackerUploads": Array(ids).sorted()], options: [.sortedKeys])
        try data.write(to: fileURL, options: .atomic)
    }
}
