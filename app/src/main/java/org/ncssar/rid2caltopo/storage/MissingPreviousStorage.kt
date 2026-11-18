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
 * Remembers which previous card holds retained state that could not be reached. One alert per
 * missing card per app run: further failed checks only add area names, they never re-open it.
 * Nothing here deletes, moves or re-links retained files; the operator decides.
 */
internal object MissingPreviousStorage {
    private val _notice = MutableStateFlow<MissingPreviousStorageNotice?>(null)
    val notice: StateFlow<MissingPreviousStorageNotice?> = _notice

    private val _alert = MutableStateFlow<MissingPreviousStorageNotice?>(null)
    /** Pending alert, set once when a missing card is first seen. */
    val alert: StateFlow<MissingPreviousStorageNotice?> = _alert

    private val alerted = mutableSetOf<String>()
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
        val key = volume ?: "unknown"
        if (alerted.add(key)) {
            log("Retained ${areas.joinToString()} is on storage that is not present (${volume ?: "unknown card"}); files left in place")
            _alert.value = next
        } else if (_alert.value != null) {
            _alert.value = next
        }
    }

    fun dismissAlert() { _alert.value = null }

    /** The map chip re-opens the alert on request; failed checks never do. */
    fun reshowAlert() { _notice.value?.let { _alert.value = it } }

    /** The card came back, or another archive was chosen: forget it so a later loss alerts again. */
    @Synchronized
    fun clear() {
        _notice.value = null
        _alert.value = null
        alerted.clear()
    }
}
