import Foundation

/// Shared recording admission for direct observations and periodic video positions.
/// Suppressed samples must not advance the anchor; a new track has no prior sample.
public enum WaypointRecordingCadence {
    public static func accepts(previous: Date?, candidate: Date) -> Bool {
        guard let previous else { return true }
        return candidate.timeIntervalSince(previous) >= 1
    }
}
