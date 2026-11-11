import Foundation

/// A leftover waypoint journal matches a saved flight by drone serial and overlapping point times,
/// not by file name. The journal is retired only when that file holds every journal point.
public enum RecoveredJournalDedup {
    public struct SavedFlight: Equatable, Sendable {
        public let remoteID: String
        public let dayDirectory: String
        public let fileName: String
        public let pointTimesMs: [Int64]
        public init(remoteID: String, dayDirectory: String, fileName: String, pointTimesMs: [Int64]) {
            self.remoteID = remoteID
            self.dayDirectory = dayDirectory
            self.fileName = fileName
            self.pointTimesMs = pointTimesMs
        }
    }

    public enum Action: Equatable, Sendable {
        case writeNew
        case retire(fileName: String)
        case merge(dayDirectory: String, fileName: String, missingTimesMs: [Int64])
    }

    public static func canonical(_ remoteID: String) -> String {
        remoteID.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    public static func decide(remoteID: String, journalTimesMs: [Int64], saved: [SavedFlight]) -> Action {
        let serial = canonical(remoteID)
        let journal = journalTimesMs.filter { $0 > 0 }
        guard !serial.isEmpty, !journal.isEmpty,
              let journalMin = journal.min(), let journalMax = journal.max() else { return .writeNew }
        var best: SavedFlight?
        var bestExact = -1
        var bestInside = -1
        for flight in saved {
            guard canonical(flight.remoteID) == serial else { continue }
            let savedTimes = flight.pointTimesMs.filter { $0 > 0 }
            guard !savedTimes.isEmpty, let savedMin = savedTimes.min(), let savedMax = savedTimes.max() else { continue }
            if journalMin > savedMax || savedMin > journalMax { continue }
            let inside = journal.filter { (savedMin...savedMax).contains($0) }.count
            if inside * 2 < journal.count { continue }
            let savedInside = savedTimes.filter { (journalMin...journalMax).contains($0) }.count
            if savedInside * 2 < savedTimes.count { continue }
            let have = Set(savedTimes)
            let exact = journal.filter { have.contains($0) }.count
            if exact > bestExact || (exact == bestExact && inside > bestInside) {
                best = flight
                bestExact = exact
                bestInside = inside
            }
        }
        guard let match = best else { return .writeNew }
        let have = Set(match.pointTimesMs)
        let missing = journal.filter { !have.contains($0) }
        if missing.isEmpty { return .retire(fileName: match.fileName) }
        return .merge(dayDirectory: match.dayDirectory, fileName: match.fileName, missingTimesMs: missing)
    }
}

/// Reads saved flight GeoJSON well enough to match a journal, and merges missing points into that file.
public enum SavedFlightIndex {
    public static func flights(in root: URL, around date: Date, fileManager: FileManager = .default) -> [RecoveredJournalDedup.SavedFlight] {
        var found: [RecoveredJournalDedup.SavedFlight] = []
        for day in dayNames(around: date) {
            let directory = root.appendingPathComponent(day, isDirectory: true)
            let files = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
            for file in files where file.pathExtension.lowercased() == "json" && file.lastPathComponent != "clues.json" {
                guard let parsed = parse(file) else { continue }
                found.append(RecoveredJournalDedup.SavedFlight(
                    remoteID: parsed.remoteID, dayDirectory: day, fileName: file.lastPathComponent, pointTimesMs: parsed.times))
            }
        }
        return found
    }

    /// Inserts journal coordinates the file does not already hold. True only when the file then has every time.
    public static func merge(
        root: URL, dayDirectory: String, fileName: String, journalPoints: [[String]], journalTimesMs: [Int64],
        fileManager: FileManager = .default
    ) -> Bool {
        let directory = root.appendingPathComponent(dayDirectory, isDirectory: true)
        let file = directory.appendingPathComponent(fileName)
        guard file.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL,
              let data = try? Data(contentsOf: file),
              var rootObject = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              var features = rootObject["features"] as? [[String: Any]], !features.isEmpty else { return false }
        var feature = features[0]
        var geometry = feature["geometry"] as? [String: Any] ?? [:]
        var coordinates = Self.rows(geometry["coordinates"])
        var have = Set(times(in: coordinates))
        var added = false
        for point in journalPoints {
            guard point.count >= 4, let time = Int64(point[3]), time > 0, !have.contains(time) else { continue }
            coordinates.append(point)
            have.insert(time)
            added = true
        }
        if added {
            coordinates.sort { lhs, rhs in
                (Int64("\(lhs.count > 3 ? lhs[3] : "")") ?? 0) < (Int64("\(rhs.count > 3 ? rhs[3] : "")") ?? 0)
            }
            geometry["coordinates"] = coordinates
            feature["geometry"] = geometry
            features[0] = feature
            rootObject["features"] = features
            guard let rewritten = try? JSONSerialization.data(withJSONObject: rootObject, options: [.sortedKeys]) else { return false }
            do { try rewritten.write(to: file, options: .atomic) } catch { return false }
        }
        guard let after = try? Data(contentsOf: file),
              let parsed = parse(after) else { return false }
        return journalTimesMs.allSatisfy { $0 <= 0 || parsed.times.contains($0) }
    }

    private static func dayNames(around date: Date) -> [String] {
        [-86_400.0, 0, 86_400.0].map { AppleFlightStorage.dayName(date.addingTimeInterval($0)) }
    }

    private static func parse(_ file: URL) -> (remoteID: String, times: [Int64])? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return parse(data)
    }

    private static func parse(_ data: Data) -> (remoteID: String, times: [Int64])? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let feature = (root["features"] as? [[String: Any]])?.first,
              let geometry = feature["geometry"] as? [String: Any] else { return nil }
        let coordinates = rows(geometry["coordinates"])
        guard !coordinates.isEmpty else { return nil }
        let properties = feature["properties"] as? [String: Any] ?? [:]
        let r2c = properties["r2c_prop"] as? [String: Any] ?? [:]
        let remote = (r2c["rid"] as? String) ?? ""
        return (remote, times(in: coordinates))
    }

    private static func rows(_ value: Any?) -> [[Any]] {
        guard let raw = value as? [Any] else { return [] }
        return raw.compactMap { item in
            if let row = item as? [Any] { return row }
            if let row = item as? [String] { return row }
            return nil
        }
    }

    private static func times(in coordinates: [[Any]]) -> [Int64] {
        coordinates.compactMap { point in
            guard point.count >= 4 else { return nil }
            if let value = point[3] as? NSNumber { return value.int64Value }
            return Int64("\(point[3])")
        }
    }
}
