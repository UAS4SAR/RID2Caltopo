import Foundation

/// UI / in-memory clue index must not flash empty or shrink while storage prepare is
/// renaming or merging the durable clues tree. Keep the last good observation until
/// prepare finishes, then accept the next full read.
public enum ClueIndexObservation: Sendable {
    public static func shouldPublish(
        previousCount: Int,
        newCount: Int,
        prepareInProgress: Bool
    ) -> Bool {
        if !prepareInProgress { return true }
        if previousCount <= 0 { return true }
        return newCount >= previousCount
    }
}
