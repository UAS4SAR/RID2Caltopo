import Foundation
import Testing
@testable import R2CCore

struct FlightFolderProtectionTests {
    @Test func reasonsMatchAndroidWordingAndPriority() {
        #expect(FlightFolderProtection.evaluate(name: "2026-10-04", today: "2026-10-04", owners: ["diagnostic-log": "2026-10-04"],
                                                incompleteRecording: true, clueStates: [.uploading])?.label == "today")
        #expect(FlightFolderProtection.evaluate(name: "2026-10-03", today: "2026-10-04", owners: [:],
                                                incompleteRecording: true, clueStates: [])?.label == "recording in progress")
        #expect(FlightFolderProtection.evaluate(name: "2026-10-03", today: "2026-10-04", owners: ["video-review": "2026-10-03"],
                                                incompleteRecording: false, clueStates: [])?.label == "in use by video review")
        #expect(FlightFolderProtection.evaluate(name: "2026-10-03", today: "2026-10-04", owners: [:],
                                                incompleteRecording: false, clueStates: [.published, .uploading])?.label == "clue upload in progress")
    }

    @Test func pendingAndFailedCluesDoNotBlockManualDelete() {
        #expect(FlightFolderProtection.evaluate(name: "2026-10-01", today: "2026-10-04", owners: [:],
                                                incompleteRecording: false, clueStates: [.failed, .pending, .failed]) == nil)
        #expect(FlightFolderProtection.unuploadedClueCount([.failed, .pending, .published, .uploading, .localOnly]) == 3)
    }

    @Test func automaticCleanupKeepsQueuedButNotFailedClues() {
        #expect(FlightFolderProtection.blocksAutomaticCleanup(clueStates: [.pending]))
        #expect(FlightFolderProtection.blocksAutomaticCleanup(clueStates: [.uploading]))
        #expect(!FlightFolderProtection.blocksAutomaticCleanup(clueStates: [.failed, .published, .localOnly]))
    }

    @Test func ownerLabelsAreReadable() {
        #expect(FlightFolderProtection.ownerLabel("diagnostic-log") == "diagnostic log")
        #expect(FlightFolderProtection.ownerLabel("video-review") == "video review")
        #expect(FlightFolderProtection.ownerLabel("upload-1234") == "archive upload")
        #expect(FlightFolderProtection.ownerLabel("archive-upload-1234") == "archive upload")
        #expect(FlightFolderProtection.ownerLabel("finalize-1234") == "recording finalization")
        #expect(FlightFolderProtection.ownerLabel("filesystem-test") == "filesystem test")
    }

    @Test func releasedOwnerNoLongerProtects() {
        AppleFlightStorage.protect("2019-03-03", owner: "video-review")
        #expect(AppleFlightStorage.protectionReason("2019-03-03")?.label == "in use by video review")
        AppleFlightStorage.release(owner: "video-review")
        #expect(AppleFlightStorage.protectionReason("2019-03-03") == nil)
        // Re-protecting the same owner moves the protection (diagnostic-log rollover).
        AppleFlightStorage.protect("2019-03-03", owner: "diagnostic-log-test")
        AppleFlightStorage.protect("2019-03-04", owner: "diagnostic-log-test")
        #expect(AppleFlightStorage.protectionReason("2019-03-03") == nil)
        #expect(AppleFlightStorage.protectionReason("2019-03-04") != nil)
        AppleFlightStorage.release(owner: "diagnostic-log-test")
    }

    @Test func folderDetailAndDeleteWarningText() {
        #expect(ArchiveFolderDisplay.detail(age: "3 days", size: "1.0 GB", protectionReason: "in use by video review", unuploadedClueCount: 0)
                == "Age 3 days • 1.0 GB • protected: in use by video review")
        #expect(ArchiveFolderDisplay.detail(age: "3 days", size: "1.0 GB", protectionReason: nil, unuploadedClueCount: 2)
                == "Age 3 days • 1.0 GB • 2 clues not uploaded")
        #expect(ArchiveFolderDisplay.deleteConfirmation(folderCount: 2, sizeLabel: "2.0 GB", unuploadedClueCount: 1)
                == "Permanently delete 2 archive folders totaling 2.0 GB? 1 clue was never uploaded to CalTopo and will be lost.")
        #expect(ArchiveFolderDisplay.deleteConfirmation(folderCount: 1, sizeLabel: "5 MB", unuploadedClueCount: 0)
                == "Permanently delete 1 archive folder totaling 5 MB?")
    }

    @Test func automaticCleanupDeletesFailedClueDaysButKeepsPendingOnes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        func clue(_ state: OperationalClueUploadState) -> OperationalClueRecord {
            OperationalClueRecord(capturedAt: Date(timeIntervalSince1970: 0), aircraftID: "A", designator: "D",
                droneLatitude: 39, droneLongitude: -121, droneAltitudeMeters: nil, clueLatitude: 39, clueLongitude: -121,
                clueAltitudeMeters: nil, headingDegrees: nil, aglMeters: nil, atoMeters: nil, gimbalAngleDegrees: -90,
                title: "t", clueDescription: "d", imageFilename: "i.jpg", thumbnailFilename: "t.jpg", uploadState: state)
        }
        for (day, state) in [("2019-05-01", OperationalClueUploadState.failed), ("2019-05-02", .pending)] {
            let folder = root.appendingPathComponent(day)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try JSONEncoder().encode([clue(state)]).write(to: folder.appendingPathComponent("clues.json"))
            try Data(repeating: 1, count: 100).write(to: folder.appendingPathComponent("i.jpg"))
        }
        #expect(AppleFlightStorage.protectionReason("2019-05-01", at: root) == nil)
        #expect(AppleFlightStorage.protectionReason("2019-05-02", at: root) == nil)
        #expect(AppleFlightStorage.unuploadedClueCount("2019-05-01", at: root) == 1)
        _ = AppleFlightStorage.maintain(at: root, maximum: 10_000, days: 30)
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("2019-05-01").path))
        #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("2019-05-02").path))
    }
}
