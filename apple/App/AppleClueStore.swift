import Foundation
import ImageIO
import R2CCore
import UIKit

struct AppleClueDraft: Sendable {
    let capturedAt: Date
    let aircraftID: String
    let designator: String
    let droneLatitude: Double
    let droneLongitude: Double
    let droneAltitudeMeters: Double?
    let clueLatitude: Double
    let clueLongitude: Double
    let clueAltitudeMeters: Double?
    let headingDegrees: Double?
    let aglMeters: Double?
    let atoMeters: Double?
    let gimbalAngleDegrees: Double
    let title: String
    let description: String
    /// Waypoint binding computed by the clue form (nil only for demo clues).
    var binding: ClueBinding? = nil
}

@MainActor
final class AppleClueStore: ObservableObject {
    @Published private(set) var records: [OperationalClueRecord] = [] {
        didSet { reviewRevision &+= 1 }
    }
    @Published private(set) var reviewRevision = 0
    @Published private(set) var status = "No local clues"

    private let disk = OperationalWorkQueue(label: "org.ncssar.clues.disk")
    private var initialLoad: Task<Void, Never>?
    private var indexLoadFailed = false
    private var indexRevision = 0
    private let root: URL
    private var personalLogin = false
    private var client: CaltopoLiveClient?
    private var teamID = ""
    private var mapID = ""
    private var configurationGeneration = 0
    private var trackFolderName = "Drone Tracks"
    private var trackFolderID: String?
    private var folderResolver = CaltopoTrackFolderResolver()
    private var uploadTasks: [UUID: Task<Void, Never>] = [:]
    /// Called after a clue is saved or deleted, so a finished flight's KMZ can be rebuilt.
    var clueChanged: ((OperationalClueRecord) -> Void)?

    init(fileManager: FileManager = .default) {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        root = documents.appendingPathComponent("RID2Caltopo/FlightStorage", isDirectory: true)
        let root = self.root, disk = self.disk
        initialLoad = Task { [weak self] in
            do {
                let loaded = try await disk.run { try OperationalClueIndex.load(root: root) }
                guard let self else { return }
                self.records = loaded
                self.updateStatus()
            } catch {
                guard let self else { return }
                self.indexLoadFailed = true
                self.status = "Saved clue history could not be read. Its files have been kept; reconnect storage and restart to retry."
                AppleLog.error("Clue", "Index loading failed; writes disabled: \(error)")
            }
        }
    }

    deinit {
        uploadTasks.values.forEach { $0.cancel() }
    }

    func configure(
        _ configuration: AppleCaltopoConfiguration,
        trackFolderName: String = "Drone Tracks"
    ) {
        uploadTasks.values.forEach { $0.cancel() }
        uploadTasks.removeAll()
        configurationGeneration += 1
        personalLogin = configuration.personalSessionID != nil
        mapID = configuration.mapID
        teamID = configuration.publicationScope
        self.trackFolderName = trackFolderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "Drone Tracks"
            : trackFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
        trackFolderID = nil
        folderResolver = CaltopoTrackFolderResolver()
        guard let live = configuration.liveConfiguration, !teamID.isEmpty else {
            client = nil
            status = records.isEmpty ? "No local clues" : "\(records.count) local clues; CalTopo upload not configured"
            return
        }
        do {
            client = try CaltopoLiveClient(configuration: live)
            let generation = configurationGeneration
            Task { await self.resumePendingUploads(generation: generation) }

        } catch {
            client = nil
            status = "Clues local; CalTopo configuration failed"
        }
    }

    private func resumePendingUploads(generation: Int) async {
        await initialLoad?.value
        guard generation == configurationGeneration else { return }
        // Publish connected-map clues before awaiting-map binding. A journal failure must not pause them.
        records.filter { $0.uploadState == .pending || $0.uploadState == .failed || $0.uploadState == .uploading }
            .forEach { enqueueUpload($0.id) }
        let decisions: [AwaitingMapFlight]
        do {
            decisions = try await disk.run {
                let journalURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("awaiting-map-flights.json")
                return try AwaitingMapFlightJournal.readSavedEntries(fileURL: journalURL)
            }
        } catch {
            AppleLog.warning(
                "Clue",
                "Awaiting-map journal unavailable; connected-map clues still upload. \(error.localizedDescription)"
            )
            updateStatus()
            return
        }
        guard generation == configurationGeneration else { return }
        for flight in decisions where ["publish", "queued"].contains(flight.decision) && flight.mapID == mapID && flight.teamID == teamID {
            guard generation == configurationGeneration else { return }
            await bindAwaitingFlight(flight, mapID: mapID, teamID: teamID)
        }
        updateStatus()
    }

    @discardableResult
    func save(_ draft: AppleClueDraft, jpegData: Data, publishToCaltopo: Bool) async throws -> OperationalClueRecord {
        guard !jpegData.isEmpty else { throw CocoaError(.fileWriteUnknown) }
        await initialLoad?.value
        guard !indexLoadFailed else { throw CocoaError(.fileReadUnknown) }
        let id = UUID()
        let day = AppleFlightStorage.dayName(Date())
        let imageFilename = "\(day)/\(id.uuidString.lowercased()).jpg"
        let thumbnailFilename = "\(day)/\(id.uuidString.lowercased())-thumb.jpg"
        let root = self.root
        let flightDestination = publishToCaltopo ? try await publicationDestination(for: draft) : (map: nil, team: nil)
        try await disk.run {
            try FileManager.default.createDirectory(at: root.appendingPathComponent(day), withIntermediateDirectories: true)
            AppleFlightStorage.prepareWrite(Int64(jpegData.count) * 2)
            try jpegData.write(to: root.appendingPathComponent(imageFilename), options: .atomic)
            let thumbnailData = Self.thumbnailJPEG(from: jpegData) ?? jpegData
            try thumbnailData.write(to: root.appendingPathComponent(thumbnailFilename), options: .atomic)
        }
        let record = OperationalClueRecord(
            id: id,
            capturedAt: draft.capturedAt,
            aircraftID: draft.aircraftID,
            designator: draft.designator,
            droneLatitude: draft.droneLatitude,
            droneLongitude: draft.droneLongitude,
            droneAltitudeMeters: draft.droneAltitudeMeters,
            clueLatitude: draft.clueLatitude,
            clueLongitude: draft.clueLongitude,
            clueAltitudeMeters: draft.clueAltitudeMeters,
            headingDegrees: RidHeading.normalized(draft.headingDegrees),
            aglMeters: draft.aglMeters,
            atoMeters: draft.atoMeters,
            gimbalAngleDegrees: draft.gimbalAngleDegrees,
            title: draft.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Local marker" : draft.title,
            clueDescription: draft.description,
            imageFilename: imageFilename,
            thumbnailFilename: thumbnailFilename,
            uploadState: publishToCaltopo ? .pending : .localOnly,
            destinationMapID: publishToCaltopo ? flightDestination.map : nil,
            destinationTeamID: publishToCaltopo ? flightDestination.team : nil,
            binding: draft.binding
        )
        records.insert(record, at: 0)
        let mutationRevision = indexRevision + 1
        do { try await persistIndex() }
        catch {
            if indexRevision == mutationRevision { records.removeAll { $0.id == id } }
            throw error
        }
        AppleLog.info(
            "Clue",
            "Local clue saved id=\(id) designator=\(draft.designator) bytes=\(jpegData.count) publish=\(publishToCaltopo) " +
                "binding=\(draft.binding.map { ClueBindingText.formSummary($0) } ?? "none")"
        )
        updateStatus()
        // The binding was fixed at Submit; the upload starts right away.
        if publishToCaltopo { enqueueUpload(id) }
        clueChanged?(record)
        return record
    }

    // MARK: Waypoint binding

    /// Stores bindings filled in from an archived flight (late clues saved without one).
    func applyArchivedBindings(_ updated: [OperationalClueRecord]) async {
        var changed: [OperationalClueRecord] = []
        for record in updated {
            guard let index = records.firstIndex(where: { $0.id == record.id }), records[index].binding != record.binding
            else { continue }
            records[index].binding = record.binding
            changed.append(records[index])
        }
        guard !changed.isEmpty else { return }
        try? await persistIndex()
    }

    /// A connected map publishes immediately. Awaiting-map review applies only while no map is connected.
    private func publicationDestination(for draft: AppleClueDraft) async throws -> (map: String?, team: String?) {
        let generation = configurationGeneration
        let selectedMap = mapID, selectedTeam = teamID
        if !selectedMap.isEmpty && !selectedTeam.isEmpty {
            return (selectedMap, selectedTeam)
        }
        let result: (map: String?, team: String?)
        do {
            result = try await disk.run {
                let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("awaiting-map-flights.json")
                let flights = try AwaitingMapFlightJournal.readSavedEntries(fileURL: url)
                let captured = draft.binding.map { Date(timeIntervalSince1970: Double($0.captureTimeMs) / 1_000) } ?? draft.capturedAt
                let owner = AwaitingMapClueMatch.owner(clueAircraftID: draft.aircraftID, capturedAt: captured,
                                                      boundFlightID: draft.binding?.flightID, flights: flights)
                if let flight = owner, !flight.finished || flight.id == draft.binding?.flightID, flight.decision != "local" {
                    return (map: flight.mapID.isEmpty ? nil : flight.mapID, team: flight.teamID.isEmpty ? nil : flight.teamID)
                }
                return (map: selectedMap.isEmpty ? nil : selectedMap, team: selectedTeam.isEmpty ? nil : selectedTeam)
            }
        } catch {
            AppleLog.warning(
                "Clue",
                "Awaiting-map destination unavailable; clue keeps the connected map. \(error.localizedDescription)"
            )
            return (selectedMap.isEmpty ? nil : selectedMap, selectedTeam.isEmpty ? nil : selectedTeam)
        }
        guard generation == configurationGeneration else { throw CancellationError() }
        return result
    }


    func bindAwaitingFlight(_ flight: AwaitingMapFlight, mapID: String, teamID: String) async {
        await initialLoad?.value
        // Ownership matching scans complete track geometry. Prepare a snapshot on the
        // worker and retry if another clue changed while it was being prepared.
        var previous: [OperationalClueRecord] = []
        while true {
            let snapshot = records, revision = reviewRevision
            let prepared = await Task.detached(priority: .utility) {
                var next = snapshot
                for index in next.indices where next[index].destinationMapID == nil &&
                        next[index].uploadState != .localOnly && next[index].uploadState != .published &&
                        AwaitingMapClueMatch.matches(next[index], flight: flight) {
                    next[index].destinationMapID = mapID
                    next[index].destinationTeamID = teamID
                }
                return next
            }.value
            guard !Task.isCancelled else { return }
            guard revision == reviewRevision else { continue }
            previous = snapshot; records = prepared
            break
        }
        let mutationRevision = indexRevision + 1
        do { try await persistIndex() } catch {
            if indexRevision == mutationRevision { records = previous }
            status = "Clue destination could not be saved"; return
        }
        for record in records where record.canAutomaticallyPublish(mapID: self.mapID, teamID: self.teamID) && record.uploadState == .pending {
            enqueueUpload(record.id)
        }
    }

    func retry(_ id: UUID) async {
        mutate(id) { record in
            record.uploadState = .pending
            record.lastUploadError = nil
        }
        try? await persistIndex()
        enqueueUpload(id)
    }

    /// Returns true when the clue's photo is gone from this device afterwards.
    @discardableResult
    func delete(_ id: UUID) async -> Bool {
        await initialLoad?.value
        uploadTasks.removeValue(forKey: id)?.cancel()
        guard let index = records.firstIndex(where: { $0.id == id }) else { return false }
        let record = records.remove(at: index)
        let image = imageURL(for: record), thumbnail = thumbnailURL(for: record)
        let removed = (try? await disk.run {
            try? FileManager.default.removeItem(at: image)
            try? FileManager.default.removeItem(at: thumbnail)
            return !FileManager.default.fileExists(atPath: image.path)
        }) ?? false
        try? await persistIndex()
        updateStatus()
        AppleLog.info("Clue", "Local clue deleted id=\(id)")
        clueChanged?(record)
        return removed
    }

    /// Clue photos that belong to an awaiting-map flight (shared rule in AwaitingMapClueMatch).
    func awaitingFlightClues(_ flight: AwaitingMapFlight, otherFlights: [AwaitingMapFlight]) -> [OperationalClueRecord] {
        AwaitingMapClueMatch.ownedClues(records, flight: flight, otherFlights: otherFlights)
    }

    func imageURL(for record: OperationalClueRecord) -> URL {
        root.appendingPathComponent(record.imageFilename)
    }

    func thumbnailURL(for record: OperationalClueRecord) -> URL {
        root.appendingPathComponent(record.thumbnailFilename)
    }

    /// Clues and local markers owned by `flight` (shared ownership rule), oldest first, with photos.
    func archiveClues(
        flight: AwaitingMapClueMatch.Candidate,
        otherFlights: [AwaitingMapClueMatch.Candidate]
    ) async -> [AppleTrackArchiveClue] {
        let snapshot = records, root = self.root
        return (try? await disk.run {
            let candidates = [flight] + otherFlights.filter { $0.id != flight.id }
            let owned = snapshot.filter { AwaitingMapClueMatch.ownerID($0, candidates: candidates) == flight.id }
                .sorted { $0.capturedAt < $1.capturedAt }
            return owned.map { AppleTrackArchiveClue(record: $0, jpegData: try? Data(contentsOf: root.appendingPathComponent($0.imageFilename))) }
        }) ?? []
    }

    /// Every clue of an aircraft, for KMZ rebuilds.
    func clues(aircraftID: String) -> [OperationalClueRecord] {
        let canonical = RidTrackStore.canonicalAircraftID(aircraftID)
        return records.filter { RidTrackStore.canonicalAircraftID($0.aircraftID) == canonical }
    }

    private func enqueueUpload(_ id: UUID) {
        guard uploadTasks[id] == nil else { return }
        guard let record = records.first(where: { $0.id == id }),
              record.canAutomaticallyPublish(mapID: mapID, teamID: teamID) else {
            let stored = records.first(where: { $0.id == id })
            AppleLog.warning(
                "Clue",
                "Clue upload skipped id=\(id) reason=destination-mismatch mapId=\(mapID) teamId=\(teamID) storedMap=\(stored?.destinationMapID ?? "nil") storedTeam=\(stored?.destinationTeamID ?? "nil") state=\(stored?.uploadState.rawValue ?? "missing")"
            )
            mutate(id) { $0.lastUploadError = "Waiting for the original map, or review to choose a destination." }
            Task { try? await persistIndex() }
            updateStatus()
            return
        }
        let generation = configurationGeneration
        let destinationTeamID = teamID
        guard client != nil, !teamID.isEmpty else {
            AppleLog.warning(
                "Clue",
                "Clue upload skipped id=\(id) reason=caltopo-not-configured mapId=\(mapID) teamId=\(teamID)"
            )
            mutate(id) { record in
                record.uploadState = .pending
                record.lastUploadError = "CalTopo credentials, team ID, or map are not configured."
            }
            Task { try? await persistIndex() }
            updateStatus()
            return
        }
        uploadTasks[id] = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled && generation == self.configurationGeneration {
                guard let record = self.records.first(where: { $0.id == id }),
                      record.uploadState != .published,
                      record.uploadState != .localOnly,
                      let client = self.client
                else { break }
                let imageURL = self.imageURL(for: record)
                guard let jpeg = try? await self.disk.run({ try Data(contentsOf: imageURL) }),
                      !Task.isCancelled, generation == self.configurationGeneration,
                      self.records.contains(where: { $0.id == id }) else { break }
                self.mutate(id) { value in
                    value.uploadState = .uploading
                    value.uploadAttempts += 1
                    value.lastUploadError = nil
                }
                try? await self.persistIndex()
                self.updateStatus()
                do {
                    AppleLog.debug(
                        "Clue",
                        "Clue upload start id=\(id) mapId=\(self.mapID) teamId=\(destinationTeamID)"
                    )
                    let folderID = try await self.resolveTrackFolder(using: client)
                    guard !Task.isCancelled, generation == self.configurationGeneration else { break }
                    let markerID = try await client.publishPhotoClue(CaltopoPhotoClue(
                        markerID: id,
                        mediaID: record.caltopoMediaID,
                        latitude: record.clueLatitude,
                        longitude: record.clueLongitude,
                        title: record.title,
                        description: record.publishedDescription,
                        createdMilliseconds: Int64(record.capturedAt.timeIntervalSince1970 * 1_000),
                        jpegData: jpeg,
                        teamID: destinationTeamID,
                        folderID: folderID
                    ))
                    self.mutate(id) { value in
                        value.uploadState = .published
                        value.caltopoMarkerID = markerID
                        value.lastUploadError = nil
                    }
                    try? await self.persistIndex()
                    self.updateStatus()
                    AppleLog.debug("Clue", "CalTopo clue published id=\(id) marker=\(markerID)")
                    break
                } catch {
                    guard !Task.isCancelled, generation == self.configurationGeneration else { break }
                    let attempts = self.records.first(where: { $0.id == id })?.uploadAttempts ?? 1
                    self.mutate(id) { value in
                        value.uploadState = .failed
                        value.lastUploadError = error.localizedDescription
                    }
                    try? await self.persistIndex()
                    self.updateStatus()
                    if case let CaltopoLiveClientError.httpStatus(code, _) = error,
                       [400, 401, 403, 404, 413, 422].contains(code) {
                        // Real auth failures stop. Do not spin on 401/403.
                        break
                    }
                    // Match Android: 2 s doubling, capped at 60 s. One uploadTasks owner per id.
                    let delay = min(60.0, 2.0 * pow(2.0, Double(max(attempts - 1, 0))))
                    AppleLog.warning(
                        "Clue",
                        "CalTopo clue upload failed id=\(id) attempt=\(attempts) retry=\(Int(delay))s error=\(error.localizedDescription)"
                    )
                    try? await Task.sleep(for: .seconds(delay))
                }
            }
            if generation == self.configurationGeneration {
                self.uploadTasks.removeValue(forKey: id)
            }
        }
    }

    private func resolveTrackFolder(using client: CaltopoLiveClient) async throws -> String {
        if let trackFolderID { return trackFolderID }
        let folderName = trackFolderName
        let generation = configurationGeneration
        let resolver = folderResolver
        let resolved = try await resolver.resolve(
            trackFolderName: folderName,
            settleDelay: .milliseconds(500),
            fetchSnapshot: {
                try await client.fetchMapArtifacts(refresh: true)
            },
            createFolder: { title, visible, labelVisible in
                try await client.createFolder(
                    title: title,
                    visible: visible,
                    labelVisible: labelVisible
                )
            },
            deleteFolder: { folderID in
                try await client.deleteFolder(folderID: folderID)
                AppleLog.info(
                    "Clue",
                    "Removed empty duplicate clue folder id=\(folderID)"
                )
            }
        )
        if generation == configurationGeneration { trackFolderID = resolved.active }
        return resolved.active
    }

    private func mutate(_ id: UUID, _ body: (inout OperationalClueRecord) -> Void) {
        guard let index = records.firstIndex(where: { $0.id == id }) else { return }
        body(&records[index])
    }

    private var pruning = false
    func pruneDeletedStorage() async {
        guard !pruning else { return }
        pruning = true
        defer { pruning = false }
        await initialLoad?.value
        guard !indexLoadFailed, !records.isEmpty else { return }
        let snapshot = records, root = self.root, revision = reviewRevision
        do {
            let prepared = try await disk.run {
                // A permission/protection/provider error is not proof of deletion.
                _ = try root.resourceValues(forKeys: [.isDirectoryKey])
                var missing: Set<UUID> = []
                for record in snapshot {
                    do { _ = try root.appendingPathComponent(record.imageFilename).resourceValues(forKeys: [.isRegularFileKey]) }
                    catch let error as CocoaError where error.code == .fileReadNoSuchFile { missing.insert(record.id) }
                }
                return (missing, snapshot.filter { !missing.contains($0.id) })
            }
            guard revision == reviewRevision, !Task.isCancelled else { return }
            if !prepared.0.isEmpty {
                for id in prepared.0 { uploadTasks.removeValue(forKey: id)?.cancel() }
                records = prepared.1
            }
            updateStatus()
        } catch {
            status = "Clue storage is unavailable; saved history has been kept."
        }
    }

    private func persistIndex() async throws {
        guard !indexLoadFailed else { throw CocoaError(.fileReadUnknown) }
        // Capture before suspension. The serial worker commits snapshots in order.
        let snapshot = records, root = self.root
        indexRevision += 1
        try await disk.run {
            let grouped = Dictionary(grouping: snapshot) { $0.imageFilename.components(separatedBy: "/")[0] }
            let directories = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            for directory in directories where AppleFlightStorage.date(directory.lastPathComponent) != nil {
                let index = directory.appendingPathComponent("clues.json")
                let dayRecords = grouped[directory.lastPathComponent] ?? []
                guard !dayRecords.isEmpty || FileManager.default.fileExists(atPath: index.path) else { continue }
                do { try JSONEncoder().encode(dayRecords).write(to: index, options: .atomic) }
                catch { if FileManager.default.fileExists(atPath: directory.path) { throw error } }
            }
        }
    }

    private func updateStatus() {
        guard !indexLoadFailed else { return }
        let pending = records.filter { $0.uploadState == .pending || $0.uploadState == .uploading || $0.uploadState == .failed }.count
        status = records.isEmpty ? "No local clues" : "\(records.count) local clues\(pending > 0 ? "; \(pending) awaiting CalTopo" : "")"
    }

    private nonisolated static func thumbnailJPEG(from data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 180,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image).jpegData(compressionQuality: 0.75)
    }
}
