import Foundation

/// Ephemeral account/map-bound startup snapshots. Actual /since/0 requests still use the per-map limiter.
public actor CaltopoSnapshotReads {
    public static let shared = CaltopoSnapshotReads()
    private struct Entry {
        var store: CaltopoArtifactFeatureStore
        var cursor: Int64
        var checkedAt: TimeInterval
    }
    private var entries: [CaltopoLiveConfiguration: Entry] = [:]
    private var busy: Set<CaltopoLiveConfiguration> = []
    private let now: @Sendable () -> TimeInterval
    public init(now: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) { self.now = now }

    public func read(configuration: CaltopoLiveConfiguration, forceFull: Bool, refresh: Bool,
                     validate: @Sendable () async throws -> Void,
                     load: @Sendable (Int64) async throws -> Data) async throws -> Data {
        while busy.contains(configuration) {
            try Task.checkCancellation()
            try await Task.sleep(for: .milliseconds(20))
        }
        try Task.checkCancellation()
        busy.insert(configuration)
        defer { busy.remove(configuration) }
        try await validate()
        var entry = entries[configuration]
        // Keep at most eight recently used scopes in memory, never on disk.
        entries = entries.filter { now() - $0.value.checkedAt < 120 }
        if let old = entry, now() - old.checkedAt >= 120 { entry = nil }
        if !forceFull, !refresh, let entry, now() - entry.checkedAt < 20 {
            return try Self.snapshotData(entry)
        }
        let cursor = forceFull ? 0 : (entry?.cursor ?? 0)
        do {
            let requestStarted = Int64(Date().timeIntervalSince1970 * 1_000)
            let data = try await load(cursor)
            try Task.checkCancellation()
            try await validate()
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  root["status"] as? String == "ok",
                  let result = root["result"] as? [String: Any],
                  let state = result["state"] as? [String: Any], state["features"] is [Any]
            else { throw CaltopoLiveClientError.missingResult }
            var store = entry?.store ?? CaltopoArtifactFeatureStore()
            store.apply(try CaltopoArtifactDecoder.decodeChanges(data: data), replacing: cursor == 0)
            // CalTopo can send authoritative per-class IDs when tombstones are no longer available.
            let ids = result["ids"] as? [String: Any] ?? state["ids"] as? [String: Any]
            if let ids {
                var retained = store.featuresByID
                for (id, featureData) in retained {
                    guard let feature = try JSONSerialization.jsonObject(with: featureData) as? [String: Any],
                          let properties = feature["properties"] as? [String: Any],
                          let kind = properties["class"] as? String,
                          let liveIDs = ids[kind] as? [String] else { continue }
                    if !liveIDs.contains(id) { retained[id] = nil }
                }
                store = CaltopoArtifactFeatureStore(featuresByID: retained)
            }
            let serverCursor = (result["timestamp"] as? NSNumber)?.int64Value ?? requestStarted
            guard serverCursor > 500 else { throw CaltopoLiveClientError.missingResult }
            let next = Entry(store: store, cursor: max(cursor, serverCursor), checkedAt: now())
            if entries[configuration] == nil, entries.count >= 8,
               let oldest = entries.min(by: { $0.value.checkedAt < $1.value.checkedAt })?.key { entries[oldest] = nil }
            entries[configuration] = next
            return try Self.snapshotData(next)
        } catch {
            entries[configuration] = nil
            throw error
        }
    }

    private static func snapshotData(_ entry: Entry) throws -> Data {
        let features = try entry.store.featuresByID.values.map { try JSONSerialization.jsonObject(with: $0) }
        return try JSONSerialization.data(withJSONObject: ["status": "ok", "result": ["timestamp": entry.cursor,
            "state": ["type": "FeatureCollection", "features": features]]])
    }
}
