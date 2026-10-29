import Foundation

/// Capture before flight-end observers retire a session-only aircraft identity.
/// Initial and delayed recording lookups must use the same completed flight.
public struct CaltopoRecordingAssociation: Sendable {
    public let designators: [String]
    public let startedAt: Date
    public let endedAt: Date

    public init(track: RidAircraftTrack, identity: RidAircraftIdentity?) {
        designators = [identity?.mappedID ?? "", track.aircraftID].filter { !$0.isEmpty }
        startedAt = track.points.first?.receivedAt ?? track.lastObservation.receivedAt
        endedAt = track.points.last?.receivedAt ?? track.lastObservation.receivedAt
    }
}

public enum CaltopoArchiveDescription {
    public static func build(capturedVideoURL: URL?) -> String {
        guard let capturedVideoURL else { return "" }
        return capturedVideoURL.absoluteString
    }
}
