import Foundation

/// What a restart does with an unfinished journal for a flight that had already
/// started on the map. The flight file uses every journal point. The CalTopo
/// shape keeps this flight's id. A different flight's shape is not a target.
public enum RestartFlightRecovery {
    public struct Decision: Equatable, Sendable {
        /// Coordinates written to the flight file and to this flight's shape.
        public let filePointCount: Int
        /// One Tracker upload, and only for this journal's own verified Mine.
        public let uploadOnce: Bool
        /// Shape id to create or update. Always this flight's live-track id.
        public let shapeID: String
        /// Shape ids on the map that belong to other flights and must be left alone.
        public let untouchedShapeIDs: [String]
        /// Pilot stored on the journal. Empty when the journal did not record one.
        public let pilot: String
        /// Unverified Mine (or no verified Mine) stays in review and is not uploaded.
        public let heldForReview: Bool
        /// The journal is removed only after the flight file write succeeds.
        public let retireJournalAfterFileWrite: Bool
        /// Recovery posts this flight's shape and does not delete that id.
        /// A LiveTrack this tablet just created is stopped on the normal finish path,
        /// which deletes the LiveTrack class after the Shape conversion. That delete
        /// is a 400 once the object is a Shape, so the shape stays. Restart recovery
        /// of an id that is already a Shape must not send it.
        public let deleteLiveTrack: Bool
        /// Recovery and its cleanup do not delete clue photos or clue records.
        public let deleteClues: Bool
    }

    /// A flight started on the map when its awaiting-map row says publication started
    /// or an interrupted-publication record exists. A `finished` flag is not that fact:
    /// the first load marks every saved row finished, and that must not retire a journal
    /// before the flight file is written.
    public static func flightAlreadyStarted(publicationStarted: Bool, interruptedRecordPresent: Bool) -> Bool {
        publicationStarted || interruptedRecordPresent
    }

    public static func decide(
        liveTrackID: String,
        journalPointCount: Int,
        interruptedPointCount: Int,
        existingShapeIDs: [String],
        ownership: FlightOwnershipContext,
        startedOnMap: Bool
    ) -> Decision? {
        guard startedOnMap, journalPointCount > 0 else { return nil }
        let id = liveTrackID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }
        _ = interruptedPointCount
        let upload = ownership.mayAutoPublish && !ownership.requiresHold
        return Decision(
            filePointCount: journalPointCount,
            uploadOnce: upload,
            shapeID: id,
            untouchedShapeIDs: existingShapeIDs.filter { $0 != id },
            pilot: ownership.recordedPilot,
            heldForReview: !upload,
            retireJournalAfterFileWrite: true,
            deleteLiveTrack: false,
            deleteClues: false
        )
    }

    /// Runs one restarted flight: read the awaiting-map row and the interrupted record,
    /// write every journal point, fsync and read the file back, then upload once and
    /// post that shape. The journal is removed only after the file verifies. A failed
    /// write leaves it. This does not delete the shape id, a clue photo, or another
    /// flight's shape, and it does not take the Tracker lease.
    public static func replay(
        journalURL: URL,
        awaitingURL: URL,
        interruptedURL: URL,
        flightFileURL: URL,
        cluePhotoURL: URL,
        existingShapeIDs: [String] = [],
        upload: () -> Void,
        postShape: (_ id: String, _ pointCount: Int) -> Void,
        deleteLiveTrack: (_ id: String) -> Void
    ) async throws -> Decision {
        let clueBefore = try Data(contentsOf: cluePhotoURL)
        guard let flight = try WaypointTrackAppendJournal.read(fileURL: journalURL) else {
            throw OperationalSafeFileWriterError.verificationFailed(journalURL.lastPathComponent)
        }
        let stored = try AwaitingMapFlightJournal.readSavedEntries(fileURL: awaitingURL)
        let row = stored.last { $0.id == flight.id }
        let interrupted = await CaltopoInterruptedPublicationJournal(fileURL: interruptedURL)
            .entry(liveTrackID: flight.id)
        let started = flightAlreadyStarted(
            publicationStarted: row?.publicationStarted == true,
            interruptedRecordPresent: interrupted != nil)
        guard let plan = decide(
            liveTrackID: flight.id,
            journalPointCount: flight.observations.count,
            interruptedPointCount: interrupted?.points.count ?? 0,
            existingShapeIDs: existingShapeIDs,
            ownership: flight.ownership,
            startedOnMap: started
        ) else {
            throw OperationalSafeFileWriterError.verificationFailed(flight.id)
        }
        let data = try RidTrackGeoJSON.encodeRecovered(
            remoteID: flight.remoteID,
            observations: flight.observations,
            title: flight.label,
            metadata: RidTrackArchiveMetadata(
                mappedID: flight.ownership.mappedID,
                owner: plan.pilot,
                model: flight.ownership.model,
                organization: flight.ownership.organization,
                mapID: flight.mapID,
                localArchiveOnly: !plan.uploadOnce,
                flightID: flight.id,
                trackerUploadAuthorized: plan.uploadOnce,
                trackerClaimPending: plan.heldForReview
            )
        )
        try RecoveredFlightFile.writeVerified(data, to: flightFileURL, expectedPoints: plan.filePointCount)
        if plan.uploadOnce { upload() }
        postShape(plan.shapeID, plan.filePointCount)
        if plan.deleteLiveTrack { deleteLiveTrack(plan.shapeID) }
        guard try Data(contentsOf: cluePhotoURL) == clueBefore else {
            throw OperationalSafeFileWriterError.verificationFailed(cluePhotoURL.lastPathComponent)
        }
        if plan.retireJournalAfterFileWrite {
            try FileManager.default.removeItem(at: journalURL)
        }
        return plan
    }
}

/// Crash-safe flight file for a recovered journal. The write is fsynced and read back,
/// and it fails unless the file contains every journal point.
public enum RecoveredFlightFile {
    public static func coordinateCount(_ data: Data) -> Int? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let feature = (root["features"] as? [[String: Any]])?.first,
              let geometry = feature["geometry"] as? [String: Any],
              let coordinates = geometry["coordinates"] as? [Any] else { return nil }
        return coordinates.count
    }

    public static func writeVerified(_ data: Data, to url: URL, expectedPoints: Int) throws {
        guard expectedPoints > 0, coordinateCount(data) == expectedPoints else {
            throw OperationalSafeFileWriterError.verificationFailed(url.lastPathComponent)
        }
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try OperationalSafeFileWriter.replace(url, with: data) { coordinateCount($0) == expectedPoints }
        let readBack = try Data(contentsOf: url)
        guard coordinateCount(readBack) == expectedPoints else {
            throw OperationalSafeFileWriterError.verificationFailed(url.lastPathComponent)
        }
    }
}
