import Foundation

/// Moves legacy day-level clues (`FlightStorage/<day>/clues.json`, `<day>/<id>.jpg`) into per-flight
/// folders `FlightStorage/<day>/<flightId>/`, matching the Android layout. Worker-only.
///
/// Crash-safe and idempotent: copy → verify → rewrite flight indexes → verify → only then remove the
/// day-level originals. A flight index that already exists is merged, never demoted (a confirmed
/// upload stays confirmed). Records without a flight id (migration only) recover it from a unique
/// same-day archived flight, else a stable synthetic id from aircraft + day; no record is dropped.
/// Discard tombstones stay at the FlightStorage root.
public enum AppleClueFlightFolderMigration {
    public static let markerFilename = ".clue-flight-folder-migration-v1-done"

    public struct Result: Equatable, Sendable {
        public var records = 0
        public var flights = 0
        public var recoveredFlightIDs = 0
        public init(records: Int = 0, flights: Int = 0, recoveredFlightIDs: Int = 0) {
            self.records = records
            self.flights = flights
            self.recoveredFlightIDs = recoveredFlightIDs
        }
    }

    public enum MigrationError: Error, Equatable {
        case verificationFailed(String)
    }

    /// True while any day still holds a day-level clue index or day-level clue photo.
    public static func hasLegacyWork(root: URL) -> Bool {
        legacyDays(root: root).isEmpty == false
    }

    @discardableResult
    public static func migrateIfNeeded(root: URL) throws -> Result {
        let fm = FileManager.default
        let days = legacyDays(root: root)
        var total = Result()
        var flights = Set<String>()
        for day in days {
            let dayResult = try migrate(day: day, flights: &flights)
            total.records += dayResult.records
            total.recoveredFlightIDs += dayResult.recoveredFlightIDs
        }
        total.flights = flights.count
        let marker = root.appendingPathComponent(markerFilename)
        if fm.fileExists(atPath: root.path), !fm.fileExists(atPath: marker.path) {
            try? Data("1".utf8).write(to: marker, options: .atomic)
        }
        return total
    }

    // MARK: Discovery

    private static func legacyDays(root: URL) -> [URL] {
        let fm = FileManager.default
        let entries = (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        return entries.sorted { $0.lastPathComponent < $1.lastPathComponent }.filter { day in
            guard AppleFlightStorage.date(day.lastPathComponent) != nil, AppleClueFlightFolder.isDirectory(day) else { return false }
            if fm.fileExists(atPath: day.appendingPathComponent(AppleClueFlightFolder.indexFilename).path) { return true }
            let files = (try? fm.contentsOfDirectory(at: day, includingPropertiesForKeys: nil)) ?? []
            return files.contains { isClueImage($0) }
        }
    }

    /// A day-level clue photo: `<uuid>.jpg` or `<uuid>-thumb.jpg`.
    private static func isClueImage(_ url: URL) -> Bool {
        guard url.pathExtension.lowercased() == "jpg" else { return false }
        var stem = url.deletingPathExtension().lastPathComponent
        if stem.hasSuffix("-thumb") { stem = String(stem.dropLast("-thumb".count)) }
        return UUID(uuidString: stem) != nil
    }

    // MARK: One day

    private static func migrate(day: URL, flights: inout Set<String>) throws -> Result {
        let fm = FileManager.default
        let dayName = day.lastPathComponent
        let indexURL = day.appendingPathComponent(AppleClueFlightFolder.indexFilename)
        let existing: [OperationalClueRecord]
        if fm.fileExists(atPath: indexURL.path) {
            existing = try JSONDecoder().decode([OperationalClueRecord].self, from: Data(contentsOf: indexURL))
        } else { existing = [] }

        // Orphan day-level JPEGs (no index row) become local-only stubs; nothing is dropped.
        var sources: [(record: OperationalClueRecord, stub: Bool)] = existing.map { ($0, false) }
        let indexed = Set(existing.flatMap { [lastComponent($0.imageFilename), lastComponent($0.thumbnailFilename)] })
        let files = (try? fm.contentsOfDirectory(at: day, includingPropertiesForKeys: nil)) ?? []
        for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
        where isClueImage(file) && !file.lastPathComponent.hasSuffix("-thumb.jpg") && !indexed.contains(file.lastPathComponent) {
            guard let id = UUID(uuidString: file.deletingPathExtension().lastPathComponent) else { continue }
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
            sources.append((ClueTrackDurabilityIntegrity.recoveredOrphanRecord(
                id: id, capturedAt: modified, prefix: dayName, flightID: nil,
                lastUploadError: "Orphan photo re-indexed"), true))
        }

        var hints: [RidTrackGeoJSON.ArchiveContents]?
        var recovered = 0
        var byFlightFolder: [String: [OperationalClueRecord]] = [:]
        var moved: [URL] = []
        var seen = Set<UUID>()
        for (record, _) in sources {
            guard seen.insert(record.id).inserted else { continue }
            let flightID: String
            if let bound = record.binding?.flightID?.trimmingCharacters(in: .whitespacesAndNewlines), !bound.isEmpty {
                flightID = bound
            } else {
                if hints == nil { hints = archivedFlights(day: day) }
                flightID = recoverFlightID(for: record, day: dayName, archives: hints ?? [])
                recovered += 1
            }
            guard let folder = AppleClueFlightFolder.folderName(flightID: flightID) else {
                throw MigrationError.verificationFailed("flight folder name")
            }
            let destinationDirectory = day.appendingPathComponent(folder, isDirectory: true)
            try fm.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
            var updated = record
            for kind in [\OperationalClueRecord.imageFilename, \OperationalClueRecord.thumbnailFilename] {
                let name = lastComponent(record[keyPath: kind])
                let source = day.appendingPathComponent(name)
                let destination = destinationDirectory.appendingPathComponent(name)
                if fm.fileExists(atPath: source.path) {
                    let bytes = try Data(contentsOf: source)
                    if !bytes.isEmpty {
                        if !(fileSize(destination) == bytes.count) {
                            try bytes.write(to: destination, options: .atomic)
                        }
                        guard fileSize(destination) == bytes.count else {
                            throw MigrationError.verificationFailed("photo copy")
                        }
                    }
                    moved.append(source)
                }
                updated[keyPath: kind] = "\(dayName)/\(folder)/\(name)"
            }
            if updated.binding != nil, updated.binding?.flightID?.isEmpty != false {
                updated.binding?.flightID = flightID
            }
            byFlightFolder[folder, default: []].append(updated)
        }

        // Flight indexes: merge with any existing destination rows, write, then verify by decoding.
        for (folder, incoming) in byFlightFolder {
            let directory = day.appendingPathComponent(folder, isDirectory: true)
            let destinationIndex = directory.appendingPathComponent(AppleClueFlightFolder.indexFilename)
            var merged: [OperationalClueRecord] = []
            if fm.fileExists(atPath: destinationIndex.path) {
                merged = try JSONDecoder().decode([OperationalClueRecord].self, from: Data(contentsOf: destinationIndex))
            }
            let stubIDs = Set(sources.filter { $0.stub }.map { $0.record.id })
            for record in incoming {
                if let at = merged.firstIndex(where: { $0.id == record.id }) {
                    if stubIDs.contains(record.id) { continue }
                    // Destination wins ties; a confirmed upload is never demoted.
                    if record.uploadConfirmed && !merged[at].uploadConfirmed { merged[at] = record }
                } else {
                    merged.append(record)
                }
            }
            try JSONEncoder().encode(merged).write(to: destinationIndex, options: .atomic)
            let verified = try JSONDecoder().decode([OperationalClueRecord].self, from: Data(contentsOf: destinationIndex))
            let ids = Set(verified.map(\.id))
            guard incoming.allSatisfy({ ids.contains($0.id) }) else {
                throw MigrationError.verificationFailed("flight index")
            }
        }

        // Verified: remove day-level originals (photos first, then the legacy index).
        for url in moved { try? fm.removeItem(at: url) }
        if fm.fileExists(atPath: indexURL.path) { try fm.removeItem(at: indexURL) }
        flights.formUnion(byFlightFolder.keys.map { dayName + "/" + $0 })
        return Result(records: seen.count, flights: 0, recoveredFlightIDs: recovered)
    }

    // MARK: Flight recovery (migration only)

    private static func archivedFlights(day: URL) -> [RidTrackGeoJSON.ArchiveContents] {
        let files = (try? FileManager.default.contentsOfDirectory(at: day, includingPropertiesForKeys: nil)) ?? []
        return files.sorted { $0.lastPathComponent < $1.lastPathComponent }.compactMap { file in
            guard file.pathExtension.lowercased() == "json",
                  file.lastPathComponent != AppleClueFlightFolder.indexFilename,
                  !file.lastPathComponent.hasSuffix(".review.json"),
                  let data = try? Data(contentsOf: file),
                  let contents = RidTrackGeoJSON.decodeArchive(data),
                  contents.flightID != nil
            else { return nil }
            return contents
        }
    }

    /// Same-day archived flight of this aircraft (time-contained first, else the only one), else a
    /// stable synthetic id from aircraft + day so repeated runs agree.
    private static func recoverFlightID(
        for record: OperationalClueRecord,
        day: String,
        archives: [RidTrackGeoJSON.ArchiveContents]
    ) -> String {
        let aircraft = record.aircraftID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? record.designator : record.aircraftID
        let canonical = RidTrackStore.canonicalAircraftID(aircraft)
        let candidates = archives.filter {
            !canonical.isEmpty && RidTrackStore.canonicalAircraftID($0.remoteID) == canonical
        }
        let ownership = Int64((record.ownershipTime.timeIntervalSince1970 * 1_000).rounded())
        let slack: Int64 = ClueBinder.bindingLimitMs
        let containing = candidates.filter { archive in
            guard let first = archive.points.map(\.timeMs).min(), let last = archive.points.map(\.timeMs).max() else { return false }
            return ownership >= first - slack && ownership <= last + slack
        }
        if containing.count == 1, let id = containing[0].flightID { return id }
        if containing.isEmpty, candidates.count == 1, let id = candidates[0].flightID { return id }
        let start = AppleFlightStorage.date(day) ?? Date(timeIntervalSince1970: 0)
        return RidFlightID.make(aircraftID: canonical.isEmpty ? "UNKNOWN" : canonical, startedAt: start)
    }

    private static func lastComponent(_ path: String) -> String {
        path.split(separator: "/").last.map(String.init) ?? path
    }

    private static func fileSize(_ url: URL) -> Int? {
        (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize
    }
}
