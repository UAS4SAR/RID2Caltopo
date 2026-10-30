import Foundation

/// Append-only per-flight waypoint journal for unclean-exit recovery.
/// Durable writes run on a dedicated serial queue so LiveTrack start/archive
/// actors never wait on journal I/O.
public struct WaypointTrackAppendJournal: Sendable {
    public struct RecoveredFlight: Sendable, Equatable {
        public var id: String
        public var remoteID: String
        public var label: String
        public var mapID: String
        public var teamID: String
        public var observations: [RidObservation]
        public var finished: Bool
    }

    private let directory: URL
    private let disk = OperationalWorkQueue(label: "org.ncssar.waypoint-track-journal")

    public init(directory: URL) {
        self.directory = directory
    }

    public func ensureOpen(
        flightID: String,
        remoteID: String,
        label: String,
        mapID: String,
        teamID: String
    ) async throws {
        try await disk.run {
            try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
            let fileURL = self.fileURL(flightID)
            guard !FileManager.default.fileExists(atPath: fileURL.path) else { return }
            let header: [String: Any] = [
                "type": "header",
                "id": flightID,
                "remote": remoteID,
                "label": label,
                "map": mapID,
                "team": teamID,
                "startedAtMs": Int64(Date().timeIntervalSince1970 * 1_000),
            ]
            try Self.append(header, to: fileURL)
        }
    }

    public func append(observation: RidObservation, flightID: String) async throws {
        try await disk.run {
            let point: [String: Any] = [
                "type": "point",
                "lat": observation.latitude,
                "lng": observation.longitude,
                "ele": observation.altitudeMeters ?? -999,
                "t": Int64((observation.droneTimestamp ?? observation.receivedAt).timeIntervalSince1970 * 1_000),
                "recv": Int64(observation.receivedAt.timeIntervalSince1970 * 1_000),
                "src": observation.source.rawValue,
                "droneClock": observation.droneTimestamp != nil,
            ]
            try Self.append(point, to: self.fileURL(flightID))
        }
    }

    public func markFinishedAndRemove(flightID: String) async throws {
        try await disk.run {
            let fileURL = self.fileURL(flightID)
            guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
            try Self.append(["type": "finished", "atMs": Int64(Date().timeIntervalSince1970 * 1_000)], to: fileURL)
            try? FileManager.default.removeItem(at: fileURL)
        }
    }

    public func recoverUnfinished() async throws -> [RecoveredFlight] {
        try await disk.run {
            try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
            let files = (try? FileManager.default.contentsOfDirectory(
                at: self.directory, includingPropertiesForKeys: nil)) ?? []
            var recovered: [RecoveredFlight] = []
            for fileURL in files where fileURL.pathExtension == "ndjson" {
                guard let flight = try Self.read(fileURL: fileURL) else {
                    try? FileManager.default.removeItem(at: fileURL)
                    continue
                }
                if flight.finished || flight.observations.isEmpty {
                    try? FileManager.default.removeItem(at: fileURL)
                    continue
                }
                recovered.append(flight)
                try? FileManager.default.removeItem(at: fileURL)
            }
            return recovered
        }
    }

    private func fileURL(_ flightID: String) -> URL {
        let safe = flightID.replacingOccurrences(
            of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
        return directory.appendingPathComponent("\(safe).ndjson")
    }

    private static func append(_ row: [String: Any], to fileURL: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
        var line = data
        line.append(contentsOf: "\n".utf8)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            let handle = try FileHandle(forWritingTo: fileURL)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: line)
            try handle.synchronize()
        } else {
            try line.write(to: fileURL, options: .atomic)
        }
    }

    public static func read(fileURL: URL) throws -> RecoveredFlight? {
        let text = try String(contentsOf: fileURL, encoding: .utf8)
        var id = ""
        var remote = ""
        var label = ""
        var map = ""
        var team = ""
        var finished = false
        var observations: [RidObservation] = []
        for line in text.split(whereSeparator: \.isNewline) where !line.isEmpty {
            guard let data = line.data(using: .utf8),
                  let row = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let type = row["type"] as? String
            else { continue }
            switch type {
            case "header":
                id = row["id"] as? String ?? id
                remote = row["remote"] as? String ?? remote
                label = row["label"] as? String ?? label
                map = row["map"] as? String ?? map
                team = row["team"] as? String ?? team
            case "point":
                let lat = row["lat"] as? Double ?? 0
                let lng = row["lng"] as? Double ?? 0
                let ele = row["ele"] as? Double
                let tMs = row["t"] as? Int64 ?? 0
                let recvMs = row["recv"] as? Int64 ?? tMs
                let src = row["src"] as? String ?? "rid"
                let droneClock = row["droneClock"] as? Bool ?? true
                let receivedAt = Date(timeIntervalSince1970: Double(recvMs) / 1_000)
                let droneTime = droneClock ? Date(timeIntervalSince1970: Double(tMs) / 1_000) : nil
                let source: RidObservation.Source =
                    RidObservation.Source(rawValue: src)
                    ?? (src == "sei" || src == "djiVideo" ? .djiVideo : .wifiBeacon)
                observations.append(RidObservation(
                    source: source,
                    aircraftId: remote,
                    receivedAt: receivedAt,
                    latitude: lat,
                    longitude: lng,
                    altitudeMeters: ele.flatMap { $0 > -998 ? $0 : nil },
                    droneTimestamp: droneTime
                ))
            case "finished":
                finished = true
            default:
                break
            }
        }
        guard !id.isEmpty else { return nil }
        return RecoveredFlight(
            id: id, remoteID: remote, label: label, mapID: map, teamID: team,
            observations: observations, finished: finished)
    }
}
