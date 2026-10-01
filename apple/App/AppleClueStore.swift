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
}

@MainActor
final class AppleClueStore: ObservableObject {
    @Published private(set) var records: [OperationalClueRecord] = []
    @Published private(set) var status = "No local clues"

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

    init(fileManager: FileManager = .default) {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        root = documents.appendingPathComponent("RID2Caltopo/FlightStorage", isDirectory: true)
        loadIndex()
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
        teamID = personalLogin ? "personal:" + configuration.personalAccountID : configuration.teamID
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
            let journalURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("awaiting-map-flights.json")
            let decisions = AwaitingMapFlightJournal(fileURL: journalURL).entries
            for flight in decisions where ["publish", "queued"].contains(flight.decision) && flight.mapID == mapID && flight.teamID == teamID {
                bindAwaitingFlight(flight, mapID: mapID, teamID: teamID)
            }
            records.filter { $0.uploadState == .pending || $0.uploadState == .failed || $0.uploadState == .uploading }
                .forEach { enqueueUpload($0.id) }
            updateStatus()
        } catch {
            client = nil
            status = "Clues local; CalTopo configuration failed"
        }
    }

    @discardableResult
    func save(_ draft: AppleClueDraft, jpegData: Data, publishToCaltopo: Bool) throws -> OperationalClueRecord {
        guard !jpegData.isEmpty else { throw CocoaError(.fileWriteUnknown) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let id = UUID()
        let day = AppleFlightStorage.dayName(Date())
        try FileManager.default.createDirectory(at: root.appendingPathComponent(day), withIntermediateDirectories: true)
        let imageFilename = "\(day)/\(id.uuidString.lowercased()).jpg"
        let thumbnailFilename = "\(day)/\(id.uuidString.lowercased())-thumb.jpg"
        AppleFlightStorage.prepareWrite(Int64(jpegData.count) * 2)
        try jpegData.write(to: root.appendingPathComponent(imageFilename), options: .atomic)
        let thumbnailData = Self.thumbnailJPEG(from: jpegData) ?? jpegData
        try thumbnailData.write(to: root.appendingPathComponent(thumbnailFilename), options: .atomic)
        let flightDestination = publicationDestination(for: draft)
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
            destinationTeamID: publishToCaltopo ? flightDestination.team : nil
        )
        records.insert(record, at: 0)
        try persistIndex()
        AppleLog.info(
            "Clue",
            "Local clue saved id=\(id) designator=\(draft.designator) bytes=\(jpegData.count) publish=\(publishToCaltopo)"
        )
        updateStatus()
        if publishToCaltopo { enqueueUpload(id) }
        return record
    }

    private func publicationDestination(for draft: AppleClueDraft) -> (map: String?, team: String?) {
        if personalLogin { return (mapID.isEmpty ? nil : mapID, teamID) }
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("awaiting-map-flights.json")
        if let data = try? Data(contentsOf: url), let flights = try? JSONDecoder().decode([AwaitingMapFlight].self, from: data),
           let flight = flights.last(where: { !$0.finished && RidTrackStore.canonicalAircraftID($0.remoteID) == RidTrackStore.canonicalAircraftID(draft.aircraftID) && $0.decision != "local" &&
               draft.capturedAt >= $0.firstTime && draft.capturedAt.timeIntervalSince($0.lastTime) <= 30 }) {
            return (flight.mapID.isEmpty ? nil : flight.mapID, flight.teamID.isEmpty ? nil : flight.teamID)
        }
        return (mapID.isEmpty ? nil : mapID, teamID.isEmpty ? nil : teamID)
    }

    func bindAwaitingFlight(_ flight: AwaitingMapFlight, mapID: String, teamID: String) {
        let previous = records
        for index in records.indices where records[index].destinationMapID == nil &&
                records[index].uploadState != .localOnly && records[index].uploadState != .published &&
                RidTrackStore.canonicalAircraftID(records[index].aircraftID) == RidTrackStore.canonicalAircraftID(flight.remoteID) &&
                records[index].capturedAt >= flight.firstTime && records[index].capturedAt <= flight.lastTime {
            records[index].destinationMapID = mapID
            records[index].destinationTeamID = teamID
        }
        do { try persistIndex() } catch { records = previous; status = "Clue destination could not be saved"; return }
        for record in records where record.canAutomaticallyPublish(mapID: self.mapID, teamID: self.teamID) && record.uploadState == .pending {
            enqueueUpload(record.id)
        }
    }

    func retry(_ id: UUID) {
        mutate(id) { record in
            record.uploadState = .pending
            record.lastUploadError = nil
        }
        try? persistIndex()
        enqueueUpload(id)
    }

    func delete(_ id: UUID) {
        uploadTasks.removeValue(forKey: id)?.cancel()
        guard let index = records.firstIndex(where: { $0.id == id }) else { return }
        let record = records.remove(at: index)
        try? FileManager.default.removeItem(at: imageURL(for: record))
        try? FileManager.default.removeItem(at: thumbnailURL(for: record))
        try? persistIndex()
        updateStatus()
        AppleLog.info("Clue", "Local clue deleted id=\(id)")
    }

    func imageURL(for record: OperationalClueRecord) -> URL {
        root.appendingPathComponent(record.imageFilename)
    }

    func thumbnailURL(for record: OperationalClueRecord) -> URL {
        root.appendingPathComponent(record.thumbnailFilename)
    }

    func archiveClues(
        aircraftID: String,
        from start: Date,
        through end: Date
    ) -> [AppleTrackArchiveClue] {
        let canonicalID = RidTrackStore.canonicalAircraftID(aircraftID)
        return records
            .filter {
                RidTrackStore.canonicalAircraftID($0.aircraftID) == canonicalID
                    && $0.capturedAt >= start
                    && $0.capturedAt <= end
            }
            .sorted { $0.capturedAt < $1.capturedAt }
            .map {
                AppleTrackArchiveClue(
                    record: $0,
                    jpegData: try? Data(contentsOf: imageURL(for: $0))
                )
            }
    }

    private func enqueueUpload(_ id: UUID) {
        guard uploadTasks[id] == nil else { return }
        guard let record = records.first(where: { $0.id == id }),
              record.canAutomaticallyPublish(mapID: mapID, teamID: teamID) else {
            mutate(id) { $0.lastUploadError = "Waiting for the original map, or review to choose a destination." }
            try? persistIndex()
            updateStatus()
            return
        }
        let generation = configurationGeneration
        let destinationTeamID = teamID
        guard client != nil, !teamID.isEmpty else {
            mutate(id) { record in
                record.uploadState = .pending
                record.lastUploadError = "CalTopo credentials, team ID, or map are not configured."
            }
            try? persistIndex()
            updateStatus()
            return
        }
        uploadTasks[id] = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled && generation == self.configurationGeneration {
                guard let record = self.records.first(where: { $0.id == id }),
                      record.uploadState != .published,
                      record.uploadState != .localOnly,
                      let client = self.client,
                      let jpeg = try? Data(contentsOf: self.imageURL(for: record))
                else { break }
                self.mutate(id) { value in
                    value.uploadState = .uploading
                    value.uploadAttempts += 1
                    value.lastUploadError = nil
                }
                try? self.persistIndex()
                self.updateStatus()
                do {
                    let folderID = try await self.resolveTrackFolder(using: client)
                    guard !Task.isCancelled, generation == self.configurationGeneration else { break }
                    let markerID = try await client.publishPhotoClue(CaltopoPhotoClue(
                        markerID: id,
                        mediaID: record.caltopoMediaID,
                        latitude: record.clueLatitude,
                        longitude: record.clueLongitude,
                        title: record.title,
                        description: record.clueDescription,
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
                    try? self.persistIndex()
                    self.updateStatus()
                    AppleLog.info("Clue", "CalTopo clue published id=\(id) marker=\(markerID)")
                    break
                } catch {
                    guard !Task.isCancelled, generation == self.configurationGeneration else { break }
                    let attempts = self.records.first(where: { $0.id == id })?.uploadAttempts ?? 1
                    self.mutate(id) { value in
                        value.uploadState = .failed
                        value.lastUploadError = error.localizedDescription
                    }
                    try? self.persistIndex()
                    self.updateStatus()
                    if case let CaltopoLiveClientError.httpStatus(code, _) = error,
                       [400, 401, 403, 404, 413, 422].contains(code) { break }
                    let delay = [2.0, 5.0, 15.0, 30.0, 60.0][min(max(attempts - 1, 0), 4)]
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
                try await client.fetchMapArtifacts()
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

    private func loadIndex() {
        let directories = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        records = directories.flatMap { directory -> [OperationalClueRecord] in
            guard AppleFlightStorage.date(directory.lastPathComponent) != nil,
                  let data = try? Data(contentsOf: directory.appendingPathComponent("clues.json")),
                  let loaded = try? JSONDecoder().decode([OperationalClueRecord].self, from: data) else { return [] }
            return loaded
        }.filter { FileManager.default.fileExists(atPath: imageURL(for: $0).path) }
            .sorted { $0.capturedAt > $1.capturedAt }
        updateStatus()
    }

    func pruneDeletedStorage() {
        for record in records where !FileManager.default.fileExists(atPath: imageURL(for: record).path) {
            uploadTasks.removeValue(forKey: record.id)?.cancel()
        }
        records.removeAll { !FileManager.default.fileExists(atPath: imageURL(for: $0).path) }
        updateStatus()
    }

    private func persistIndex() throws {
        pruneDeletedStorage()
        let grouped = Dictionary(grouping: records) { $0.imageFilename.components(separatedBy: "/")[0] }
        let directories = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        for directory in directories where AppleFlightStorage.date(directory.lastPathComponent) != nil {
            let index = directory.appendingPathComponent("clues.json")
            let dayRecords = grouped[directory.lastPathComponent] ?? []
            guard !dayRecords.isEmpty || FileManager.default.fileExists(atPath: index.path) else { continue }
            do {
                try JSONEncoder().encode(dayRecords).write(to: index, options: .atomic)
            } catch {
                // Retention may have removed this older day since enumeration.
                if FileManager.default.fileExists(atPath: directory.path) { throw error }
            }
        }
    }

    private func updateStatus() {
        let pending = records.filter { $0.uploadState == .pending || $0.uploadState == .uploading || $0.uploadState == .failed }.count
        status = records.isEmpty ? "No local clues" : "\(records.count) local clues\(pending > 0 ? "; \(pending) awaiting CalTopo" : "")"
    }

    private static func thumbnailJPEG(from data: Data) -> Data? {
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
