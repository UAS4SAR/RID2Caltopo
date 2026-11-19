package org.ncssar.rid2caltopo.storage

import java.text.DateFormat
import java.util.Date

/** User-facing wording for a not-writable archive. Shared by the alert, Flight Storage and the map chip. */
object ArchiveWritabilityText {
    const val USE_TABLET_STORAGE = "Use tablet storage"
    const val MANAGE_STORAGE = "Manage Storage"

    fun subject(verdict: ArchiveWritabilityVerdict): String = if (verdict.removable) "SD card" else "Archive storage"

    private fun condition(verdict: ArchiveWritabilityVerdict): String = when (verdict.problem) {
        ArchiveWriteProblem.READ_ONLY -> "is read-only (likely failing)"
        ArchiveWriteProblem.IO_ERROR -> "has I/O errors (likely failing)"
        ArchiveWriteProblem.MISSING -> "with the archive folder is not in this tablet"
        else -> "cannot be written"
    }

    private fun shortCondition(verdict: ArchiveWritabilityVerdict): String = when (verdict.problem) {
        ArchiveWriteProblem.READ_ONLY -> "read-only"
        ArchiveWriteProblem.IO_ERROR -> "I/O errors"
        ArchiveWriteProblem.MISSING -> "missing"
        else -> "not writable"
    }

    fun alertTitle(verdict: ArchiveWritabilityVerdict): String = "${subject(verdict)} ${if (verdict.problem == ArchiveWriteProblem.IO_ERROR) "has I/O errors" else "is ${shortCondition(verdict)}"}"

    /** [fallbackAvailable]: tablet storage has room above the reserve, so recordings and logs continue there. */
    fun alertMessage(verdict: ArchiveWritabilityVerdict, fallbackAvailable: Boolean): String =
        if (verdict.problem == ArchiveWriteProblem.MISSING) missingMessage(fallbackAvailable)
        else "${subject(verdict)} ${condition(verdict)}. " + if (fallbackAvailable) {
            "Recording to it is paused; live video still works. New recordings and logs are kept in tablet storage " +
                "and copied to your archive folder once it can be written again. " +
                "Choose \"$USE_TABLET_STORAGE\" to move the archive folder to this tablet."
        } else {
            "Recording is paused; live video still works. Tablet storage is also low, so nothing is recorded " +
                "until you free space or choose another folder in $MANAGE_STORAGE."
        }

    /** The card holding the active archive folder was removed: reinsert it or pick another folder. */
    private fun missingMessage(fallbackAvailable: Boolean): String =
        "The SD card with your archive folder is not in this tablet. " + if (fallbackAvailable) {
            "Live video still works, and new recordings and logs are kept in tablet storage until it can be written. " +
                "Reinsert that card, or choose another archive folder in $MANAGE_STORAGE " +
                "(or \"$USE_TABLET_STORAGE\")."
        } else {
            "Live video still works. Tablet storage is also low, so nothing is recorded until you reinsert " +
                "that card or choose another archive folder in $MANAGE_STORAGE."
        }

    /** One line for the map chip. */
    fun chip(verdict: ArchiveWritabilityVerdict, fallbackAvailable: Boolean): String =
        "${subject(verdict)} ${shortCondition(verdict)} · " + if (fallbackAvailable) "saving to tablet" else "recording paused"

    /** Steady status line for Flight Storage. */
    fun status(verdict: ArchiveWritabilityVerdict, fallbackAvailable: Boolean, time: (Long) -> String = ::shortTime): String =
        "${subject(verdict)} ${condition(verdict)} since ${time(verdict.sinceMs)}. " + if (fallbackAvailable) {
            "Recordings and logs are kept in tablet storage and copied when the archive folder can be written again."
        } else {
            "Recording is paused: tablet storage is also low."
        } + " Checked again on remount, folder change, or every ${ArchiveWritabilityTracker.RECHECK_INTERVAL_MS / 60_000} minutes."

    private fun shortTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
}

/** Wording for retained state that sits on a card that is no longer in the tablet. */
internal object MissingPreviousStorageText {
    const val OK = "OK"
    const val MANAGE_STORAGE = ArchiveWritabilityText.MANAGE_STORAGE

    private fun card(notice: MissingPreviousStorageNotice): String =
        notice.volume?.let { "the previous SD card ($it)" } ?: "the previous storage"

    fun title(notice: MissingPreviousStorageNotice): String =
        if (notice.problem == ArchiveStateProblem.PREVIOUS_ARCHIVE_REQUIRED) "Previous archive folder is missing" else "Previous SD card is missing"

    fun message(notice: MissingPreviousStorageNotice): String {
        val what = describe(notice.areas)
        return "Some saved app data ($what) is on ${card(notice)}, which is not in this tablet. " +
            "Nothing was deleted or moved. Live video still works; new recordings are staged in temporary " +
            "tablet storage while this lasts. Reinsert that card to pick up where you left off, " +
            "or open $MANAGE_STORAGE to choose another archive folder."
    }

    /** One line for Flight Storage. */
    fun status(notice: MissingPreviousStorageNotice): String =
        "Saved app data (${describe(notice.areas)}) is on ${card(notice)}, which is not in this tablet. Nothing was deleted."

    fun describe(areas: Set<String>): String {
        val names = areas.map { id ->
            when (id) {
                "unfinished-recordings", "recording-work", "recording-originals", "recordings" -> "unfinished recordings"
                "queue-state" -> "pending uploads and flight state"
                "missions", "layers", "processing", "map-statistics" -> "mapping data"
                "map-archives" -> "offline maps"
                else -> id.replace('-', ' ')
            }
        }.distinct()
        return names.ifEmpty { listOf("operational data") }.joinToString(", ")
    }
}
