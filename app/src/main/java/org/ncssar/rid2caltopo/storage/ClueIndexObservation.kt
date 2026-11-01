package org.ncssar.rid2caltopo.storage

/**
 * UI / in-memory clue index must not flash empty or shrink while Archive Dir prepare
 * is renaming or merging the durable clues tree. Keep serving the last good observation
 * until prepare finishes, then accept the next full read.
 */
internal object ClueIndexObservation {
    fun shouldPublish(
        previousCount: Int,
        newCount: Int,
        prepareInProgress: Boolean,
    ): Boolean {
        if (!prepareInProgress) return true
        if (previousCount <= 0) return true
        // Never regress to empty or a partial set while prepare holds the tree.
        return newCount >= previousCount
    }
}
