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
        #expect(ClueTrackDurabilityIntegrity.isPermanentClientFailure(statusCode: 400))
        #expect(ClueTrackDurabilityIntegrity.isPermanentClientFailure(statusCode: 404))
        #expect(!ClueTrackDurabilityIntegrity.isPermanentClientFailure(statusCode: 502))
        #expect(!ClueTrackDurabilityIntegrity.isPermanentClientFailure(statusCode: 503))
        #expect(ClueTrackDurabilityIntegrity.isMediaAbsentProbe(statusCode: 400))
        #expect(ClueTrackDurabilityIntegrity.isMediaAbsentProbe(statusCode: 404))
        // Store-level permanent stop must not fire for media-absent probes (handled in publishPhotoClue).
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
}
