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
            retireJournalAfterFileWrite: true
        )
    }
}
