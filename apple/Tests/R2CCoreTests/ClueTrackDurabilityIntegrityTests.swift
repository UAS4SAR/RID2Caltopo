import Foundation
import Testing
@testable import R2CCore

@Suite struct ClueTrackDurabilityIntegrityTests {
    private func sampleRecord(
        id: UUID = UUID(),
        imageFilename: String,
        uploadState: OperationalClueUploadState = .pending
    ) -> OperationalClueRecord {
        OperationalClueRecord(
            id: id,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000),
            aircraftID: "RID-1",
            designator: "ALPHA",
            droneLatitude: 39.1,
            droneLongitude: -121.1,
            droneAltitudeMeters: 100,
            clueLatitude: 39.11,
            clueLongitude: -121.11,
            clueAltitudeMeters: nil,
            headingDegrees: nil,
            aglMeters: nil,
            atoMeters: nil,
            gimbalAngleDegrees: -45,
            title: "Clue",
            clueDescription: "",
            imageFilename: imageFilename,
            thumbnailFilename: imageFilename.replacingOccurrences(of: ".jpg", with: "-thumb.jpg"),
            uploadState: uploadState
        )
    }

    @Test func pruneKeepsMetadataWhenPhotoMissing() {
        let id = UUID()
        let records = [sampleRecord(id: id, imageFilename: "2026-10-30/\(id.uuidString.lowercased()).jpg")]
        let flagged = ClueTrackDurabilityIntegrity.flagMissingPhotos(in: records, missingIDs: [id])
        #expect(flagged.records.count == 1)
        #expect(flagged.records[0].id == id)
        #expect(flagged.records[0].uploadState == .failed)
        #expect(flagged.records[0].lastUploadError == ClueTrackDurabilityIntegrity.missingPhotoError)
        #expect(flagged.flagged == 1)
    }

    @Test func networkFailuresKeepPendingOrFailedDisposition() {
        #expect(ClueTrackDurabilityIntegrity.isClientFailureNeedingReconcile(statusCode: 400))
        #expect(ClueTrackDurabilityIntegrity.isClientFailureNeedingReconcile(statusCode: 404))
        #expect(!ClueTrackDurabilityIntegrity.isClientFailureNeedingReconcile(statusCode: 502))
        #expect(!ClueTrackDurabilityIntegrity.isClientFailureNeedingReconcile(statusCode: 503))
        #expect(ClueTrackDurabilityIntegrity.isMediaAbsentProbe(statusCode: 400))
        #expect(ClueTrackDurabilityIntegrity.isMediaAbsentProbe(statusCode: 404))
        // 4xx requires map reconciliation; durable pending/failed IDs still resume.
        let pending = sampleRecord(imageFilename: "day/a.jpg", uploadState: .pending)
        let failed = sampleRecord(imageFilename: "day/b.jpg", uploadState: .failed)
        let published = sampleRecord(imageFilename: "day/c.jpg", uploadState: .published)
        let ids = ClueTrackDurabilityIntegrity.pendingUploadIDs(in: [pending, failed, published])
        #expect(Set(ids) == Set([pending.id, failed.id]))
    }

    @Test func restartFindsPendingUploadsAfterReload() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("clue-integrity-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let day = AppleFlightStorage.dayName(Date())
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        try FileManager.default.createDirectory(at: dayURL, withIntermediateDirectories: true)
        let id = UUID()
        let imageName = "\(day)/\(id.uuidString.lowercased()).jpg"
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9])
        try jpeg.write(to: root.appendingPathComponent(imageName))
        let record = sampleRecord(id: id, imageFilename: imageName, uploadState: .pending)
        try JSONEncoder().encode([record]).write(to: dayURL.appendingPathComponent("clues.json"))

        let loaded = try OperationalClueIndex.load(root: root)
        #expect(loaded.count == 1)
        let pending = ClueTrackDurabilityIntegrity.pendingUploadIDs(in: loaded)
        #expect(pending == [id])
    }

    @Test func scanReindexesOrphanPhotoAndFlagsMissing() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("clue-integrity-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let day = AppleFlightStorage.dayName(Date())
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        try FileManager.default.createDirectory(at: dayURL, withIntermediateDirectories: true)

        let orphanID = UUID()
        let orphanName = "\(day)/\(orphanID.uuidString.lowercased()).jpg"
        try Data([0xFF, 0xD8, 0xFF, 0xD9]).write(to: root.appendingPathComponent(orphanName))

        let missingID = UUID()
        let missing = sampleRecord(
            id: missingID,
            imageFilename: "\(day)/\(missingID.uuidString.lowercased()).jpg",
            uploadState: .pending
        )
        try JSONEncoder().encode([missing]).write(to: dayURL.appendingPathComponent("clues.json"))

        let loaded = try OperationalClueIndex.load(root: root)
        let result = try ClueTrackDurabilityIntegrity.scan(root: root, records: loaded)
        #expect(result.summary.orphanPhotosReindexed == 1)
        #expect(result.summary.missingPhotoFlagged == 1)
        #expect(result.records.contains { $0.id == orphanID && $0.uploadState == .localOnly })
        let flagged = result.records.first { $0.id == missingID }
        #expect(flagged?.uploadState == .failed)
        #expect(flagged?.lastUploadError == ClueTrackDurabilityIntegrity.missingPhotoError)
        #expect(ClueTrackDurabilityIntegrity.pendingUploadIDs(in: result.records) == [])
    }

    @Test func photoMissingStopsAutoRetryResume() {
        let missing = sampleRecord(
            imageFilename: "day/gone.jpg",
            uploadState: .failed
        )
        var flagged = missing
        flagged.lastUploadError = CluePhotoUploadPolicy.photoMissingMessage
        #expect(ClueTrackDurabilityIntegrity.pendingUploadIDs(in: [flagged]).isEmpty)
        #expect(CluePhotoUploadPolicy.stopsAutoRetry(flagged.lastUploadError))
    }

    @Test func pendingListIncludesPhotoMissingAndExcludesLocalOnly() {
        var photoMissing = sampleRecord(imageFilename: "day/gone.jpg", uploadState: .failed)
        photoMissing.lastUploadError = CluePhotoUploadPolicy.photoMissingMessage
        photoMissing.destinationMapID = "map-a"
        photoMissing.destinationMapTitle = "Alpha Range"
        var pending = sampleRecord(imageFilename: "day/ok.jpg", uploadState: .pending)
        pending.destinationMapID = "map-b"
        pending.destinationMapTitle = "Bravo Sector"
        var localOnly = sampleRecord(imageFilename: "day/local.jpg", uploadState: .localOnly)
        localOnly.destinationMapID = "map-a"
        let published = sampleRecord(imageFilename: "day/done.jpg", uploadState: .published)

        let list = ClueTrackDurabilityIntegrity.pendingListRecords(
            in: [photoMissing, pending, localOnly, published]
        )
        #expect(Set(list.map(\.id)) == Set([photoMissing.id, pending.id]))
        #expect(ClueTrackDurabilityIntegrity.pendingUploadCount(
            in: [photoMissing, pending, localOnly, published]
        ) == 2)
        // Auto-resume still skips photo-missing; chip/list count includes it.
        #expect(ClueTrackDurabilityIntegrity.pendingUploadIDs(
            in: [photoMissing, pending, localOnly, published]
        ) == [pending.id])
        #expect(CluePhotoUploadPolicy.isPhotoMissing(photoMissing.lastUploadError))
        #expect(!CluePhotoUploadPolicy.isPhotoMissing(pending.lastUploadError))
    }

    @Test func pendingRowsUsePerClueMapTitle() {
        #expect(
            ClueTrackDurabilityIntegrity.pendingRowMapName(
                destinationMapTitle: "Alpha Range",
                destinationMapID: "map-a"
            ) == "Alpha Range"
        )
        #expect(
            ClueTrackDurabilityIntegrity.pendingRowMapName(
                destinationMapTitle: "  ",
                destinationMapID: "map-b"
            ) == "map-b"
        )
        #expect(
            ClueTrackDurabilityIntegrity.pendingRowMapName(
                destinationMapTitle: nil,
                destinationMapID: "map-c"
            ) == "map-c"
        )
        var older = sampleRecord(imageFilename: "day/a.jpg", uploadState: .pending)
        older.destinationMapID = "map-older"
        older.destinationMapTitle = "Older Incident"
        var newer = sampleRecord(imageFilename: "day/b.jpg", uploadState: .failed)
        newer.lastUploadError = CluePhotoUploadPolicy.photoMissingMessage
        newer.destinationMapID = "map-newer"
        newer.destinationMapTitle = "Newer Incident"
        let rows = ClueTrackDurabilityIntegrity.pendingListRecords(in: [older, newer])
        #expect(rows.count == 2)
        let names = Set(rows.map {
            ClueTrackDurabilityIntegrity.pendingRowMapName(
                destinationMapTitle: $0.destinationMapTitle,
                destinationMapID: $0.destinationMapID
            )
        })
        #expect(names == Set(["Older Incident", "Newer Incident"]))
    }

    @Test func destinationMapTitleRoundTripsInClueIndex() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("clue-map-title-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let day = AppleFlightStorage.dayName(Date())
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        try FileManager.default.createDirectory(at: dayURL, withIntermediateDirectories: true)
        var record = sampleRecord(
            imageFilename: "\(day)/title.jpg",
            uploadState: .pending
        )
        record.destinationMapID = "map-round"
        record.destinationMapTitle = "Round Trip Map"
        try JSONEncoder().encode([record]).write(to: dayURL.appendingPathComponent("clues.json"))
        let loaded = try OperationalClueIndex.load(root: root)
        #expect(loaded.count == 1)
        #expect(loaded[0].destinationMapID == "map-round")
        #expect(loaded[0].destinationMapTitle == "Round Trip Map")

        // Older indexes without the field decode as nil.
        var legacyRecord = sampleRecord(
            imageFilename: "\(day)/legacy.jpg",
            uploadState: .pending
        )
        legacyRecord.destinationMapID = "map-legacy"
        legacyRecord.destinationMapTitle = "strip-me"
        let encoded = try JSONEncoder().encode([legacyRecord])
        var json = try JSONSerialization.jsonObject(with: encoded) as! [[String: Any]]
        json[0].removeValue(forKey: "destinationMapTitle")
        try JSONSerialization.data(withJSONObject: json)
            .write(to: dayURL.appendingPathComponent("clues.json"))
        let legacy = try OperationalClueIndex.load(root: root)
        #expect(legacy.count == 1)
        #expect(legacy[0].destinationMapID == "map-legacy")
        #expect(legacy[0].destinationMapTitle == nil)
    }

    @Test func discardTombstonePersistsAcrossReload() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("clue-tomb-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let day = AppleFlightStorage.dayName(Date())
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        try FileManager.default.createDirectory(at: dayURL, withIntermediateDirectories: true)
        let keep = sampleRecord(imageFilename: "\(day)/keep.jpg", uploadState: .pending)
        let discarded = sampleRecord(imageFilename: "\(day)/gone.jpg", uploadState: .failed)
        try JSONEncoder().encode([keep, discarded])
            .write(to: dayURL.appendingPathComponent("clues.json"))
        try OperationalClueDeletionTombstone.persist([discarded.id], root: root)

        let deleted = OperationalClueDeletionTombstone.load(root: root)
        #expect(deleted == [discarded.id])
        let loaded = OperationalClueDeletionTombstone.excludingDeleted(
            try OperationalClueIndex.load(root: root),
            deleted: deleted
        )
        #expect(loaded.map(\.id) == [keep.id])
        #expect(ClueTrackDurabilityIntegrity.pendingListRecords(in: loaded).map(\.id) == [keep.id])
    }

    @Test func liveTrackEnqueueNotGatedOnAwaitingMapHistoryInViewModel() throws {
        let appleRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let viewModel = try String(
            contentsOf: appleRoot.appendingPathComponent("App/RIDTrackViewModel.swift"),
            encoding: .utf8
        )
        #expect(viewModel.contains("LiveTrack must publish even when awaiting-map history is corrupt"))
        #expect(!viewModel.contains("guard awaitingMapHistoryReady, awaitingMapHistoryError == nil else { return Task { } }"))
        #expect(viewModel.contains("acknowledgeRecovered(flightID:"))
        #expect(viewModel.contains("Waypoint journal write failed:"))
    }

    @Test func clueLaneSeparatesFromStartLane() throws {
        let appleRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let client = try String(
            contentsOf: appleRoot.appendingPathComponent("Sources/R2CCore/CaltopoLiveClient.swift"),
            encoding: .utf8
        )
        #expect(client.contains("private let clueLane: CaltopoNetworkLane"))
        #expect(client.contains("lane: clueLane"))
        #expect(client.contains("Clue media must not share startLane"))
    }

    @Test func interruptedJournalUsesSafeFileWriter() throws {
        let appleRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let journal = try String(
            contentsOf: appleRoot.appendingPathComponent(
                "Sources/R2CCore/CaltopoInterruptedPublicationJournal.swift"),
            encoding: .utf8
        )
        #expect(journal.contains("OperationalSafeFileWriter.replace"))
    }

    @Test func scanReindexesOrphansAndFlagsMissingInsideFlightFolders() throws {
        typealias Support = ClueFlightFolderTestSupport
        let root = Support.tempRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let day = "2026-10-22"
        let flight = root.appendingPathComponent(day).appendingPathComponent("flight-a")
        let orphan = UUID(), missing = UUID()
        try Support.photo("\(orphan.uuidString.lowercased()).jpg", in: flight)
        try Support.write([Support.record(id: missing, image: "\(day)/flight-a/\(missing.uuidString.lowercased()).jpg",
                                          flightID: "flight-a")], to: flight)
        let loaded = try OperationalClueIndex.load(root: root)
        let result = try ClueTrackDurabilityIntegrity.scan(root: root, records: loaded)
        #expect(result.summary.orphanPhotosReindexed == 1)
        #expect(result.summary.missingPhotoFlagged == 1)
        let stub = try #require(result.records.first { $0.id == orphan })
        #expect(stub.uploadState == .localOnly)
        #expect(stub.imageFilename == "\(day)/flight-a/\(orphan.uuidString.lowercased()).jpg")
        #expect(stub.binding?.flightID == "flight-a")
        // A second scan over the re-indexed rows finds nothing new.
        let again = try ClueTrackDurabilityIntegrity.scan(root: root, records: result.records)
        #expect(again.summary.orphanPhotosReindexed == 0)
    }
}
