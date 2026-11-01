package org.ncssar.rid2caltopo.data

/**
 * After marker + media + link are confirmed, published clues stay reconciled until Retry
 * or an ambiguous demotion. Auto re-verify runs at most once per app start / map connect
 * for clues that are still pending confirmation — never for already-reconciled rows.
 */
internal object CluePartsReconcilePolicy {
    fun needsAutoReverify(uploadState: String, partsReconciled: Boolean): Boolean {
        if (uploadState != "published") return false
        return !partsReconciled
    }

    /** User Retry may re-probe published clues that were previously confirmed. */
    fun clearReconciledOnRetry(partsReconciled: Boolean): Boolean = partsReconciled
}
