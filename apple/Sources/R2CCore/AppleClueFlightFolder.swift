import Foundation

/// Per-flight clue folder layout under `FlightStorage`:
///
///     FlightStorage/<yyyy-MM-dd>/<flightId>/clues.json
///     FlightStorage/<yyyy-MM-dd>/<flightId>/<uuid>.jpg
///     FlightStorage/<yyyy-MM-dd>/<flightId>/<uuid>-thumb.jpg
///
/// Clues nest under the day that holds the flight logs, so deleting a day also deletes its clues.
/// Relative `imageFilename` is `<day>/<flightId>/<id>.jpg`. The legacy day-level layout
/// (`<day>/clues.json`, `<day>/<id>.jpg`) is a migration source only.
/// `OperationalClueDeletionTombstone` stays at the FlightStorage root.
public enum AppleClueFlightFolder {
    public static let indexFilename = "clues.json"

    /// Filesystem-safe single path component for a flight id; nil for a blank id.
    public static func folderName(flightID: String) -> String? {
        let trimmed = flightID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var safe = String(trimmed.unicodeScalars.map { scalar -> Character in
            let ascii = scalar.isASCII
            let ok = ascii && (CharacterSet.alphanumerics.contains(scalar) || scalar == "-" || scalar == "_")
            return ok ? Character(scalar) : "_"
        })
        if safe.allSatisfy({ $0 == "_" }) { safe = "flight" + safe }
        return safe
    }

    /// `<day>/<flightId>/<filename>`; nil when the day is not a valid day folder or the flight is blank.
    public static func relativePath(day: String, flightID: String, filename: String) -> String? {
        guard AppleFlightStorage.date(day) != nil, let folder = folderName(flightID: flightID) else { return nil }
        let name = filename.split(separator: "/").last.map(String.init) ?? filename
        guard !name.isEmpty else { return nil }
        return "\(day)/\(folder)/\(name)"
    }

    /// Day (first component) of a relative clue path, when it is a valid day folder.
    public static func day(ofRelativePath path: String) -> String? {
        let first = path.split(separator: "/", omittingEmptySubsequences: true).first.map(String.init) ?? ""
        return AppleFlightStorage.date(first) != nil ? first : nil
    }

    /// Flight folder (second of three components) of `<day>/<flightId>/<file>`; nil for legacy `<day>/<file>`.
    public static func flightFolder(ofRelativePath path: String) -> String? {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard parts.count == 3, AppleFlightStorage.date(parts[0]) != nil else { return nil }
        return parts[1]
    }

    /// Directory (relative to the root) holding a record's files: `<day>` or `<day>/<flightId>`.
    public static func directory(ofRelativePath path: String) -> String {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        return parts.dropLast().joined(separator: "/")
    }

    /// Day to use for a new clue of `flightID`: the day already holding that flight's clues (so one
    /// flight stays in one folder across midnight), else the day of `now`.
    public static func resolveDay(flightID: String, existing: [OperationalClueRecord], now: Date) -> String {
        let folder = folderName(flightID: flightID)
        for record in existing {
            guard let day = day(ofRelativePath: record.imageFilename),
                  let name = flightFolder(ofRelativePath: record.imageFilename), name == folder else { continue }
            return day
        }
        return AppleFlightStorage.dayName(now)
    }

    /// Directories that may carry a `clues.json`: every valid day folder (legacy day-level index) and
    /// each directory directly below it (flight folders; recording folders simply have no index).
    /// A missing root is empty; any other listing failure is an error (never a silent empty day).
    public static func indexDirectories(root: URL) throws -> [URL] {
        let fm = FileManager.default
        let days: [URL]
        do { days = try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey]) }
        catch let error as CocoaError where error.code == .fileReadNoSuchFile { return [] }
        var result: [URL] = []
        for day in days.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            guard AppleFlightStorage.date(day.lastPathComponent) != nil, isDirectory(day) else { continue }
            result.append(day)
            let children: [URL]
            do { children = try fm.contentsOfDirectory(at: day, includingPropertiesForKeys: [.isDirectoryKey]) }
            catch let error as CocoaError where error.code == .fileReadNoSuchFile { continue }
            for child in children.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                guard !child.lastPathComponent.hasPrefix("."), isDirectory(child) else { continue }
                result.append(child)
            }
        }
        return result
    }

    /// Upload states of every clue stored under one day folder (legacy index plus all flight indexes).
    public static func clueStates(inDay folder: URL) -> [OperationalClueUploadState] {
        // Keyed by id so an interrupted migration (legacy + flight copy) counts a clue once.
        var byID: [UUID: OperationalClueUploadState] = [:]
        var directories = [folder]
        let children = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        directories += children.filter { !$0.lastPathComponent.hasPrefix(".") && isDirectory($0) }
        for directory in directories {
            guard let data = try? Data(contentsOf: directory.appendingPathComponent(indexFilename)),
                  let clues = try? JSONDecoder().decode([OperationalClueRecord].self, from: data) else { continue }
            for clue in clues where byID[clue.id] == nil || clue.uploadConfirmed { byID[clue.id] = clue.uploadState }
        }
        return Array(byID.values)
    }

    static func isDirectory(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
    }

    /// `properties.r2c_flight_id` values from GeoJSON archives under a day folder.
    public static func flightIDsInArchiveDay(_ dayURL: URL) -> Set<String> {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(
            at: dayURL, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]
        ) else { return [] }
        var ids = Set<String>()
        for file in files {
            let name = file.lastPathComponent
            guard file.pathExtension.lowercased() == "json",
                  name != indexFilename,
                  !name.hasSuffix(".review.json") else { continue }
            guard let data = try? Data(contentsOf: file),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let features = root["features"] as? [[String: Any]],
                  let properties = features.first?["properties"] as? [String: Any],
                  let flightID = properties["r2c_flight_id"] as? String,
                  !flightID.isEmpty
            else { continue }
            ids.insert(flightID)
        }
        return ids
    }

    /// Nested flight-folder names directly under a day (`<day>/<flightId>/`).
    public static func nestedFlightFolderNames(inDay dayURL: URL) -> Set<String> {
        let children = (try? FileManager.default.contentsOfDirectory(
            at: dayURL, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]
        )) ?? []
        return Set(children.compactMap { child -> String? in
            guard isDirectory(child), !child.lastPathComponent.hasPrefix(".") else { return nil }
            return child.lastPathComponent
        })
    }

    /**
     * Clue trees to remove when deleting archive day `dayName`: any `<otherDay>/<flightId>/`
     * for flights contained in the day's GeoJSON or nested under it (midnight edge).
     * Does not include the day folder itself.
     */
    public static func crossDayClueFoldersToRemove(
        root: URL,
        dayName: String,
        archiveFlightIDs: Set<String>,
        nestedFolderNames: Set<String>
    ) -> [URL] {
        var folderNames = nestedFolderNames
        for id in archiveFlightIDs {
            if let name = folderName(flightID: id) { folderNames.insert(name) }
        }
        guard !folderNames.isEmpty else { return [] }
        let fm = FileManager.default
        guard let days = try? fm.contentsOfDirectory(
            at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]
        ) else { return [] }
        var result: [URL] = []
        for day in days {
            let name = day.lastPathComponent
            guard name != dayName, AppleFlightStorage.date(name) != nil, isDirectory(day) else { continue }
            for folder in folderNames {
                let url = day.appendingPathComponent(folder, isDirectory: true)
                if isDirectory(url) { result.append(url) }
            }
        }
        return result
    }
}
