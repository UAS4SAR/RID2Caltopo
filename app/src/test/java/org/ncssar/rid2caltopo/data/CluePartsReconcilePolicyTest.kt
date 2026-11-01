package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CluePartsReconcilePolicyTest {
    @Test
    fun publishedUnreconciledNeedsAutoReverify() {
        assertTrue(CluePartsReconcilePolicy.needsAutoReverify("published", partsReconciled = false))
    }

    @Test
    fun publishedReconciledStopsAutoReverify() {
        assertFalse(CluePartsReconcilePolicy.needsAutoReverify("published", partsReconciled = true))
    }

    @Test
    fun pendingAndFailedNeverAutoReverifyAsPublished() {
        assertFalse(CluePartsReconcilePolicy.needsAutoReverify("pending", partsReconciled = false))
        assertFalse(CluePartsReconcilePolicy.needsAutoReverify("failed", partsReconciled = false))
        assertFalse(CluePartsReconcilePolicy.needsAutoReverify("uploading", partsReconciled = false))
    }

    @Test
    fun retryClearsOnlyReconciledFlag() {
        assertTrue(CluePartsReconcilePolicy.clearReconciledOnRetry(partsReconciled = true))
        assertFalse(CluePartsReconcilePolicy.clearReconciledOnRetry(partsReconciled = false))
    }
}
