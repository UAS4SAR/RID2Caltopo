package org.ncssar.rid2caltopo.storage

/**
 * Pure reconciliation for bulk clues hydrate vs the independent clues lane.
 * Deletes and adds during hydration look like lost/changed clues against a stale
 * migrated snapshot; those races are non-fatal.
 */
internal object ClueHydrationReconcile {
    enum class Verdict { MATCH, RACE, MISMATCH }

    fun verdict(
        stagedHash: String?,
        expectedHash: String,
        archivePresent: Boolean,
        archiveHash: String?,
    ): Verdict {
        if (!archivePresent) return Verdict.RACE
        if (stagedHash == null) return Verdict.RACE
        if (stagedHash == expectedHash) return Verdict.MATCH
        if (archiveHash != null && stagedHash == archiveHash) return Verdict.RACE
        return Verdict.MISMATCH
    }
}
