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
