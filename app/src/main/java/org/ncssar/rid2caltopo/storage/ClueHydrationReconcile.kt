package org.ncssar.rid2caltopo.storage

/**
 * Pure reconciliation for bulk clues hydrate vs the independent clues lane.
 * Deletes and adds during hydration look like lost/changed clues against a stale
 * migrated snapshot; those races are non-fatal.
 *
 * `clues.json` is intentionally rewritten by [ClueProjectionMerge.mergeIndexJson] during
 * prepare, so its staged digest will not match the pre-merge migrated or archive hash.
 * That rewrite is a RACE (accept staged), not a MISMATCH warning.
 */
internal object ClueHydrationReconcile {
    enum class Verdict { MATCH, RACE, MISMATCH }

    fun verdict(
        stagedHash: String?,
        expectedHash: String,
        archivePresent: Boolean,
        archiveHash: String?,
        relativePath: String = "",
    ): Verdict {
        if (!archivePresent) return Verdict.RACE
        if (stagedHash == null) return Verdict.RACE
        if (stagedHash == expectedHash) return Verdict.MATCH
        if (archiveHash != null && stagedHash == archiveHash) return Verdict.RACE
        if (isMergedIndexPath(relativePath) && stagedHash.isNotBlank()) return Verdict.RACE
        return Verdict.MISMATCH
    }

    fun isMergedIndexPath(relativePath: String): Boolean {
        val path = relativePath.trim().removePrefix("/")
        return path == "clues.json" || path.endsWith("/clues.json")
    }
}
