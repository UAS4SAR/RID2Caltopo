import Foundation

/// Compact durable per-flight publication disposition.
///
/// Awaiting-map rows are pruned after local/publish/confirm decisions, so recovery and
/// tracker upload cannot rely on that journal alone. This ledger survives those prunes and
/// gates every publish path. Mirrors Android `FlightPublicationLedger`.
public actor FlightPublicationLedger {
    public enum Disposition: String, Sendable, Equatable {
        case declined
        case suppressed
        case localArchived = "local_archived"
        case published
    }

    private var entries: [String: Disposition] = [:]
    private let fileURL: URL?
    private var loaded = false

    public init(fileURL: URL? = nil) {
        self.fileURL = fileURL
    }

    public func remember(flightID: String, disposition: Disposition) throws {
        let id = flightID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        try load()
        let prior = entries[id]
        let next = Self.merge(prior: prior, next: disposition)
        if prior == next { return }
        entries[id] = next
        try save()
    }

    public func disposition(flightID: String) throws -> Disposition? {
        let id = flightID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }
        try load()
        return entries[id]
    }

    public func blocksPublication(flightID: String) throws -> Bool {
        try disposition(flightID: flightID) != nil
    }

    public func blocksAwaitingMapPromotion(flightID: String) throws -> Bool {
        try blocksPublication(flightID: flightID)
    }

    public func isDeclinedOrSuppressed(flightID: String) throws -> Bool {
        guard let value = try disposition(flightID: flightID) else { return false }
        return value == .declined || value == .suppressed || value == .localArchived
    }

    public func allForTesting() throws -> [String: Disposition] {
        try load()
        return entries
    }

    public func clearForTesting() throws {
        entries = [:]
        loaded = fileURL != nil
        if fileURL != nil { try save() }
    }

    private static func merge(prior: Disposition?, next: Disposition) -> Disposition {
        if prior == .published { return .published }
        if next == .published { return .published }
        if prior == .declined || next == .declined { return .declined }
        if prior == .localArchived || next == .localArchived { return .localArchived }
        return next
    }

    private func load() throws {
        if loaded { return }
        defer { loaded = true }
        guard let fileURL, FileManager.default.fileExists(atPath: fileURL.path) else {
            entries = [:]
            return
        }
        let data = try Data(contentsOf: fileURL)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: String] else {
            entries = [:]
            return
        }
        var parsed: [String: Disposition] = [:]
        for (key, value) in object {
            if let disposition = Disposition(rawValue: value) {
                parsed[key] = disposition
            }
        }
        entries = parsed
    }

    private func save() throws {
        guard let fileURL else { return }
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let object = Dictionary(uniqueKeysWithValues: entries.map { ($0.key, $0.value.rawValue) })
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        try OperationalSafeFileWriter.replace(fileURL, with: data)
    }
}
