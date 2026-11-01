import Foundation

/// Owner/wake bookkeeping for pending clue uploads (parity with Android ClueUploadWakeGate).
///
/// A live owner with no per-clue upload ids is not "upload-in-flight". Submit sets a wake
/// flag; the active owner observes it instead of logging an empty-id skip.
public enum ClueUploadWakeGate: Sendable {
    public struct Decision: Sendable, Equatable {
        public let startOwner: Bool
        /// Non-nil when the caller should log an in-flight skip; never an empty collection.
        public let skipLogIds: [String]?

        public init(startOwner: Bool, skipLogIds: [String]? = nil) {
            self.startOwner = startOwner
            self.skipLogIds = skipLogIds
        }
    }

    public static func decide(ownerActive: Bool, inFlightIds: [String]) -> Decision {
        if !ownerActive { return Decision(startOwner: true) }
        let ids = inFlightIds.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        if ids.isEmpty {
            return Decision(startOwner: false, skipLogIds: nil)
        }
        return Decision(startOwner: false, skipLogIds: ids)
    }
}
