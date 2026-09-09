import Foundation
import R2CCore

struct AppleTrackArchiveConfiguration: Sendable {
    let tracker: TrackerArchiveUploadConfiguration
    let incident: String
    let operationalPeriod: String
    let mapID: String
    let identities: [String: RidAircraftIdentity]
}

struct AppleTrackArchiveClue: Sendable {
    let record: OperationalClueRecord
    let jpegData: Data?
}

struct AppleTrackArchiveOutcome: Sendable {
    enum TrackerResult: Sendable {
        case notConfigured
        case uploaded(Int)
        case rejected(Int)
        case pending(Int)
        case skipped(TrackerArchiveEligibility)
    }

    let url: URL
    let trackerResult: TrackerResult
}

struct AppleTrackReplaySummary: Sendable {
    var checked = 0
    var uploaded = 0
    var pending = 0
    var skipped = 0
}

struct AppleTrackResubmitSummary: Sendable {
    var directoriesReset = 0
    var replay = AppleTrackReplaySummary()

    var description: String {
        "Reset \(directoriesReset) day folder(s); uploaded \(replay.uploaded), pending \(replay.pending), skipped \(replay.skipped)."
    }
}

struct AppleArchiveDirectoryOption: Sendable, Identifiable, Equatable {
    var id: String { name }
    let name: String
    let ageLabel: String
    let byteCount: Int64
    let sizeLabel: String
    let fileCount: Int
    let isToday: Bool
}

actor AppleTrackArchiveStore {
    enum ArchiveError: Error {
        case documentsDirectoryUnavailable
    }

    private let rootURL: URL?
    private let session: URLSession
    private var configuration: AppleTrackArchiveConfiguration?
    private let reportedFilename = "r2c_reported.txt"

    init(fileManager: FileManager = .default, session: URLSession = .shared) {
        rootURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("RID2Caltopo", isDirectory: true)
            .appendingPathComponent("Tracks", isDirectory: true)
        self.session = session
    }

    func configure(_ configuration: AppleTrackArchiveConfiguration) {
        self.configuration = configuration
    }

    func archive(
        track: RidAircraftTrack,
        metadata: RidTrackArchiveMetadata,
        clues: [AppleTrackArchiveClue] = []
    ) async throws -> AppleTrackArchiveOutcome {
        guard let rootURL else { throw ArchiveError.documentsDirectoryUnavailable }
        let directory = rootURL.appendingPathComponent(dayDirectoryName(), isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(RidTrackGeoJSON.suggestedFilename(for: track))
        let data = try RidTrackGeoJSON.encode(track: track, metadata: metadata)
        try data.write(to: destination, options: .atomic)
        if !clues.isEmpty {
            try writeKMZ(
                track: track,
                title: metadata.owner.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? (metadata.mappedID.isEmpty ? track.aircraftID : metadata.mappedID)
                    : metadata.owner.trimmingCharacters(in: .whitespacesAndNewlines),
                clues: clues,
                destination: directory.appendingPathComponent(
                    RidTrackGeoJSON.suggestedClueReportFilename(for: track)
                )
            )
        }
        let result = await process(data: data, file: destination)
        return AppleTrackArchiveOutcome(url: destination, trackerResult: result)
    }

    func replayUnreported() async -> AppleTrackReplaySummary {
        guard configuration?.tracker.isConfigured == true, let rootURL else { return .init() }
        var summary = AppleTrackReplaySummary()
        let dayDirectories = (try? FileManager.default.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        for directory in dayDirectories.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            guard (try? directory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
            let reported = reportedFilenames(in: directory)
            let files = ((try? FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            )) ?? []).filter { $0.pathExtension.lowercased() == "json" }
            for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                guard var data = try? Data(contentsOf: file) else {
                    guard !reported.contains(file.lastPathComponent) else { continue }
                    summary.checked += 1
                    try? markReported(file)
                    summary.skipped += 1
                    continue
                }
                if let repaired = repairLegacyMetadata(in: data) {
                    data = repaired
                    try? data.write(to: file, options: .atomic)
                }
                guard !reported.contains(file.lastPathComponent) else { continue }
                summary.checked += 1
                switch await process(data: data, file: file) {
                case .uploaded: summary.uploaded += 1
                case .pending: summary.pending += 1
                case .skipped, .rejected: summary.skipped += 1
                case .notConfigured: summary.pending += 1
                }
            }
        }
        return summary
    }

    func resubmitRecent(days: Int, now: Date = Date()) async -> AppleTrackResubmitSummary {
        guard configuration?.tracker.isConfigured == true, let rootURL else { return .init() }
        let clampedDays = max(1, days)
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let oldest = calendar.date(byAdding: .day, value: -(clampedDays - 1), to: today) ?? today
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        var result = AppleTrackResubmitSummary()
        let directories = (try? FileManager.default.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        for directory in directories {
            guard (try? directory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true,
                  let date = formatter.date(from: directory.lastPathComponent),
                  date >= oldest,
                  date <= today
            else { continue }
            try? FileManager.default.removeItem(
                at: directory.appendingPathComponent(reportedFilename)
            )
            result.directoriesReset += 1
        }
        result.replay = await replayUnreported()
        AppleLog.info("TrackerArchive", "Recent resubmit: \(result.description)")
        return result
    }

    func archiveRootURL() -> URL? { rootURL }

    func archiveDirectories(now: Date = Date()) -> [AppleArchiveDirectoryOption] {
        guard let rootURL else { return [] }
        let today = dayDirectoryName(for: now)
        let directories = (try? FileManager.default.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        return directories.compactMap { directory in
            guard let directoryValues = try? directory.resourceValues(
                forKeys: [.isDirectoryKey, .contentModificationDateKey]
            ), directoryValues.isDirectory == true
            else { return nil }
            let enumerator = FileManager.default.enumerator(
                at: directory,
                includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
                options: [.skipsHiddenFiles]
            )
            var bytes: Int64 = 0
            var files = 0
            while let child = enumerator?.nextObject() as? URL {
                guard let values = try? child.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]),
                      values.isRegularFile == true
                else { continue }
                files += 1
                bytes += Int64(values.fileSize ?? 0)
            }
            guard let datedFolder = archiveDate(from: directory.lastPathComponent) else { return nil }
            let ageBase = directoryValues.contentModificationDate ?? datedFolder
            return AppleArchiveDirectoryOption(
                name: directory.lastPathComponent,
                ageLabel: ArchiveFolderDisplay.age(now.timeIntervalSince(ageBase)),
                byteCount: bytes,
                sizeLabel: ArchiveFolderDisplay.size(bytes),
                fileCount: files,
                isToday: directory.lastPathComponent == today
            )
        }
        .sorted { $0.name > $1.name }
    }

    func deleteArchiveDirectories(_ names: Set<String>, now: Date = Date()) -> [String] {
        guard let rootURL else { return Array(names).sorted() }
        let today = dayDirectoryName(for: now)
        var failed: [String] = []
        for name in names.sorted() {
            guard name != today,
                  !name.isEmpty,
                  name != ".",
                  name != "..",
                  !name.contains("/"),
                  !name.contains("\\")
            else {
                failed.append(name)
                continue
            }
            let target = rootURL.appendingPathComponent(name, isDirectory: true)
            guard target.deletingLastPathComponent().standardizedFileURL == rootURL.standardizedFileURL,
                  (try? target.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
            else {
                failed.append(name)
                continue
            }
            do {
                try FileManager.default.removeItem(at: target)
                AppleLog.info("Archive", "Deleted local archive folder name=\(name)")
            } catch {
                failed.append(name)
                AppleLog.warning(
                    "Archive",
                    "Failed deleting archive folder name=\(name): \(error.localizedDescription)"
                )
            }
        }
        return failed
    }

    private func process(data: Data, file: URL) async -> AppleTrackArchiveOutcome.TrackerResult {
        guard let configuration, configuration.tracker.isConfigured else { return .notConfigured }
        let knownIDs = Set(configuration.identities.keys)
        let eligibility = TrackerArchiveUploadContract.eligibility(
            geoJSON: data,
            configuration: configuration.tracker,
            knownRemoteIDs: knownIDs
        )
        guard eligibility == .eligible else {
            try? markReported(file)
            AppleLog.info("TrackerArchive", "Skipped \(file.lastPathComponent): \(String(describing: eligibility))")
            return .skipped(eligibility)
        }
        guard let request = try? TrackerArchiveUploadContract.makeRequest(
            geoJSON: data,
            configuration: configuration.tracker
        ) else { return .pending(400) }

        var statusCode = 503
        for attempt in 1 ... 3 {
            do {
                let (_, response) = try await session.data(for: request)
                statusCode = (response as? HTTPURLResponse)?.statusCode ?? 503
            } catch {
                statusCode = (error as? URLError)?.code == .timedOut ? 408 : 503
                AppleLog.warning("TrackerArchive", "Upload attempt \(attempt)/3 failed for \(file.lastPathComponent): \(error.localizedDescription)")
            }
            if !TrackerArchiveUploadContract.isTransient(statusCode: statusCode) { break }
            if attempt < 3 {
                try? await Task.sleep(for: .seconds(attempt))
            }
        }
        if TrackerArchiveUploadContract.shouldMarkReported(statusCode: statusCode) {
            try? markReported(file)
            if (200 ... 299).contains(statusCode) {
                AppleLog.info("TrackerArchive", "Uploaded \(file.lastPathComponent) status=\(statusCode)")
                return .uploaded(statusCode)
            }
            AppleLog.warning("TrackerArchive", "Tracker rejected \(file.lastPathComponent) status=\(statusCode)")
            return .rejected(statusCode)
        }
        AppleLog.warning("TrackerArchive", "Upload remains pending for \(file.lastPathComponent) status=\(statusCode)")
        return .pending(statusCode)
    }

    /// Early Apple builds wrote the Android-compatible envelope before wiring
    /// identity/config metadata. They used the RID itself as `mid` and sometimes
    /// put the imported owner's full name in `owner`; both are placeholders, not
    /// the Android-compatible mapped aircraft ID and pilot callsign.
    private func repairLegacyMetadata(in data: Data) -> Data? {
        guard let configuration,
              var root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              var features = root["features"] as? [[String: Any]], !features.isEmpty,
              var properties = features[0]["properties"] as? [String: Any],
              var metadata = properties["r2c_prop"] as? [String: Any],
              let remoteID = metadata["rid"] as? String,
              let identity = configuration.identities[remoteID]
        else { return nil }
        var changed = false
        func fill(_ key: String, _ value: String) {
            guard ((metadata[key] as? String) ?? "").isEmpty, !value.isEmpty else { return }
            metadata[key] = value
            changed = true
        }
        let priorMappedID = ((metadata["mid"] as? String) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let hasPlaceholderIdentity = priorMappedID.isEmpty || priorMappedID == remoteID
        if hasPlaceholderIdentity, !identity.mappedID.isEmpty {
            metadata["mid"] = identity.mappedID
            changed = true
        }
        fill("org", identity.organization)
        if hasPlaceholderIdentity, !identity.pilotCallsign.isEmpty {
            metadata["owner"] = identity.pilotCallsign
            changed = true
        } else {
            fill("owner", identity.pilotCallsign)
        }
        fill("model", identity.droneDescription)
        fill("incident", configuration.incident)
        fill("op_period", configuration.operationalPeriod)
        fill("map_id", configuration.mapID)
        guard changed else { return nil }
        properties["title"] = identity.displayLabel
        properties["r2c_prop"] = metadata
        features[0]["properties"] = properties
        root["features"] = features
        return try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    private func reportedFilenames(in directory: URL) -> Set<String> {
        let url = directory.appendingPathComponent(reportedFilename)
        guard let contents = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return Set(contents.split(whereSeparator: \.isNewline).map(String.init))
    }

    private func markReported(_ file: URL) throws {
        let reportURL = file.deletingLastPathComponent().appendingPathComponent(reportedFilename)
        var reported = reportedFilenames(in: file.deletingLastPathComponent())
        guard reported.insert(file.lastPathComponent).inserted else { return }
        let contents = reported.sorted().joined(separator: "\n") + "\n"
        try contents.write(to: reportURL, atomically: true, encoding: .utf8)
    }

    private func dayDirectoryName() -> String {
        dayDirectoryName(for: Date())
    }

    private func dayDirectoryName(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private func archiveDate(from directoryName: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.isLenient = false
        return formatter.date(from: directoryName)
    }

    private func writeKMZ(
        track: RidAircraftTrack,
        title: String,
        clues: [AppleTrackArchiveClue],
        destination: URL
    ) throws {
        var kml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <name>\(Self.escapeXML(title))</name>
            <Style id="trackStyle"><LineStyle><color>ffff0000</color><width>3</width></LineStyle></Style>
            <Placemark>
              <name>\(Self.escapeXML(title))</name>
              <styleUrl>#trackStyle</styleUrl>
              <LineString><tessellate>1</tessellate><coordinates>

        """
        for point in track.points {
            kml += String(
                format: "        %.6f,%.6f,%.1f\n",
                point.longitude,
                point.latitude,
                point.altitudeMeters ?? 0
            )
        }
        kml += "      </coordinates></LineString>\n    </Placemark>\n"
        let iso = ISO8601DateFormatter()
        for (index, clue) in clues.enumerated() {
            let record = clue.record
            kml += "    <Placemark>\n"
            kml += "      <name>\(Self.escapeXML(record.title))</name>\n"
            kml += "      <TimeStamp><when>\(iso.string(from: record.capturedAt))</when></TimeStamp>\n"
            if clue.jpegData != nil {
                let description = record.clueDescription.replacingOccurrences(of: "]]>", with: "]]&gt;")
                kml += "      <description><![CDATA[\(description)<br/><img src=\"files/clue_\(index).jpg\"/>]]></description>\n"
            } else if !record.clueDescription.isEmpty {
                kml += "      <description>\(Self.escapeXML(record.clueDescription))</description>\n"
            }
            kml += String(
                format: "      <Point><coordinates>%.6f,%.6f,%.1f</coordinates></Point>\n",
                record.clueLongitude,
                record.clueLatitude,
                record.clueAltitudeMeters ?? 0
            )
            kml += "    </Placemark>\n"
        }
        kml += "  </Document>\n</kml>\n"
        var entries = [
            OperationalZipArchive.Entry(path: "doc.kml", data: Data(kml.utf8)),
        ]
        for (index, clue) in clues.enumerated() {
            if let jpegData = clue.jpegData {
                entries.append(.init(path: "files/clue_\(index).jpg", data: jpegData))
            }
        }
        let archive = try OperationalZipArchive.encode(entries, compress: true)
        try archive.write(to: destination, options: .atomic)
        AppleLog.info(
            "Archive",
            "Wrote clue KMZ file=\(destination.lastPathComponent) clues=\(clues.count)"
        )
    }

    private static func escapeXML(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}
