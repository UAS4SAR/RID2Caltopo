import Foundation

/// Durable per-flight CalTopo Shape-archive confirmation.
///
/// Once a flight track has been confirmed archived (decision published, or an explicit
/// archive id/folder recorded), automatic connect/reconcile/recovery paths must never
/// re-POST it. Map absence for a confirmed archive means remotely deleted — not "needs
/// upload". Only never-confirmed flights may auto-archive. Mirrors `ClueUploadConfirmation`.
public enum TrackArchiveConfirmation: Sendable {
    /// Confirmed when the awaiting-map decision is `published`, or an archive id was stored.
    public static func isConfirmed(
        decision: String,
        archiveConfirmed: Bool,
        hasArchiveId: Bool = false
    ) -> Bool {
        if archiveConfirmed { return true }
        if decision == "published" { return true }
        return hasArchiveId
    }

    /// Never-confirmed flights may still auto-archive. Confirmed or map-deleted must not.
    public static func mayAutoArchive(
        confirmed: Bool,
        remotelyDeleted: Bool
    ) -> Bool {
        !(confirmed || remotelyDeleted)
    }

    /// Inbox "awaiting publication" offers are review decisions that lack archive confirmation.
    /// Bound/queued/local/published rows never belong in that inbox. Live-published flights
    /// that were confirmed archived must not reappear even if decision was left as review.
    /// Ledger-declined / suppressed flights are excluded even if a stale review row remains.
    public static func showsInAwaitingPublicationInbox(
        decision: String,
        finished: Bool,
        archiveConfirmed: Bool,
        hasArchiveId: Bool = false,
        ledgerBlocksPromotion: Bool = false
    ) -> Bool {
        _ = finished
        if ledgerBlocksPromotion { return false }
        guard decision == "review" else { return false }
        return !isConfirmed(
            decision: decision,
            archiveConfirmed: archiveConfirmed,
            hasArchiveId: hasArchiveId
        )
    }
}
