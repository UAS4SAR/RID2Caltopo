package org.ncssar.rid2caltopo.data

/**
 * Decides which CalTopo folder id a clue POST should use after the connect full-map read.
 * Only clue uploads wait on the initial read; incremental / single-entity GETs do not.
 */
object ClueTrackFolderSend {
    const val INITIAL_READ_WAIT_MS: Long = 30_000L

    /**
     * @param initialReadCompleted true when the connect full-map read finished within the wait budget
     * @param cachedFolderId already-adopted or previously ensured active track folder
     * @param ensureFolderId create/reuse Drone Tracks folders; used only after a completed read
     * @return folder id for the clue POST, or null/blank to send without folderId and move later
     */
    @JvmStatic
    fun folderIdForSend(
        initialReadCompleted: Boolean,
        cachedFolderId: String?,
        ensureFolderId: () -> String?,
    ): String? {
        cachedFolderId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!initialReadCompleted) return null
        return ensureFolderId()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
