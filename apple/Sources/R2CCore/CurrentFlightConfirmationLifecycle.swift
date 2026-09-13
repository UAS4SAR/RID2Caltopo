import Foundation

public struct CurrentFlightConfirmationReconciliation: Sendable, Equatable {
    public let endedRemoteIDs: Set<String>
    public let candidateRemoteID: String?
}

/// Tracks prompt presentation for the current flight. Caller-supplied confirmations are
/// per flight; ignored decisions remain in force for the app session.
public struct CurrentFlightConfirmationLifecycle: Sendable, Equatable {
    private var activeRemoteIDs: Set<String> = []
    private var promptedRemoteIDs: Set<String> = []

    public init() {}

    /// Standalone tablets must obtain their own local flight confirmation.
    public static func acceptsPeerConfirmation(mapID: String) -> Bool {
        !mapID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public mutating func reconcile(
        orderedRemoteIDs: [String],
        confirmedRemoteIDs: Set<String>,
        ignoredRemoteIDs: Set<String>
    ) -> CurrentFlightConfirmationReconciliation {
        let currentRemoteIDs = Set(orderedRemoteIDs.filter { !$0.isEmpty })
        let endedRemoteIDs = activeRemoteIDs.subtracting(currentRemoteIDs)
        promptedRemoteIDs.subtract(endedRemoteIDs)
        activeRemoteIDs = currentRemoteIDs

        let decisionsAfterFlightEnd = confirmedRemoteIDs
            .subtracting(endedRemoteIDs)
            .union(ignoredRemoteIDs)
        let candidate = orderedRemoteIDs.first { remoteID in
            !remoteID.isEmpty
                && !promptedRemoteIDs.contains(remoteID)
                && !decisionsAfterFlightEnd.contains(remoteID)
        }
        if let candidate {
            promptedRemoteIDs.insert(candidate)
        }
        return CurrentFlightConfirmationReconciliation(
            endedRemoteIDs: endedRemoteIDs,
            candidateRemoteID: candidate
        )
    }

    public mutating func reset() {
        activeRemoteIDs.removeAll()
        promptedRemoteIDs.removeAll()
    }
}
