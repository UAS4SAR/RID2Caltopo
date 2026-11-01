package org.ncssar.rid2caltopo.data

/**
 * Owner-loop wake bookkeeping for pending clue uploads.
 *
 * A live owner with no per-clue upload ids is not "upload-in-flight" — that used to happen
 * while re-verify/reconcile of other clues held the owner and caused Submit to skip with
 * `ids={}`. Submit always sets [wakeRequested]; an active owner must observe the wake
 * instead of being reported as a full skip when no ids are uploading.
 */
internal object ClueUploadWakeGate {
    data class Decision(
        val startOwner: Boolean,
        /** Non-null when the caller should log an in-flight skip; empty ids must never appear. */
        val skipLogIds: Collection<String>? = null,
    )

    fun decide(ownerActive: Boolean, inFlightIds: Collection<String>): Decision {
        if (!ownerActive) return Decision(startOwner = true)
        val ids = inFlightIds.filter { it.isNotBlank() }
        return if (ids.isEmpty()) {
            // Owner is alive (backoff / between batches) but nothing is uploading; it will
            // observe the wake flag. Do not claim upload-in-flight with an empty id set.
            Decision(startOwner = false, skipLogIds = null)
        } else {
            Decision(startOwner = false, skipLogIds = ids)
        }
    }
}
