package org.ncssar.rid2caltopo.data

/**
 * Durable per-clue upload confirmation.
 *
 * Once a clue has been confirmed uploaded (published state, parts-reconciled flag, or any
 * stored CalTopo id from a prior success), automatic connect/reconcile/reverify paths must
 * never re-POST it. Map absence for a confirmed clue means remotely deleted — not "needs upload".
 * Only never-confirmed pending clues may auto-upload.
 */
internal object ClueUploadConfirmation {
    enum class ProbeDecision {
        /** Marker + media + link all present — backfill reconciled; do not POST. */
        ConfirmReconciled,
        /** Marker missing from the incident map — treat as map-deleted; do not POST. */
        MarkRemotelyDeleted,
        /** Confirmed but incomplete while marker still on the map — leave alone; do not POST. */
        LeaveAlone,
        /** Never confirmed and nothing on the map — caller may auto-upload. */
        MayAutoUpload,
        /** Never confirmed with partial presence — caller may complete missing parts. */
        RepairPartial,
    }

    /**
     * Confirmed when published or parts were reconciled. Legacy rows uploaded before the
     * reconciled flag existed still count via [uploadState]=published. Explicit CalTopo ids
     * alone do not confirm — pending retries mint media/link overrides without a successful
     * publish. Callers may also pass [hasLegacyCaltopoIds] for records that store a success
     * marker id alongside a non-pending state from older builds.
     */
    fun isConfirmed(
        uploadState: String,
        partsReconciled: Boolean,
        hasLegacyCaltopoIds: Boolean = false,
    ): Boolean {
        if (partsReconciled) return true
        if (uploadState.equals("published", ignoreCase = true)) return true
        // Older indexes may retain a success marker id without uploadState=published.
        return hasLegacyCaltopoIds &&
            !uploadState.equals("pending", ignoreCase = true) &&
            !uploadState.equals("uploading", ignoreCase = true) &&
            !uploadState.equals("failed", ignoreCase = true)
    }

    /** Pending/failed/uploading rows that were never confirmed may still auto-upload. */
    fun mayAutoUpload(
        uploadState: String,
        confirmed: Boolean,
        remotelyDeleted: Boolean,
    ): Boolean {
        if (confirmed || remotelyDeleted) return false
        return uploadState == "pending" || uploadState == "failed" || uploadState == "uploading"
    }

    /**
     * Probe outcome for reconnect / re-verify. Marker absence is map deletion even when media
     * still exists in the personal media library (that used to look like HTTP 409 "partial").
     */
    fun decideProbe(
        confirmed: Boolean,
        markerPresent: Boolean,
        mediaPresent: Boolean,
        linkPresent: Boolean,
    ): ProbeDecision {
        val allPresent = markerPresent && mediaPresent && linkPresent
        if (confirmed) {
            if (allPresent) return ProbeDecision.ConfirmReconciled
            if (!markerPresent) return ProbeDecision.MarkRemotelyDeleted
            return ProbeDecision.LeaveAlone
        }
        if (allPresent) return ProbeDecision.ConfirmReconciled
        if (markerPresent || mediaPresent || linkPresent) return ProbeDecision.RepairPartial
        return ProbeDecision.MayAutoUpload
    }
}
