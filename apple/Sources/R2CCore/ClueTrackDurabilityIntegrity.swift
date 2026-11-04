import Foundation

/// Startup integrity for clue photos and indexes. Runs entirely off the UI thread.
public enum ClueTrackDurabilityIntegrity {
    public static let missingPhotoError = CluePhotoUploadPolicy.photoMissingMessage

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
    /// Confirmed uploads, map-deleted clues, and photo-missing / needs-attention stops are
    /// excluded — never auto re-POST; the operator Retry / Upload-as-new path wakes those.
    public static func pendingUploadIDs(in records: [OperationalClueRecord]) -> [UUID] {
        records.compactMap { record in
            if CluePhotoUploadPolicy.stopsAutoRetry(record.lastUploadError) { return nil }
            if record.caltopoRemotelyDeleted == true { return nil }
            guard ClueUploadConfirmation.mayAutoUpload(
                uploadState: record.uploadState,
                confirmed: record.uploadConfirmed,
                remotelyDeleted: record.caltopoRemotelyDeleted == true
            ) else { return nil }
            return record.id
        }
    }

    /// Publish-intended but unconfirmed clues for the pending chip/list across every map.
    /// Includes photo-missing / needs-attention failed rows; excludes Don't Publish / localOnly.
    public static func pendingListRecords(in records: [OperationalClueRecord]) -> [OperationalClueRecord] {
        records
            .filter {
                ClueUploadConfirmation.mayAutoUpload(
                    uploadState: $0.uploadState,
                    confirmed: $0.uploadConfirmed,
                    remotelyDeleted: $0.caltopoRemotelyDeleted == true
                )
            }
            .sorted { $0.capturedAt > $1.capturedAt }
    }

    /// Chip/list count — aligned with `pendingListRecords` (includes photo-missing).
    public static func pendingUploadCount(in records: [OperationalClueRecord]) -> Int {
        pendingListRecords(in: records).count
    }

    /// Map label for a pending row: each clue's own saved title, else its map id.
    public static func pendingRowMapName(
        destinationMapTitle: String?,
        destinationMapID: String?
    ) -> String {
        let title = (destinationMapTitle ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title }
        return (destinationMapID ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// HTTP 4xx that historically looked "permanent". Callers must reconcile map state
    /// before treating a clue as done; never drop durable retry solely on these codes.
    public static func isClientFailureNeedingReconcile(statusCode: Int) -> Bool {
        [400, 401, 403, 404, 413, 422].contains(statusCode)
    }

    @available(*, deprecated, renamed: "isClientFailureNeedingReconcile")
    public static func isPermanentClientFailure(statusCode: Int) -> Bool {
        isClientFailureNeedingReconcile(statusCode: statusCode)
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

        // Day folders: legacy day-level photos plus each flight folder `<day>/<flightId>/`.
        for directory in directories where AppleFlightStorage.date(directory.lastPathComponent) != nil {
            let day = directory.lastPathComponent
            var scanned: [(url: URL, prefix: String, flightFolder: String?)] = [(directory, day, nil)]
            let children = (try? FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
            for child in children.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
            where !child.lastPathComponent.hasPrefix(".") && AppleClueFlightFolder.isDirectory(child) {
                scanned.append((child, "\(day)/\(child.lastPathComponent)", child.lastPathComponent))
            }
            for (folder, prefix, flightFolder) in scanned {
                let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
                for fileURL in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
                where fileURL.pathExtension.lowercased() == "jpg" && !fileURL.lastPathComponent.hasSuffix("-thumb.jpg") {
                    let relative = "\(prefix)/\(fileURL.lastPathComponent)"
                    guard !indexedFilenames.contains(relative) else { continue }
                    let stem = fileURL.deletingPathExtension().lastPathComponent
                    guard let id = UUID(uuidString: stem) else { continue }
                    let capturedAt = (try? fileURL.resourceValues(forKeys: [.contentModificationDateKey])
                        .contentModificationDate) ?? Date()
                    next.insert(
                        recoveredOrphanRecord(
                            id: id, capturedAt: capturedAt, prefix: prefix, flightID: flightFolder,
                            lastUploadError: "Orphan photo re-indexed"
                        ),
                        at: 0
                    )
                    indexedFilenames.insert(relative)
                    orphanPhotosReindexed += 1
                }
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

    /// Index row for a JPEG that has no row. Never dropped; local-only so it is not auto-published.
    /// `prefix` is the relative directory (`<day>` or `<day>/<flightId>`); a flight folder name also
    /// becomes the row's binding flight so the clue stays tied to its flight.
    public static func recoveredOrphanRecord(
        id: UUID,
        capturedAt: Date,
        prefix: String,
        flightID: String?,
        lastUploadError: String
    ) -> OperationalClueRecord {
        let name = id.uuidString.lowercased()
        let milliseconds = Int64((capturedAt.timeIntervalSince1970 * 1_000).rounded())
        let binding = flightID.map {
            ClueBinding(
                aircraftID: "unknown", flightID: $0, flightStartMs: nil, captureTimeMs: milliseconds,
                captureTimeSource: "app-receive", captureReceivedAtMs: milliseconds, waypoint: nil,
                offsetMs: nil, nearestOffsetMs: nil, quality: .unbound, hovering: false,
                originIsWaypoint: false, framePosition: nil
            )
        }
        return OperationalClueRecord(
            id: id,
            capturedAt: capturedAt,
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
            imageFilename: "\(prefix)/\(name).jpg",
            thumbnailFilename: "\(prefix)/\(name)-thumb.jpg",
            uploadState: .localOnly,
            lastUploadError: lastUploadError,
            binding: binding
        )
    }
}
