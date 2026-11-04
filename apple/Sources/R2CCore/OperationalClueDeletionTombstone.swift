import Foundation

/// Durable user-delete tombstones for operational clues. Survives restart so late upload
/// success or index reload cannot resurrect a discarded clue.
public enum OperationalClueDeletionTombstone {
    public static let filename = "deleted-clue-ids.json"

    public static func load(root: URL) -> Set<UUID> {
        let url = root.appendingPathComponent(filename)
        guard let data = try? Data(contentsOf: url),
              let raw = try? JSONDecoder().decode([String].self, from: data)
        else { return [] }
        return Set(raw.compactMap { UUID(uuidString: $0) })
    }

    public static func persist(_ ids: Set<UUID>, root: URL) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let sorted = ids.map { $0.uuidString.lowercased() }.sorted()
        let data = try JSONEncoder().encode(sorted)
        try data.write(to: root.appendingPathComponent(filename), options: .atomic)
    }

    public static func excludingDeleted(
        _ records: [OperationalClueRecord],
        deleted: Set<UUID>
    ) -> [OperationalClueRecord] {
        guard !deleted.isEmpty else { return records }
        return records.filter { !deleted.contains($0.id) }
    }
}
