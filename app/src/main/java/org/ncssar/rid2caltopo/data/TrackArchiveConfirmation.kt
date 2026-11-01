package org.ncssar.rid2caltopo.data

/**
 * Durable per-flight CalTopo Shape-archive confirmation.
 *
 * Once a flight track has been confirmed archived (decision published, or an explicit
 * archive id/folder recorded), automatic connect/reconcile/recovery paths must never
 * re-POST it. Map absence for a confirmed archive means remotely deleted — not "needs
 * upload". Only never-confirmed flights may auto-archive. Mirrors [ClueUploadConfirmation].
 */
internal object TrackArchiveConfirmation {
    /** Confirmed when the awaiting-map decision is `published`, or an archive id was stored. */
    fun isConfirmed(
        decision: String,
        archiveConfirmed: Boolean,
        hasArchiveId: Boolean = false,
    ): Boolean {
        if (archiveConfirmed) return true
        if (decision.equals("published", ignoreCase = true)) return true
        return hasArchiveId
    }

    /** Never-confirmed flights may still auto-archive. Confirmed or map-deleted must not. */
    fun mayAutoArchive(confirmed: Boolean, remotelyDeleted: Boolean): Boolean =
        !(confirmed || remotelyDeleted)

    /**
     * Inbox "awaiting publication" offers are review decisions that lack archive confirmation.
     * Bound/queued/local/published rows never belong in that inbox. Live-published flights
     * that were confirmed archived must not reappear even if decision was left as review.
     */
    @Suppress("UNUSED_PARAMETER")
    fun showsInAwaitingPublicationInbox(
        decision: String,
        finished: Boolean,
        archiveConfirmed: Boolean,
        hasArchiveId: Boolean = false,
    ): Boolean {
        if (!decision.equals("review", ignoreCase = true)) return false
        return !isConfirmed(decision, archiveConfirmed, hasArchiveId)
    }
}
