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
        case alreadyReported
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
    /// Why the folder cannot be deleted now (e.g. "today", "in use by video review").
    let protectionReason: String?
    /// Clues in this folder that never uploaded to CalTopo and would be lost on delete.
    let unuploadedClueCount: Int
    /// True when deletion is blocked (kept for existing callers).
    var isToday: Bool { protectionReason != nil }
}

actor AppleTrackArchiveStore {
    static let authorizationRejectedNotification = Notification.Name("trackerArchiveAuthorizationRejected")
    enum ArchiveError: Error {
        case documentsDirectoryUnavailable
    }

    private let rootURL: URL?
    private let session: URLSession
    private var configuration: AppleTrackArchiveConfiguration?
    private let reportedFilename = "r2c_reported.txt"
    private let uploadWork = TrackerArchiveUploadWork<AppleTrackArchiveOutcome.TrackerResult>()

    init(fileManager: FileManager = .default, session: URLSession = .shared) {
        rootURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("RID2Caltopo", isDirectory: true)
            .appendingPathComponent("FlightStorage", isDirectory: true)
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
        try safeWrite(data, to: destination)
        // Local backup KMZ for every recorded flight, with or without clues.
        do {
            try writeKMZ(
                title: RidTrackGeoJSON.archiveTitle(for: track, metadata: metadata),
                points: track.points.map {
                    OperationalFlightKMZPoint(latitude: $0.latitude, longitude: $0.longitude, altitudeMeters: $0.altitudeMeters)
                },
                clues: clues.map { OperationalFlightKMZClue(record: $0.record, jpegData: $0.jpegData) },
                destination: directory.appendingPathComponent(RidTrackGeoJSON.suggestedClueReportFilename(for: track))
            )
        } catch {
            // The GeoJSON is saved; queue the KMZ so a launch retry rebuilds it from the archive.
            AppleLog.warning("Archive", "Flight KMZ write failed file=\(destination.lastPathComponent): \(error.localizedDescription)")
            try? rewriteQueue?.enqueue(FlightArchiveRewriteJob(
                aircraftID: track.aircraftID, dayDirectory: directory.lastPathComponent,
                geoJSONFilename: destination.lastPathComponent,
                kmzFilename: RidTrackGeoJSON.suggestedClueReportFilename(for: track)))
        }
        let result = await process(data: data, file: destination)
        return AppleTrackArchiveOutcome(url: destination, trackerResult: result)
    }

    // MARK: Crash-safe writes and KMZ rewrites

    private lazy var rewriteQueue: FlightArchiveRewriteQueue? = {
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return nil }
        return FlightArchiveRewriteQueue(fileURL: support.appendingPathComponent("flight-kmz-rewrites.json"))
    }()
    private var didLaunchSweep = false

    private func safeWrite(_ data: Data, to destination: URL) throws {
        AppleFlightStorage.prepareWrite(Int64(data.count))
        try OperationalSafeFileWriter.replace(destination, with: data)
        AppleFlightStorage.fileChanged(destination)
    }

    /// Once per launch: finish or roll back interrupted archive writes.
    func launchSweep() {
        guard !didLaunchSweep else { return }
        didLaunchSweep = true
        var actions: [OperationalSafeFileWriter.SweepAction] = []
        if let rootURL { actions += OperationalSafeFileWriter.sweep(directory: rootURL) }
        if let queueDirectory = rewriteQueue?.fileURL.deletingLastPathComponent() {
            actions += OperationalSafeFileWriter.sweep(directory: queueDirectory)
        }
        for action in actions {
            AppleLog.info("Archive", "Launch sweep \(action)")
        }
    }

    /// One archived track file.
    struct ArchivedFlight {
        /// The file's flight id (r2c_flight_id), or "day/filename" for files written before it existed.
        let id: String
        let dayDirectory: String
        let fileName: String
        let contents: RidTrackGeoJSON.ArchiveContents
    }

    /// Track archives of `aircraftID` in the day folders around `date`, keyed by flight id (or "day/filename").
    func archivedFlights(aircraftID: String, around date: Date) -> [String: RidTrackGeoJSON.ArchiveContents] {
        Dictionary(archivedFlightFiles(aircraftID: aircraftID, around: date).map { ($0.id, $0.contents) },
                   uniquingKeysWith: { first, _ in first })
    }

    func archivedFlightFiles(aircraftID: String, around date: Date) -> [ArchivedFlight] {
        guard let rootURL else { return [] }
        let canonical = RidTrackStore.canonicalAircraftID(aircraftID)
        var result: [ArchivedFlight] = []
        let days = Set([-86_400.0, 0, 86_400].map { dayDirectoryName(for: date.addingTimeInterval($0)) })
        for day in days {
            let directory = rootURL.appendingPathComponent(day, isDirectory: true)
            let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
            for file in files where file.pathExtension.lowercased() == "json"
                && file.lastPathComponent != "clues.json" && !file.lastPathComponent.hasSuffix(".review.json") {
                guard let data = try? Data(contentsOf: file),
                      let contents = RidTrackGeoJSON.decodeArchive(data),
                      RidTrackStore.canonicalAircraftID(contents.remoteID) == canonical,
                      !contents.points.isEmpty
                else { continue }
                result.append(ArchivedFlight(id: contents.flightID ?? "\(day)/\(file.lastPathComponent)",
                                             dayDirectory: day, fileName: file.lastPathComponent, contents: contents))
            }
        }
        return result.sorted { ($0.dayDirectory, $0.fileName) < ($1.dayDirectory, $1.fileName) }
    }

    /// Queues a KMZ rebuild for the archived flight that owns `record`: the flight whose
    /// r2c_flight_id equals the clue's bound flight, else (older files) the time rule. Returns false
    /// when no archive owns it.
    @discardableResult
    func enqueueRewrite(for record: OperationalClueRecord) -> Bool {
        let files = archivedFlightFiles(aircraftID: record.aircraftID, around: record.ownershipTime)
        let candidates = files.map { OperationalFlightKMZ.candidate(id: $0.id, contents: $0.contents) }
        guard let owner = AwaitingMapClueMatch.ownerID(clueAircraftID: record.aircraftID,
                                                       capturedAt: record.ownershipTime,
                                                       boundFlightID: record.binding?.flightID, candidates: candidates),
              let file = files.first(where: { $0.id == owner })
        else { return false }
        let day = file.dayDirectory
        let geoJSON = file.fileName
        let kmz = URL(fileURLWithPath: geoJSON).deletingPathExtension().appendingPathExtension("kmz").lastPathComponent
        do {
            try rewriteQueue?.enqueue(FlightArchiveRewriteJob(aircraftID: record.aircraftID, dayDirectory: day,
                                                              geoJSONFilename: geoJSON, kmzFilename: kmz))
            AppleLog.info("Archive", "Queued flight KMZ rewrite file=\(day)/\(kmz) clue=\(record.id)")
            return true
        } catch {
            AppleLog.warning("Archive", "Could not queue flight KMZ rewrite: \(error.localizedDescription)")
            return false
        }
    }

    func pendingRewrites() -> [FlightArchiveRewriteJob] { rewriteQueue?.jobs() ?? [] }

    /// Rebuilds one queued KMZ from the archived track and every clue (from `clues`) it owns.
    /// Returns clues whose binding was filled in from the archive so the caller can persist them.
    func performRewrite(_ job: FlightArchiveRewriteJob, clues: [OperationalClueRecord]) -> [OperationalClueRecord] {
        guard let rootURL else { return [] }
        let directory = rootURL.appendingPathComponent(job.dayDirectory, isDirectory: true)
        let geoJSONURL = directory.appendingPathComponent(job.geoJSONFilename)
        do {
            guard let data = try? Data(contentsOf: geoJSONURL), let contents = RidTrackGeoJSON.decodeArchive(data) else {
                // The flight was deleted (Discard or retention); nothing left to rebuild.
                try rewriteQueue?.complete(job.id)
                return []
            }
            let firstTime = contents.points.map(\.timeMs).min().map { Date(timeIntervalSince1970: Double($0) / 1_000) } ?? Date()
            // The rebuilt file is identified like every archive: by its flight id, else "day/file".
            let selfID = contents.flightID ?? job.id
            var archives = archivedFlights(aircraftID: job.aircraftID, around: firstTime)
            archives[selfID] = contents
            let owned = OperationalFlightKMZ.ownedClues(archiveID: selfID, archives: archives, clues: clues)
                .map { OperationalFlightKMZ.bindingFallback($0, contents: contents) }
            try writeKMZ(
                title: contents.title.isEmpty ? job.aircraftID : contents.title,
                points: OperationalFlightKMZ.points(contents),
                clues: owned.map { OperationalFlightKMZClue(record: $0, jpegData: try? Data(contentsOf: rootURL.appendingPathComponent($0.imageFilename))) },
                destination: directory.appendingPathComponent(job.kmzFilename)
            )
            try rewriteQueue?.complete(job.id)
            let original = Dictionary(uniqueKeysWithValues: clues.map { ($0.id, $0) })
            return owned.filter { original[$0.id] != $0 }
        } catch {
            AppleLog.warning("Archive", "Flight KMZ rewrite failed file=\(job.kmzFilename): \(error.localizedDescription)")
            try? rewriteQueue?.recordFailure(job.id, error: error.localizedDescription)
            return []
        }
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
            )) ?? []).filter { $0.pathExtension.lowercased() == "json" && $0.lastPathComponent != "clues.json" && !$0.lastPathComponent.hasSuffix(".review.json") }
            for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let owner = "upload-" + UUID().uuidString
                AppleFlightStorage.protect(directory.lastPathComponent, owner: owner)
                defer { AppleFlightStorage.release(owner: owner) }
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
                case .skipped, .rejected, .alreadyReported: summary.skipped += 1
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
            let ageBase = datedFolder
            return AppleArchiveDirectoryOption(
                name: directory.lastPathComponent,
                ageLabel: ArchiveFolderDisplay.age(now.timeIntervalSince(ageBase)),
                byteCount: bytes,
                sizeLabel: ArchiveFolderDisplay.size(bytes),
                fileCount: files,
                protectionReason: (directory.lastPathComponent == today
                    ? FlightFolderProtection.today
                    : AppleFlightStorage.protectionReason(directory.lastPathComponent))?.label,
                unuploadedClueCount: AppleFlightStorage.unuploadedClueCount(directory.lastPathComponent)
            )
        }
        .sorted { $0.name < $1.name }
    }

    func deleteArchiveDirectories(_ names: Set<String>, now: Date = Date()) -> [String] {
        guard let rootURL else { return Array(names).sorted() }
        let today = dayDirectoryName(for: now)
        var failed: [String] = []
        for name in names.sorted() {
            guard name != today, !AppleFlightStorage.isProtected(name),
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
                try AppleFlightStorage.deleteDay(name)
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

    /// Deletes only the GeoJSON and clue KMZ written for this flight.
    func deleteFlightArchive(_ flight: AwaitingMapFlight) -> AwaitingMapArchiveDeletion {
        guard let rootURL else { return AwaitingMapArchiveDeletion(failures: ["archive folder unavailable"]) }
        let result = AwaitingMapFlightArchive.deleteFiles(for: flight, root: rootURL)
        for path in result.deleted { AppleFlightStorage.fileChanged(rootURL.appendingPathComponent(path), size: 0) }
        return result
    }

    private func process(data: Data, file: URL) async -> AppleTrackArchiveOutcome.TrackerResult {
        await uploadWork.run(key: file.standardizedFileURL.path) {
            await self.processOnce(data: data, file: file)
        }
    }

    private func processOnce(data: Data, file: URL) async -> AppleTrackArchiveOutcome.TrackerResult {
        // Recheck inside the coalesced operation; replay's directory snapshot can be stale.
        guard !reportedFilenames(in: file.deletingLastPathComponent()).contains(file.lastPathComponent) else {
            return .alreadyReported
        }
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
        if statusCode == 401 || statusCode == 403 {
            await MainActor.run {
                NotificationCenter.default.post(
                    name: Self.authorizationRejectedNotification,
                    object: nil,
                    userInfo: ["credential": configuration.tracker.apiKey]
                )
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
        title: String,
        points: [OperationalFlightKMZPoint],
        clues: [OperationalFlightKMZClue],
        destination: URL
    ) throws {
        let archive = try OperationalFlightKMZ.archive(title: title, points: points, clues: clues)
        try safeWrite(archive, to: destination)
        AppleLog.info(
            "Archive",
            "Wrote flight KMZ file=\(destination.lastPathComponent) points=\(points.count) clues=\(clues.count)"
        )
    }
}
