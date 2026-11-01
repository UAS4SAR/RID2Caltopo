import Foundation

/// Durable per-clue upload confirmation.
///
/// Once a clue has been confirmed uploaded (published state, parts-reconciled flag, or a
/// legacy success marker id), automatic connect/reconcile/reverify paths must never re-POST it.
/// Map absence for a confirmed clue means remotely deleted — not "needs upload".
/// Only never-confirmed pending clues may auto-upload.
public enum ClueUploadConfirmation: Sendable {
    public enum ProbeDecision: String, Sendable, Equatable {
        /// Marker + media + link all present — backfill reconciled; do not POST.
        case confirmReconciled
        /// Marker missing from the incident map — treat as map-deleted; do not POST.
        case markRemotelyDeleted
        /// Confirmed but incomplete while marker still on the map — leave alone; do not POST.
        case leaveAlone
        /// Never confirmed and nothing on the map — caller may auto-upload.
        case mayAutoUpload
        /// Never confirmed with partial presence — caller may complete missing parts.
        case repairPartial
    }

    /// Confirmed when published or parts were reconciled. Legacy rows uploaded before the
    /// reconciled flag existed still count via `uploadState == .published`. Explicit success
    /// marker ids may confirm non-pending legacy rows from older builds.
    public static func isConfirmed(
        uploadState: OperationalClueUploadState,
        partsReconciled: Bool,
        hasLegacyCaltopoIds: Bool = false
    ) -> Bool {
        if partsReconciled { return true }
        if uploadState == .published { return true }
        guard hasLegacyCaltopoIds else { return false }
        switch uploadState {
        case .pending, .uploading, .failed:
            return false
        case .published, .localOnly:
            return true
        }
    }

    /// Pending/failed/uploading rows that were never confirmed may still auto-upload.
    public static func mayAutoUpload(
        uploadState: OperationalClueUploadState,
        confirmed: Bool,
        remotelyDeleted: Bool
    ) -> Bool {
        if confirmed || remotelyDeleted { return false }
        switch uploadState {
        case .pending, .failed, .uploading:
            return true
        case .published, .localOnly:
            return false
        }
    }

    /// Probe outcome for reconnect / re-verify. Marker absence is map deletion even when media
    /// still exists in the personal media library (that used to look like partial presence).
    public static func decideProbe(
        confirmed: Bool,
        markerPresent: Bool,
        mediaPresent: Bool,
        linkPresent: Bool
    ) -> ProbeDecision {
        let allPresent = markerPresent && mediaPresent && linkPresent
        if confirmed {
            if allPresent { return .confirmReconciled }
            if !markerPresent { return .markRemotelyDeleted }
            return .leaveAlone
        }
        if allPresent { return .confirmReconciled }
        if markerPresent || mediaPresent || linkPresent { return .repairPartial }
        return .mayAutoUpload
    }
}
