import Foundation

/// Write-ahead marker ownership. Credentials are deliberately excluded.
public final class DeviceMarkerJournal: @unchecked Sendable {
    public struct Entry: Codable, Equatable, Sendable {
        public let domain: String
        public let map: String
        public let marker: String
        public let revision: String
        public var key: String { "\(domain)/\(map)/\(marker)" }
    }

    /// First 8 characters of a map id for compact logs (never coordinates/secrets).
    public static func mapIdPrefix(_ mapId: String) -> String {
        String(mapId.prefix(8))
    }

    /// Compact confirmed-stale cleanup line: `Marker cleanup: stale marker deleted map=<prefix> http=<code>`
    public static func staleCleanupLogMessage(mapId: String, httpStatus: Int) -> String {
        "Marker cleanup: stale marker deleted map=\(mapIdPrefix(mapId)) http=\(httpStatus)"
    }

    private let file: URL
    /// Serializes arm/acknowledge only. Reads never wait on a writer mid-fsync.
    private let writeGate = NSLock()
    public init(file: URL) { self.file = file }

    public func entries() throws -> [Entry] {
        try loadFromDisk()
    }

    @discardableResult public func arm(domain: String, map: String, marker: String) throws -> Entry {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
        guard !domain.isEmpty, !map.isEmpty, !marker.isEmpty,
              map.unicodeScalars.allSatisfy(allowed.contains), marker.unicodeScalars.allSatisfy(allowed.contains) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        let next = Entry(domain: domain, map: map, marker: marker, revision: UUID().uuidString)
        writeGate.lock()
        defer { writeGate.unlock() }
        var rows = try loadFromDisk()
        rows.removeAll { $0.key == next.key }
        rows.append(next)
        try write(rows)
        return next
    }

    public func acknowledge(_ entry: Entry) throws {
        writeGate.lock()
        defer { writeGate.unlock() }
        try write(try loadFromDisk().filter { $0 != entry })
        clearRetryUnlocked(entry.key)
    }

    /// True when a failed delete may run again. Missing state is due immediately.
    public func due(_ key: String, now: Int64 = MarkerCleanupRetry.now()) -> Bool {
        writeGate.lock()
        defer { writeGate.unlock() }
        guard let row = retryRows()[key] as? [String: Any] else { return true }
        let next = (row["nextTryAt"] as? NSNumber)?.int64Value ?? 0
        return now >= next
    }

    /// Persist the attempt count and the next eligible time. Returns the new attempt count.
    @discardableResult
    public func noteFailure(_ key: String, now: Int64 = MarkerCleanupRetry.now()) throws -> Int {
        writeGate.lock()
        defer { writeGate.unlock() }
        var rows = retryRows()
        let previous = rows[key] as? [String: Any]
        let attempts = ((previous?["attempts"] as? NSNumber)?.intValue ?? 0) + 1
        let next = now + MarkerCleanupRetry.delayMs(attempts: attempts)
        rows[key] = ["attempts": attempts, "nextTryAt": next]
        try writeRetry(rows)
        return attempts
    }

    public func attempts(_ key: String) -> Int {
        writeGate.lock()
        defer { writeGate.unlock() }
        let row = retryRows()[key] as? [String: Any]
        return (row?["attempts"] as? NSNumber)?.intValue ?? 0
    }

    public func clearRetry(_ key: String) {
        writeGate.lock()
        defer { writeGate.unlock() }
        clearRetryUnlocked(key)
    }

    private var retryURL: URL { file.appendingPathExtension("retry") }

    private func retryRows() -> [String: Any] {
        guard let data = try? Data(contentsOf: retryURL),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return rows
    }

    private func clearRetryUnlocked(_ key: String) {
        var rows = retryRows()
        guard rows.removeValue(forKey: key) != nil else { return }
        try? writeRetry(rows)
    }

    private func writeRetry(_ rows: [String: Any]) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys])
        try data.write(to: retryURL, options: .atomic)
    }

    private func loadFromDisk() throws -> [Entry] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        return try JSONDecoder().decode([Entry].self, from: Data(contentsOf: file))
    }

    private func write(_ rows: [Entry]) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(rows).write(to: file, options: .atomic)
    }
}
