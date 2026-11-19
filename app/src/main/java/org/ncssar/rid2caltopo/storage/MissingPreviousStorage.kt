package org.ncssar.rid2caltopo.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the archive-storage alert says about retained state on a card that is not in the tablet. */
internal data class MissingPreviousStorageNotice(
    /** Volume id of the missing card ("4A21-0000"), or null when the link does not name one. */
    val volume: String?,
    val problem: ArchiveStateProblem,
    /** Retained areas found on it so far (for example "unfinished-recordings", "queue-state"). */
    val areas: Set<String>,
)

/**
 * Remembers retained state on a previous card that could not be reached and was not simply
 * swapped out (a card swap is handled by [DetachedPreviousStorage] and never lands here).
 * Shown only as a quiet line in Manage Storage: no map chip and no pop-up, because the active
 * archive folder is fine (Ken, 2026-11-18). A missing active archive is reported by
 * ArchiveWritability instead. Nothing here deletes, moves or re-links retained files.
 */
internal object MissingPreviousStorage {
    private val _notice = MutableStateFlow<MissingPreviousStorageNotice?>(null)
    val notice: StateFlow<MissingPreviousStorageNotice?> = _notice

    private val _alert = MutableStateFlow<MissingPreviousStorageNotice?>(null)
    /** Never raised any more; kept so the shared archive alert keeps one shape. */
    val alert: StateFlow<MissingPreviousStorageNotice?> = _alert

    private val logged = mutableSetOf<String>()
    @Volatile var log: (String) -> Unit = {}

    fun note(error: ArchiveStateUnavailableException) {
        if (error.problem == ArchiveStateProblem.PREPARATION_FAILED_ELSEWHERE) return
        note(error.missingVolume, error.problem, error.areaId)
    }

    @Synchronized
    fun note(volume: String?, problem: ArchiveStateProblem, areaId: String?) {
        val current = _notice.value
        val areas = if (current != null && current.volume == volume) current.areas + listOfNotNull(areaId) else setOfNotNull(areaId)
        val next = MissingPreviousStorageNotice(volume, current?.takeIf { it.volume == volume }?.problem ?: problem, areas)
        if (next == current) return
        _notice.value = next
        if (logged.add(volume ?: "unknown")) {
            log("Retained ${areas.joinToString()} is on storage that is not present (${volume ?: "unknown card"}); files left in place")
        }
    }

    fun dismissAlert() { _alert.value = null }

    /** The map chip no longer shows this notice, so there is nothing to re-open. */
    fun reshowAlert() {}

    /** The card came back, another archive was chosen, or storage became ready. Clears at once. */
    @Synchronized
    fun clear() {
        _notice.value = null
        _alert.value = null
        logged.clear()
    }
}
