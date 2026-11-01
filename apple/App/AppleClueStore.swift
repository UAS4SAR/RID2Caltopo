import Foundation
import ImageIO
import R2CCore
import UIKit

private struct ClueUploadAttemptTimeout: Error {
    let kind: Kind
    enum Kind: String {
        case send
        case submit
    }
}

struct PendingClueUploadRow: Identifiable, Equatable, Sendable {
    let id: UUID
    let title: String
    let designator: String
    let capturedAt: Date
    let uploadAttempts: Int
    let lastErrorPlain: String
    let mapName: String
    let mapID: String
    let credentialProfile: String
}

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
    /// True when Submit already published a DEM ray-march / nadir-DEM point.
    var terrainProjectionApplied: Bool = false
    /// Projection height used at Submit (AGL/ATO/DEM-recovered); drives post-save refine.
    var projectionHeightMeters: Double? = nil
    var terrainReferenceLatitude: Double? = nil
    var terrainReferenceLongitude: Double? = nil
    var relativeUpMeters: Double? = nil
}

@MainActor
final class AppleClueStore: ObservableObject {
    @Published private(set) var records: [OperationalClueRecord] = [] {
        didSet { reviewRevision &+= 1 }
    }
    @Published private(set) var reviewRevision = 0
    @Published private(set) var status = "No local clues"
    @Published private(set) var pendingUploadCount = 0
    @Published private(set) var pendingUploadRows: [PendingClueUploadRow] = []
    /// Brief status for Retry now / Upload as new ("Retrying…", then success or plain error).
    @Published private(set) var pendingUploadActionFeedback: String?

    private let disk = OperationalWorkQueue(label: "org.ncssar.clues.disk")
    private var initialLoad: Task<Void, Never>?
    private var indexLoadFailed = false
    private var indexRevision = 0
    private let root: URL
    private var personalLogin = false
    private var client: CaltopoLiveClient?
    private var teamID = ""
    private var mapID = ""
    private var mapTitle = ""
    private var credentialProfile = ""
    private var configurationGeneration = 0
    private var trackFolderName = "Drone Tracks"
    private var trackFolderID: String?
    private var folderResolver = CaltopoTrackFolderResolver()
    private var uploadTasks: [UUID: Task<Void, Never>] = [:]
    private var integrityScanDone = false
    /// Consecutive identical permanent 4xx fingerprints per clue (auto-retry bound).
    private var permanentFailureStreak: [UUID: (fingerprint: String, count: Int)] = [:]
    /// Shared with the track publisher so clue and track folder creation stay one path.
    var ensureMapArtifacts: ((CaltopoMapArtifactReason) async throws -> String)?
    /// Cached folder id for clue send / later move (never blocks Submit).
    var cachedTrackFolderID: (() async -> String?)?
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
                // Integrity scan must not block clue Submit. Run it after the index is ready.
                Task { [weak self] in await self?.performDurabilityIntegrityScan() }
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
        trackFolderName: String = "Drone Tracks",
        mapTitle: String = "",
        credentialProfile: String = ""
    ) {
        uploadTasks.values.forEach { $0.cancel() }
        uploadTasks.removeAll()
        permanentFailureStreak.removeAll()
        configurationGeneration += 1
        personalLogin = configuration.personalSessionID != nil
        mapID = configuration.mapID
        teamID = configuration.publicationScope
        self.mapTitle = mapTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        self.credentialProfile = credentialProfile.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? (personalLogin ? "Personal" : "Team")
            : credentialProfile.trimmingCharacters(in: .whitespacesAndNewlines)
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

    /// Network-path wake and startup: re-enqueue pending/failed uploads without reconfiguring.
    func resume() {
        guard client != nil, !teamID.isEmpty else { return }
        if !AppleNetworkDiagnosticCenter.shared.internetAvailable {
            AppleLog.info("Clue", "resumePendingUploads: deferred offline")
            return
        }
        let generation = configurationGeneration
        Task {
            await client?.cancelInFlightForConnectivityRestore()
            await resumePendingUploads(generation: generation)
        }
    }

    /// Best-effort pending-clue upload wake before map disconnect drops the client
    /// (matches Android `flushPendingCluesBestEffort`).
    func flushPendingBestEffort() {
        guard client != nil, !teamID.isEmpty else { return }
        AppleLog.info("Clue", "flushPendingBestEffort pending=\(pendingUploadCount)")
        resume()
    }

    /// Pending-chip Retry now: immediate, not throttled by backoff. Feedback is plain words.
    func retryPendingUploadsNow() {
        AppleLog.info("Clue", "retryPendingUploadsNow pending=\(pendingUploadCount)")
        pendingUploadActionFeedback = "Retrying…"
        let pendingIDs = records
            .filter { $0.uploadState == .pending || $0.uploadState == .failed || $0.uploadState == .uploading }
            .map(\.id)
        for id in pendingIDs {
            uploadTasks[id]?.cancel()
            uploadTasks.removeValue(forKey: id)
            permanentFailureStreak.removeValue(forKey: id)
            mutate(id) { value in
                if value.uploadState != .published && value.uploadState != .localOnly {
                    value.uploadState = .pending
                    if value.lastUploadError == CluePhotoUploadPolicy.needsAttentionMessage {
                        value.lastUploadError = nil
                    }
                }
            }
        }
        Task { try? await persistIndex() }
        updateStatus()
        resume()
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self else { return }
            if self.pendingUploadCount == 0 {
                self.pendingUploadActionFeedback = "Uploaded"
            } else if let first = self.pendingUploadRows.first {
                self.pendingUploadActionFeedback = first.lastErrorPlain
            }
        }
    }

    /// Upload as a brand-new CalTopo marker (new marker + media ids); local record id unchanged.
    func uploadPendingClueAsNewMarker(_ id: UUID) {
        guard let record = records.first(where: { $0.id == id }),
              record.uploadState != .published, record.uploadState != .localOnly else { return }
        pendingUploadActionFeedback = "Retrying…"
        uploadTasks[id]?.cancel()
        uploadTasks.removeValue(forKey: id)
        permanentFailureStreak.removeValue(forKey: id)
        let freshMarker = UUID()
        let freshMedia = UUID()
        mutate(id) { value in
            value.caltopoMarkerID = freshMarker.uuidString.lowercased()
            value.caltopoMediaID = freshMedia
            value.uploadState = .pending
            value.lastUploadError = nil
        }
        Task { try? await persistIndex() }
        updateStatus()
        enqueueUpload(id)
        AppleLog.info("Clue", "Clue upload as new marker id=\(id) marker=\(freshMarker)")
    }

    /// Stop auto-upload; keep the local photo and metadata (never deletes).
    func dismissPendingClueKeepLocal(_ id: UUID) {
        uploadTasks[id]?.cancel()
        uploadTasks.removeValue(forKey: id)
        permanentFailureStreak.removeValue(forKey: id)
        mutate(id) { value in
            value.uploadState = .localOnly
            value.lastUploadError = nil
        }
        Task { try? await persistIndex() }
        updateStatus()
        AppleLog.info("Clue", "Clue dismiss keep local id=\(id)")
    }

    func resumePendingUploads(generation: Int) async {
        await initialLoad?.value
        guard generation == configurationGeneration else { return }
        // Repair recently published clues that reconciled marker-only (missing photo/link).
        await reverifyRecentPublishedClues()
        guard generation == configurationGeneration else { return }
        // Reconcile before enqueue so a prior client timeout that still landed on CalTopo
        // clears "1 clue pending" without risking a duplicate feature.
        let pendingIDs = records
            .filter { $0.uploadState == .pending || $0.uploadState == .failed || $0.uploadState == .uploading }
            .map(\.id)
        for id in pendingIDs {
            guard generation == configurationGeneration else { return }
            if await reconcileUploadedClue(id: id) { continue }
            enqueueUpload(id)
        }
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
        let saveStarted = Date()
        guard !jpegData.isEmpty else { throw CocoaError(.fileWriteUnknown) }
        // Index load only — never wait on the durability integrity scan.
        if let initialLoad {
            let loadFinished = await withTaskGroup(of: Bool.self) { group in
                group.addTask { await initialLoad.value; return true }
                group.addTask {
                    try? await Task.sleep(for: .milliseconds(250))
                    return false
                }
                let first = await group.next() ?? false
                group.cancelAll()
                return first
            }
            if !loadFinished {
                AppleLog.warning("Clue", "Clue save stage=index-load-timeout elapsedMs=\(Int(Date().timeIntervalSince(saveStarted) * 1_000)); continuing with in-memory index")
            }
        }
        guard !indexLoadFailed else { throw CocoaError(.fileReadUnknown) }
        AppleLog.info("Clue", "Clue save stage=bound elapsedMs=\(Int(Date().timeIntervalSince(saveStarted) * 1_000)) publishWithoutTerrainWait=true")
        let id = UUID()
        let day = AppleFlightStorage.dayName(Date())
        let imageFilename = "\(day)/\(id.uuidString.lowercased()).jpg"
        let thumbnailFilename = "\(day)/\(id.uuidString.lowercased())-thumb.jpg"
        let root = self.root
        // Prefer connected-map destination without disk journal when possible.
        let flightDestination: (map: String?, team: String?)
        if publishToCaltopo {
            if !mapID.isEmpty && !teamID.isEmpty {
                flightDestination = (mapID, teamID)
            } else {
                flightDestination = (try? await withTimeout(seconds: 0.2) {
                    try await self.publicationDestination(for: draft)
                }) ?? (nil, nil)
            }
        } else {
            flightDestination = (nil, nil)
        }
        AppleLog.info("Clue", "Clue save stage=image-prepared elapsedMs=\(Int(Date().timeIntervalSince(saveStarted) * 1_000)) bytes=\(jpegData.count)")
        // Durable image first. Thumbnail is filled after Local clue saved / upload enqueue.
        try await disk.run {
            try FileManager.default.createDirectory(at: root.appendingPathComponent(day), withIntermediateDirectories: true)
            // Never wait on Flight Storage directory walks / purge (Android prepareAppPrivateWrite).
            AppleFlightStorage.prepareAppPrivateWrite(Int64(jpegData.count))
            try jpegData.write(to: root.appendingPathComponent(imageFilename), options: .atomic)
            // Placeholder thumb so the index row is valid; replaced asynchronously.
            try jpegData.write(to: root.appendingPathComponent(thumbnailFilename), options: .atomic)
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
            "Clue save stage=durable id=\(id) elapsedMs=\(Int(Date().timeIntervalSince(saveStarted) * 1_000))"
        )
        AppleLog.info(
            "Clue",
            "Local clue saved id=\(id) designator=\(draft.designator) bytes=\(jpegData.count) publish=\(publishToCaltopo) " +
                "binding=\(draft.binding.map { ClueBindingText.formSummary($0) } ?? "none")"
        )
        updateStatus()
        // The binding was fixed at Submit; the upload starts right away.
        if publishToCaltopo {
            enqueueUpload(id)
            AppleLog.info("Clue", "Clue save stage=queued-upload id=\(id) elapsedMs=\(Int(Date().timeIntervalSince(saveStarted) * 1_000))")
        }
        clueChanged?(record)
        // Thumbnail encode stays off the durable/upload critical path.
        let thumbJPEG = jpegData
        let thumbName = thumbnailFilename
        let diskQueue = disk
        Task.detached(priority: .utility) {
            guard let thumb = Self.thumbnailJPEG(from: thumbJPEG), thumb.count != thumbJPEG.count else { return }
            try? await diskQueue.run {
                try thumb.write(to: root.appendingPathComponent(thumbName), options: .atomic)
            }
            AppleLog.info("Clue", "Clue save stage=thumb id=\(id) bytes=\(thumb.count)")
        }
        return record
    }

    /// Clues whose local position was refined while a POST was in flight; edit CalTopo after publish.
    private var pendingCaltopoTerrainEdits: Set<UUID> = []

    /// After Submit published a flat-ground estimate, apply a later S1M ray-march to the local clue and CalTopo marker (edit, not duplicate).
    func applyTerrainProjectionRefine(
        id: UUID,
        projection: OperationalClueProjection,
        format: OperationalCoordinateDisplayFormat = .decimal
    ) async {
        guard projection.terrainProjectionApplied else { return }
        guard let index = records.firstIndex(where: { $0.id == id }) else { return }
        var record = records[index]
        let previousLat = record.clueLatitude
        let previousLon = record.clueLongitude
        let moved = abs(previousLat - projection.latitude) > 1e-7 || abs(previousLon - projection.longitude) > 1e-7
        record.clueLatitude = projection.latitude
        record.clueLongitude = projection.longitude
        record.clueAltitudeMeters = projection.altitudeMeters
        record.clueDescription = OperationalClueDescription.applyingTerrainProjection(
            record.clueDescription,
            projection: projection,
            format: format
        )
        records[index] = record
        try? await persistIndex()
        clueChanged?(record)
        let offset = ClueUploadPosition.offsetMeters(
            clueLatitude: projection.latitude,
            clueLongitude: projection.longitude,
            droneLatitude: record.droneLatitude,
            droneLongitude: record.droneLongitude
        )
        AppleLog.info(
            "Clue",
            ClueUploadPosition.terrainRefineAppliedLogLine(id: id.uuidString, offsetMeters: offset)
        )
        guard moved else { return }
        // Pending uploads re-read coords before POST. In-flight/published markers need an edit.
        if record.uploadState == .published || record.caltopoMarkerID != nil {
            pendingCaltopoTerrainEdits.remove(id)
            await updatePublishedMarkerPosition(record)
        } else {
            pendingCaltopoTerrainEdits.insert(id)
        }
    }

    private func updatePublishedMarkerPosition(_ record: OperationalClueRecord) async {
        guard let client,
              let markerID = record.caltopoMarkerID ?? Optional(record.id.uuidString.lowercased()),
              !markerID.isEmpty
        else { return }
        let generation = configurationGeneration
        do {
            let folderID = try? await resolveTrackFolder(using: client)
            guard generation == configurationGeneration else { return }
            try await client.updatePhotoClueMarker(
                markerID: markerID,
                latitude: record.clueLatitude,
                longitude: record.clueLongitude,
                title: record.title,
                description: record.publishedDescription,
                folderID: folderID,
                createdMilliseconds: Int64(record.capturedAt.timeIntervalSince1970 * 1_000)
            )
            AppleLog.info(
                "Clue",
                "CalTopo marker position edited id=\(record.id) marker=\(markerID)"
            )
        } catch {
            AppleLog.warning(
                "Clue",
                "CalTopo marker position edit failed id=\(record.id) error=\(error.localizedDescription)"
            )
        }
    }

    private func withTimeout<T: Sendable>(
        seconds: Double,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(for: .seconds(seconds))
                throw CancellationError()
            }
            let value = try await group.next()!
            group.cancelAll()
            return value
        }
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
            var terminalReason = "loop-exit"
            while !Task.isCancelled && generation == self.configurationGeneration {
                guard let record = self.records.first(where: { $0.id == id }) else {
                    terminalReason = "record-missing"
                    break
                }
                if record.uploadState == .published {
                    terminalReason = "already-published"
                    break
                }
                if record.uploadState == .localOnly {
                    terminalReason = "local-only"
                    break
                }
                guard let client = self.client else {
                    terminalReason = "client-nil"
                    break
                }
                let imageURL = self.imageURL(for: record)
                guard let jpeg = try? await self.disk.run({ try Data(contentsOf: imageURL) }) else {
                    terminalReason = "image-unreadable"
                    self.mutate(id) { value in
                        value.uploadState = .failed
                        value.lastUploadError = "Clue photo could not be read for upload."
                    }
                    try? await self.persistIndex()
                    self.updateStatus()
                    AppleLog.warning("Clue", "Clue upload failed id=\(id) reason=image-unreadable")
                    break
                }
                guard !Task.isCancelled, generation == self.configurationGeneration,
                      self.records.contains(where: { $0.id == id }) else {
                    terminalReason = Task.isCancelled || generation != self.configurationGeneration
                        ? "cancelled-or-reconfigured"
                        : "record-removed"
                    break
                }
                self.mutate(id) { value in
                    value.uploadState = .uploading
                    value.uploadAttempts += 1
                    value.lastUploadError = nil
                }
                try? await self.persistIndex()
                self.updateStatus()
                let attempt = self.records.first(where: { $0.id == id })?.uploadAttempts ?? 1
                do {
                    // Prefer the latest local projection (post-save terrain may have landed during image I/O).
                    guard let latest = self.records.first(where: { $0.id == id }) else {
                        terminalReason = "record-missing"
                        break
                    }
                    // Submit / attempt wall-clock starts here. Folder ensure must not count against
                    // the send budget — that timer starts only when publishPhotoClue is invoked.
                    let attemptStartedAt = Date()
                    let submitDeadlineSeconds = CluePhotoUploadPolicy.submitDeadlineSeconds(jpegBytes: jpeg.count)
                    let sendBudgetSeconds = CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: jpeg.count)
                    AppleLog.info(
                        "Clue",
                        "Clue upload start id=\(id) attempt=\(attempt) mapId=\(self.mapID) teamId=\(destinationTeamID) " +
                            "submitDeadline=\(Int(submitDeadlineSeconds))s sendBudget=\(Int(sendBudgetSeconds))s"
                    )
                    let terrainApplied = ClueUploadPosition.descriptionIndicatesTerrainApplied(latest.clueDescription)
                    AppleLog.info(
                        "Clue",
                        ClueUploadPosition.logLine(
                            id: id.uuidString,
                            source: ClueUploadPosition.source(
                                terrainProjectionApplied: terrainApplied,
                                gimbalPitchDegrees: latest.gimbalAngleDegrees
                            ),
                            offsetMeters: ClueUploadPosition.offsetMeters(
                                clueLatitude: latest.clueLatitude,
                                clueLongitude: latest.clueLongitude,
                                droneLatitude: latest.droneLatitude,
                                droneLongitude: latest.droneLongitude
                            )
                        )
                    )
                    // Before a retry, reconcile: a prior timed-out POST may already be on the map.
                    if attempt > 1, await self.reconcileUploadedClue(id: id, jpeg: jpeg, teamID: destinationTeamID) {
                        terminalReason = "reconciled"
                        break
                    }
                    let publishLatitude = latest.clueLatitude
                    let publishLongitude = latest.clueLongitude
                    let publishTitle = latest.title
                    let publishDescription = latest.publishedDescription
                    let publishMediaID = latest.caltopoMediaID
                    let publishCreatedMs = Int64(latest.capturedAt.timeIntervalSince1970 * 1_000)
                    // Cached folder only — never wait on ensure/full-map. Move later if needed.
                    let folderID = await self.cachedFolderIDForClue()
                    let publishMarkerID = Self.publishMarkerID(for: latest)
                    let remainingOverall = submitDeadlineSeconds - Date().timeIntervalSince(attemptStartedAt)
                    guard remainingOverall > 0 else { throw ClueUploadAttemptTimeout(kind: .send) }
                    let sendTimeout = min(sendBudgetSeconds, remainingOverall)
                    AppleLog.info(
                        "Clue",
                        "Clue upload send id=\(id) attempt=\(attempt) folder=\(folderID ?? "none") " +
                            "sendTimeout=\(Int(sendTimeout))s"
                    )
                    let markerID: String = try await withThrowingTaskGroup(of: String.self) { group in
                        group.addTask {
                            try await client.publishPhotoClue(CaltopoPhotoClue(
                                markerID: publishMarkerID,
                                mediaID: publishMediaID,
                                latitude: publishLatitude,
                                longitude: publishLongitude,
                                title: publishTitle,
                                description: publishDescription,
                                createdMilliseconds: publishCreatedMs,
                                jpegData: jpeg,
                                teamID: destinationTeamID,
                                folderID: folderID
                            ))
                        }
                        group.addTask {
                            try await Task.sleep(for: .seconds(sendTimeout))
                            throw ClueUploadAttemptTimeout(kind: .send)
                        }
                        let first = try await group.next()!
                        group.cancelAll()
                        return first
                    }
                    guard !Task.isCancelled, generation == self.configurationGeneration else {
                        terminalReason = "cancelled-after-publish-race"
                        break
                    }
                    self.permanentFailureStreak.removeValue(forKey: id)
                    self.mutate(id) { value in
                        value.uploadState = .published
                        value.caltopoMarkerID = markerID
                        value.lastUploadError = nil
                    }
                    try? await self.persistIndex()
                    self.updateStatus()
                    AppleLog.info(
                        "Clue",
                        "Clue upload success id=\(id) attempt=\(attempt) status=200 marker=\(markerID)"
                    )
                    // Best-effort folder move when folders were created after a folder-less send.
                    await self.movePublishedClueToTrackFolderIfNeeded(id: id)
                    // Terrain may have refined local coords while the flat POST was in flight.
                    if self.pendingCaltopoTerrainEdits.remove(id) != nil,
                       let published = self.records.first(where: { $0.id == id }) {
                        await self.updatePublishedMarkerPosition(published)
                    }
                    terminalReason = "published"
                    break
                } catch let CaltopoLiveClientError.httpStatus(code, message)
                    where CluePhotoUploadPolicy.isAmbiguousMediaState(statusCode: code, message: message)
                        || (code == 409 && message.localizedCaseInsensitiveContains(
                            CluePhotoUploadPolicy.existingBackendMediaToken
                        ))
                {
                    // Ambiguous / unusable media: persist a NEW media id before the next send.
                    await self.regenerateMediaID(for: id, reason: "ambiguous-media")
                    terminalReason = "media-regenerated"
                    continue
                } catch let timeout as ClueUploadAttemptTimeout {
                    let attempts = self.records.first(where: { $0.id == id })?.uploadAttempts ?? 1
                    let budget = Int(CluePhotoUploadPolicy.attemptBudgetSeconds(jpegBytes: jpeg.count))
                    let token = "timeout-\(timeout.kind.rawValue)-\(budget)s"
                    // Timeout during media/data is ambiguous — mint a new media id before retry.
                    await self.regenerateMediaID(for: id, reason: "timeout")
                    self.mutate(id) { value in
                        value.uploadState = .failed
                        value.lastUploadError = "Upload timed out after \(budget)s; will retry."
                    }
                    try? await self.persistIndex()
                    self.updateStatus()
                    let delay = min(60.0, 2.0 * pow(2.0, Double(max(attempts - 1, 0))))
                    AppleLog.warning(
                        "Clue",
                        "Clue upload failed id=\(id) attempt=\(attempts) retry=\(Int(delay))s error=\(token)"
                    )
                    if await self.reconcileUploadedClue(id: id, jpeg: jpeg, teamID: destinationTeamID) {
                        terminalReason = "reconciled-after-timeout"
                        break
                    }
                    terminalReason = "timeout-retrying"
                    try? await Task.sleep(for: .seconds(delay))
                } catch {
                    guard !Task.isCancelled, generation == self.configurationGeneration else {
                        terminalReason = "cancelled-or-reconfigured"
                        self.mutate(id) { value in
                            if value.uploadState == .uploading { value.uploadState = .pending }
                            value.lastUploadError = "Upload interrupted; will retry."
                        }
                        try? await self.persistIndex()
                        self.updateStatus()
                        AppleLog.warning(
                            "Clue",
                            "Clue upload interrupted id=\(id) attempt=\(attempt) reason=cancelled-or-reconfigured error=\(error.localizedDescription)"
                        )
                        break
                    }
                    let attempts = self.records.first(where: { $0.id == id })?.uploadAttempts ?? 1
                    if case let CaltopoLiveClientError.httpStatus(code, message) = error,
                       ClueTrackDurabilityIntegrity.isClientFailureNeedingReconcile(statusCode: code) {
                        AppleLog.warning(
                            "Clue",
                            "Clue upload failed id=\(id) attempt=\(attempts) reason=http-\(code) error=\(message)"
                        )
                        if await self.reconcileUploadedClue(id: id, jpeg: jpeg, teamID: destinationTeamID) {
                            terminalReason = "reconciled-after-http-\(code)"
                            break
                        }
                        if CluePhotoUploadPolicy.isPermanentClientFailure(statusCode: code),
                           await self.notePermanentFailure(id: id, statusCode: code, message: message) {
                            terminalReason = "needs-attention"
                            break
                        }
                        self.mutate(id) { value in
                            value.uploadState = .failed
                            value.lastUploadError = CluePhotoUploadPolicy.plainUploadError(message)
                        }
                        try? await self.persistIndex()
                        self.updateStatus()
                        let delay = min(60.0, 2.0 * pow(2.0, Double(max(attempts - 1, 0))))
                        AppleLog.warning(
                            "Clue",
                            "Clue upload failed id=\(id) attempt=\(attempts) retry=\(Int(delay))s error=\(message)"
                        )
                        terminalReason = "failed-retrying"
                        try? await Task.sleep(for: .seconds(delay))
                        continue
                    }
                    self.mutate(id) { value in
                        value.uploadState = .failed
                        value.lastUploadError = CluePhotoUploadPolicy.plainUploadError(error.localizedDescription)
                    }
                    try? await self.persistIndex()
                    self.updateStatus()
                    let delay = min(60.0, 2.0 * pow(2.0, Double(max(attempts - 1, 0))))
                    AppleLog.warning(
                        "Clue",
                        "Clue upload failed id=\(id) attempt=\(attempts) retry=\(Int(delay))s error=\(error.localizedDescription)"
                    )
                    terminalReason = "failed-retrying"
                    try? await Task.sleep(for: .seconds(delay))
                }
            }
            if terminalReason != "published" && terminalReason != "already-published" {
                AppleLog.info("Clue", "Clue upload end id=\(id) reason=\(terminalReason)")
            }
            if generation == self.configurationGeneration {
                self.uploadTasks.removeValue(forKey: id)
            }
        }
    }

    /// Returns true only when marker + media + link are all present (completing any missing part).
    @discardableResult
    private func reconcileUploadedClue(id: UUID, jpeg: Data? = nil, teamID: String? = nil) async -> Bool {
        guard let client, let record = records.first(where: { $0.id == id }) else { return false }
        let destinationTeamID = teamID
            ?? record.destinationTeamID
            ?? self.teamID
        guard !destinationTeamID.isEmpty else { return false }
        do {
            let jpegData: Data
            if let jpeg {
                jpegData = jpeg
            } else {
                let imageURL = self.imageURL(for: record)
                guard let loaded = try? await disk.run({ try Data(contentsOf: imageURL) }) else { return false }
                jpegData = loaded
            }
            // Cached folder only — reconcile must never wait on folder ensure / full-map reads.
            let folderID = await cachedFolderIDForClue()
            guard let result = try await client.reconcilePhotoClue(
                CaltopoPhotoClue(
                    markerID: Self.publishMarkerID(for: record),
                    mediaID: record.caltopoMediaID,
                    latitude: record.clueLatitude,
                    longitude: record.clueLongitude,
                    title: record.title,
                    description: record.publishedDescription,
                    createdMilliseconds: Int64(record.capturedAt.timeIntervalSince1970 * 1_000),
                    jpegData: jpegData,
                    teamID: destinationTeamID,
                    folderID: folderID
                )
            ) else { return false }
            permanentFailureStreak.removeValue(forKey: id)
            mutate(id) { value in
                value.uploadState = .published
                value.caltopoMarkerID = result.featureID
                value.lastUploadError = nil
            }
            try? await persistIndex()
            updateStatus()
            AppleLog.info("Clue", result.partsLog)
            AppleLog.info(
                "Clue",
                "Clue upload reconciled id=\(id) featureId=\(result.featureID)"
            )
            await movePublishedClueToTrackFolderIfNeeded(id: id)
            return true
        } catch let CaltopoLiveClientError.httpStatus(code, message)
            where CluePhotoUploadPolicy.isAmbiguousMediaState(statusCode: code, message: message)
        {
            await regenerateMediaID(for: id, reason: "reconcile-ambiguous")
            return false
        } catch {
            AppleLog.warning(
                "Clue",
                "Clue upload reconcile failed id=\(id) error=\(error.localizedDescription)"
            )
            return false
        }
    }

    private static func publishMarkerID(for record: OperationalClueRecord) -> UUID {
        if let raw = record.caltopoMarkerID?.trimmingCharacters(in: .whitespacesAndNewlines),
           let parsed = UUID(uuidString: raw) {
            return parsed
        }
        return record.id
    }

    private func cachedFolderIDForClue() async -> String? {
        if let trackFolderID, !trackFolderID.isEmpty { return trackFolderID }
        if let fromPublisher = await cachedTrackFolderID?(), !fromPublisher.isEmpty {
            trackFolderID = fromPublisher
            return fromPublisher
        }
        return nil
    }

    private func regenerateMediaID(for id: UUID, reason: String) async {
        let fresh = UUID()
        mutate(id) { value in
            value.caltopoMediaID = fresh
            if value.uploadState != .published && value.uploadState != .localOnly {
                value.uploadState = .pending
            }
            value.lastUploadError = "Replacing unusable CalTopo photo; retrying."
        }
        try? await persistIndex()
        updateStatus()
        AppleLog.warning("Clue", "Clue upload regenerating media id=\(id) reason=\(reason)")
    }

    /// Returns true when the clue should stop auto-retry ("Needs attention").
    @discardableResult
    private func notePermanentFailure(id: UUID, statusCode: Int, message: String) async -> Bool {
        let fingerprint = CluePhotoUploadPolicy.permanentFailureFingerprint(
            statusCode: statusCode, message: message)
        var streak = permanentFailureStreak[id] ?? (fingerprint, 0)
        if streak.fingerprint == fingerprint {
            streak.count += 1
        } else {
            streak = (fingerprint, 1)
        }
        permanentFailureStreak[id] = streak
        if streak.count >= CluePhotoUploadPolicy.permanentFailureLimit {
            mutate(id) { value in
                value.uploadState = .failed
                value.lastUploadError = CluePhotoUploadPolicy.needsAttentionMessage
            }
            try? await persistIndex()
            updateStatus()
            AppleLog.warning(
                "Clue",
                "Clue upload needs attention id=\(id) fingerprint=\(fingerprint) count=\(streak.count)"
            )
            return true
        }
        return false
    }

    /// After a folder-less send, move the marker once Drone Tracks exists. Never fails the clue.
    private func movePublishedClueToTrackFolderIfNeeded(id: UUID) async {
        guard let client, let record = records.first(where: { $0.id == id }),
              record.uploadState == .published else { return }
        let folderID: String
        if let cached = await cachedFolderIDForClue() {
            folderID = cached
        } else if let ensureMapArtifacts {
            // First flight (or later) may create folders; clue move is best-effort only.
            do {
                folderID = try await ensureMapArtifacts(.firstTrack)
                trackFolderID = folderID
            } catch {
                AppleLog.warning(
                    "Clue",
                    "Clue folder move skipped id=\(id) error=\(error.localizedDescription)"
                )
                return
            }
        } else {
            return
        }
        do {
            try await client.updatePhotoClueMarker(
                markerID: record.caltopoMarkerID ?? record.id.uuidString.lowercased(),
                latitude: record.clueLatitude,
                longitude: record.clueLongitude,
                title: record.title,
                description: record.publishedDescription,
                folderID: folderID,
                createdMilliseconds: Int64(record.capturedAt.timeIntervalSince1970 * 1_000)
            )
            AppleLog.info("Clue", "Clue folder move ok id=\(id) folder=\(folderID)")
        } catch {
            AppleLog.warning(
                "Clue",
                "Clue folder move failed id=\(id) error=\(error.localizedDescription)"
            )
        }
    }

    /// Re-verify recently published clues (last 24 h) and repair missing photos/links.
    func reverifyRecentPublishedClues() async {
        guard let client, !teamID.isEmpty else { return }
        await initialLoad?.value
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
        let recent = records.filter {
            $0.uploadState == .published && $0.capturedAt >= cutoff
        }
        for record in recent {
            do {
                let parts = try await client.probePhotoClueParts(
                    markerID: Self.publishMarkerID(for: record),
                    mediaID: record.caltopoMediaID,
                    teamID: record.destinationTeamID ?? teamID
                )
                if parts.allPresent { continue }
                // Nothing visible may be a transient map/auth miss — do not demote published.
                if !parts.anyPresent { continue }
                if await reconcileUploadedClue(id: record.id) { continue }
                // Still incomplete after repair: re-queue without losing the local clue.
                mutate(record.id) { value in
                    value.uploadState = .pending
                    value.lastUploadError = "Re-verify found incomplete CalTopo parts; repairing."
                }
                try? await persistIndex()
                enqueueUpload(record.id)
                AppleLog.info(
                    "Clue",
                    "Clue re-verify queued repair id=\(record.id)"
                )
            } catch {
                AppleLog.warning(
                    "Clue",
                    "Clue re-verify skipped id=\(record.id) error=\(error.localizedDescription)"
                )
            }
        }
        updateStatus()
    }

    /// First-flight / recovery only. Clue Submit never calls this before send.
    private func resolveTrackFolder(using client: CaltopoLiveClient) async throws -> String {
        if let trackFolderID { return trackFolderID }
        let generation = configurationGeneration
        if let ensureMapArtifacts {
            let folderID = try await ensureMapArtifacts(.firstTrack)
            if generation == configurationGeneration { trackFolderID = folderID }
            return folderID
        }
        // Fallback for unit tests that do not wire the shared publisher path.
        let folderName = trackFolderName
        let resolver = folderResolver
        let resolved = try await resolver.resolve(
            trackFolderName: folderName,
            settleDelay: .zero,
            fetchSnapshot: {
                // Warm connect snapshot only — never force a gated full-map read.
                try await client.fetchMapArtifacts(refresh: false)
            },
            createFolder: { title, visible, labelVisible in
                try await client.createFolder(
                    title: title,
                    visible: visible,
                    labelVisible: labelVisible
                )
            }
        )
        if resolved.created {
            AppleLog.info(
                "CalTopo",
                "Created map artifacts reason=first-track active=\(resolved.active) archive=\(resolved.archive) folder='\(folderName)'"
            )
        }
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
                    do {
                        let values = try root.appendingPathComponent(record.imageFilename)
                            .resourceValues(forKeys: [.isRegularFileKey])
                        if values.isRegularFile != true { missing.insert(record.id) }
                    } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
                        missing.insert(record.id)
                    }
                }
                // Keep index rows; flag missing photos. Operator delete alone removes metadata.
                let flagged = ClueTrackDurabilityIntegrity.flagMissingPhotos(in: snapshot, missingIDs: missing)
                return (missing, flagged.records, flagged.flagged)
            }
            guard revision == reviewRevision, !Task.isCancelled else { return }
            if !prepared.0.isEmpty {
                for id in prepared.0 { uploadTasks.removeValue(forKey: id)?.cancel() }
                records = prepared.1
                if prepared.2 > 0 { try? await persistIndex() }
            }
            updateStatus()
        } catch {
            status = "Clue storage is unavailable; saved history has been kept."
        }
    }

    /// Startup scan: orphan photos → re-index; index without photo → keep + failed.
    func runDurabilityIntegrityScan() async {
        await initialLoad?.value
        await performDurabilityIntegrityScan()
    }

    private func performDurabilityIntegrityScan() async {
        guard !integrityScanDone, !indexLoadFailed else { return }
        integrityScanDone = true
        let snapshot = records, root = self.root
        do {
            let result = try await disk.run {
                try ClueTrackDurabilityIntegrity.scan(root: root, records: snapshot)
            }
            if result.summary.orphanPhotosReindexed > 0 || result.summary.missingPhotoFlagged > 0 {
                records = result.records
                try? await persistIndex()
            }
            updateStatus()
            AppleLog.info("Clue", result.summary.description)
        } catch {
            AppleLog.warning("Clue", "Durability integrity scan failed: \(error.localizedDescription)")
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
        let pending = ClueTrackDurabilityIntegrity.pendingUploadCount(in: records)
        pendingUploadCount = pending
        pendingUploadRows = records
            .filter { $0.uploadState == .pending || $0.uploadState == .failed || $0.uploadState == .uploading }
            .sorted { $0.capturedAt > $1.capturedAt }
            .map {
                PendingClueUploadRow(
                    id: $0.id,
                    title: $0.title.isEmpty ? "Untitled clue" : $0.title,
                    designator: $0.designator,
                    capturedAt: $0.capturedAt,
                    uploadAttempts: $0.uploadAttempts,
                    lastErrorPlain: CluePhotoUploadPolicy.plainUploadError($0.lastUploadError),
                    mapName: mapTitle.isEmpty ? mapID : mapTitle,
                    mapID: $0.destinationMapID ?? mapID,
                    credentialProfile: credentialProfile
                )
            }
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
