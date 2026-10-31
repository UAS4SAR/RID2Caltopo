import Foundation

/// Startup integrity for clue photos and indexes. Runs entirely off the UI thread.
public enum ClueTrackDurabilityIntegrity {
    public static let missingPhotoError = "Missing photo"

    public struct Summary: Sendable, Equatable {
        public var orphanPhotosReindexed: Int
        public var missingPhotoFlagged: Int
        public var pendingOrFailedUploads: Int

        public init(
            orphanPhotosReindexed: Int = 0,
            missingPhotoFlagged: Int = 0,
            pendingOrFailedUploads: Int = 0
        ) {
            self.orphanPhotosReindexed = orphanPhotosReindexed
            self.missingPhotoFlagged = missingPhotoFlagged
            self.pendingOrFailedUploads = pendingOrFailedUploads
        }

        public var description: String {
            "clue integrity orphans=\(orphanPhotosReindexed) missingPhoto=\(missingPhotoFlagged) pendingOrFailed=\(pendingOrFailedUploads)"
        }
    }

    /// IDs that should resume upload after reload (pending / failed / interrupted uploading).
    public static func pendingUploadIDs(in records: [OperationalClueRecord]) -> [UUID] {
        records.compactMap { record in
            switch record.uploadState {
            case .pending, .failed, .uploading: return record.id
            case .localOnly, .published: return nil
            }
        }
    }

    public static func pendingUploadCount(in records: [OperationalClueRecord]) -> Int {
        pendingUploadIDs(in: records).count
    }

    /// Permanent 4xx from CalTopo stops retries but must keep the record as failed.
    public static func isPermanentClientFailure(statusCode: Int) -> Bool {
        [400, 401, 403, 404, 413, 422].contains(statusCode)
    }

    /// Media probe 400/404 means absent, not a store-level permanent stop.
    public static func isMediaAbsentProbe(statusCode: Int) -> Bool {
        statusCode == 400 || statusCode == 404
    }

    /// When the image file is gone, keep the index row and flag it — never drop.
    public static func flagMissingPhotos(
        in records: [OperationalClueRecord],
        missingIDs: Set<UUID>
    ) -> (records: [OperationalClueRecord], flagged: Int) {
        guard !missingIDs.isEmpty else { return (records, 0) }
        var flagged = 0
        let next = records.map { record -> OperationalClueRecord in
            guard missingIDs.contains(record.id) else { return record }
            var updated = record
            if updated.uploadState != .failed || updated.lastUploadError != missingPhotoError {
                flagged += 1
            }
            updated.uploadState = .failed
            updated.lastUploadError = missingPhotoError
            return updated
        }
        return (next, flagged)
    }

    /// Scan FlightStorage: re-index orphan JPEGs; flag index rows whose photo is missing.
    public static func scan(root: URL, records: [OperationalClueRecord]) throws -> (
        records: [OperationalClueRecord],
        summary: Summary
    ) {
        let directories: [URL]
        do {
            directories = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey])
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            return (records, Summary())
        }

        var next = records
        var indexedFilenames = Set(records.map(\.imageFilename))
        var orphanPhotosReindexed = 0
        var missingIDs: Set<UUID> = []

        for record in records {
            let imageURL = root.appendingPathComponent(record.imageFilename)
            do {
                let values = try imageURL.resourceValues(forKeys: [.isRegularFileKey])
                if values.isRegularFile != true { missingIDs.insert(record.id) }
            } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
                missingIDs.insert(record.id)
            }
        }

        let flagged = flagMissingPhotos(in: next, missingIDs: missingIDs)
        next = flagged.records

        for directory in directories where AppleFlightStorage.date(directory.lastPathComponent) != nil {
            let day = directory.lastPathComponent
            let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
            for fileURL in files where fileURL.pathExtension.lowercased() == "jpg"
                && !fileURL.lastPathComponent.hasSuffix("-thumb.jpg")
            {
                let relative = "\(day)/\(fileURL.lastPathComponent)"
                guard !indexedFilenames.contains(relative) else { continue }
                let stem = fileURL.deletingPathExtension().lastPathComponent
                guard let id = UUID(uuidString: stem) else { continue }
                let thumbName = "\(day)/\(id.uuidString.lowercased())-thumb.jpg"
                let recovered = OperationalClueRecord(
                    id: id,
                    capturedAt: (try? fileURL.resourceValues(forKeys: [.contentModificationDateKey])
                        .contentModificationDate) ?? Date(),
                    aircraftID: "unknown",
                    designator: "Recovered",
                    droneLatitude: 0,
                    droneLongitude: 0,
                    droneAltitudeMeters: nil,
                    clueLatitude: 0,
                    clueLongitude: 0,
                    clueAltitudeMeters: nil,
                    headingDegrees: nil,
                    aglMeters: nil,
                    atoMeters: nil,
                    gimbalAngleDegrees: 0,
                    title: "Recovered photo",
                    clueDescription: "Re-indexed orphan photo at startup",
                    imageFilename: relative,
                    thumbnailFilename: thumbName,
                    uploadState: .localOnly,
                    lastUploadError: "Orphan photo re-indexed"
                )
                next.insert(recovered, at: 0)
                indexedFilenames.insert(relative)
                orphanPhotosReindexed += 1
            }
        }

        next.sort { $0.capturedAt > $1.capturedAt }
        let summary = Summary(
            orphanPhotosReindexed: orphanPhotosReindexed,
            missingPhotoFlagged: flagged.flagged,
            pendingOrFailedUploads: pendingUploadCount(in: next)
        )
        return (next, summary)
    }
}
