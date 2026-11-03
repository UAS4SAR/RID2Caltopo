import Foundation

/// Remembers empty result dirs already removed so relaunch cannot re-log forever.
public enum OrphanEmptyRegistry {
    public static let schema = 1

    public static func fileURL(supportRoot: URL) -> URL {
        supportRoot.appendingPathComponent("mapping-storage-stats/orphan-empty-removed.json")
    }

    public static func load(supportRoot: URL) -> Set<String> {
        guard let data = try? Data(contentsOf: fileURL(supportRoot: supportRoot)),
              let text = String(data: data, encoding: .utf8) else { return [] }
        return parse(text)
    }

    public static func parse(_ text: String) -> Set<String> {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (object["schema"] as? Int) == schema,
              let ids = object["ids"] as? [String] else { return [] }
        return Set(ids.filter { !$0.isEmpty })
    }

    public static func write(supportRoot: URL, ids: Set<String>) {
        let url = fileURL(supportRoot: supportRoot)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let body: [String: Any] = ["schema": schema, "ids": ids.sorted()]
        guard let data = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]) else { return }
        try? data.write(to: url, options: .atomic)
    }

    /// Returns true the first time `id` is recorded.
    @discardableResult
    public static func remember(supportRoot: URL, id: String) -> Bool {
        var prior = load(supportRoot: supportRoot)
        if prior.contains(id) { return false }
        prior.insert(id)
        write(supportRoot: supportRoot, ids: prior)
        return true
    }
}
