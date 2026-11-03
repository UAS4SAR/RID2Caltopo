import Foundation

/// Compact durable per-flight publication disposition.
///
/// Awaiting-map rows are pruned after local/publish/confirm decisions, so recovery and
/// tracker upload cannot rely on that journal alone. This ledger survives those prunes and
/// gates every publish path. Mirrors Android `FlightPublicationLedger`.
///
/// In-memory dispositions update synchronously; disk writes run on [OperationalWorkQueue]
/// so MainActor callers never block on file I/O.
public actor FlightPublicationLedger {
    public enum Disposition: String, Sendable, Equatable {
        case declined
        case discarded
        case suppressed
        case localArchived = "local_archived"
        case published
    }

    private var entries: [String: Disposition] = [:]
    private let fileURL: URL?
    private var loaded = false
    private let disk = OperationalWorkQueue(label: "org.ncssar.flight-publication-ledger.disk")
    private var persistTask: Task<Void, Never>?

    public init(fileURL: URL? = nil) {
        self.fileURL = fileURL
    }

    public func remember(flightID: String, disposition: Disposition) throws {
        let id = flightID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        // Memory first — publication gates see the refusal before disk completes.
        let prior = entries[id]
        let next = Self.merge(prior: prior, next: disposition)
        if prior == next, entries[id] != nil { return }
        entries[id] = next
        schedulePersist()
    }

    public func disposition(flightID: String) throws -> Disposition? {
        let id = flightID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }
        if let value = entries[id] { return value }
        if !loaded { try loadFromDiskMergingMemory() }
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
        return value == .declined || value == .discarded || value == .suppressed || value == .localArchived
    }

    public func allForTesting() throws -> [String: Disposition] {
        if !loaded { try loadFromDiskMergingMemory() }
        return entries
    }

    public func clearForTesting() throws {
        entries = [:]
        loaded = fileURL != nil
        if let fileURL {
            try Self.write(entries: [:], fileURL: fileURL)
        }
    }

    /// Drain scheduled persist for tests that assert durable file contents.
    public func flushForTesting() async throws {
        persistTask?.cancel()
        persistTask = nil
        if !loaded { try await loadMergingOnDiskQueue() }
        let snapshot = entries
        guard let fileURL else { return }
        try await disk.run {
            try Self.write(entries: snapshot, fileURL: fileURL)
        }
    }

    private static func merge(prior: Disposition?, next: Disposition) -> Disposition {
        if prior == .published { return .published }
        if next == .published { return .published }
        // Discard and Don't Publish are permanent operator refusals; never downgrade them.
        if prior == .discarded || next == .discarded { return .discarded }
        if prior == .declined || next == .declined { return .declined }
        if prior == .localArchived || next == .localArchived { return .localArchived }
        return next
    }

    private func schedulePersist() {
        persistTask?.cancel()
        let snapshot = entries
        let fileURL = self.fileURL
        persistTask = Task { [disk] in
            do {
                try await disk.run {
                    guard let fileURL else { return }
                    // Merge disk under the worker so concurrent remember() memory wins on overlap.
                    var merged = (try? Self.read(fileURL: fileURL)) ?? [:]
                    for (key, value) in snapshot {
                        merged[key] = Self.merge(prior: merged[key], next: value)
                    }
                    try Self.write(entries: merged, fileURL: fileURL)
                }
                loaded = true
            } catch {
                // Soft-fail: in-memory gate already blocks publication.
            }
        }
    }

    private func loadFromDiskMergingMemory() throws {
        guard let fileURL else {
            loaded = true
            return
        }
        // Synchronous fallback for tests without awaiting the queue; production prefer async path.
        let diskEntries = (try? Self.read(fileURL: fileURL)) ?? [:]
        var merged = diskEntries
        for (key, value) in entries {
            merged[key] = Self.merge(prior: merged[key], next: value)
        }
        entries = merged
        loaded = true
    }

    private func loadMergingOnDiskQueue() async throws {
        guard let fileURL else {
            loaded = true
            return
        }
        let memory = entries
        let merged = try await disk.run {
            var diskEntries = (try? Self.read(fileURL: fileURL)) ?? [:]
            for (key, value) in memory {
                diskEntries[key] = Self.merge(prior: diskEntries[key], next: value)
            }
            return diskEntries
        }
        entries = merged
        loaded = true
    }

    private static func read(fileURL: URL) throws -> [String: Disposition] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [:] }
        let data = try Data(contentsOf: fileURL)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: String] else {
            return [:]
        }
        var parsed: [String: Disposition] = [:]
        for (key, value) in object {
            if let disposition = Disposition(rawValue: value) {
                parsed[key] = disposition
            }
        }
        return parsed
    }

    private static func write(entries: [String: Disposition], fileURL: URL) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let object = Dictionary(uniqueKeysWithValues: entries.map { ($0.key, $0.value.rawValue) })
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        try OperationalSafeFileWriter.replace(fileURL, with: data)
    }
}
