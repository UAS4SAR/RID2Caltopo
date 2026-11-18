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
        else -> "cannot be written"
    }

    private fun shortCondition(verdict: ArchiveWritabilityVerdict): String = when (verdict.problem) {
        ArchiveWriteProblem.READ_ONLY -> "read-only"
        ArchiveWriteProblem.IO_ERROR -> "I/O errors"
        else -> "not writable"
    }

    fun alertTitle(verdict: ArchiveWritabilityVerdict): String = "${subject(verdict)} ${if (verdict.problem == ArchiveWriteProblem.IO_ERROR) "has I/O errors" else "is ${shortCondition(verdict)}"}"

    /** [fallbackAvailable]: tablet storage has room above the reserve, so recordings and logs continue there. */
    fun alertMessage(verdict: ArchiveWritabilityVerdict, fallbackAvailable: Boolean): String =
        "${subject(verdict)} ${condition(verdict)}. " + if (fallbackAvailable) {
            "Recording to it is paused; live video still works. New recordings and logs are kept in tablet storage " +
                "and copied to your archive folder once it can be written again. " +
                "Choose \"$USE_TABLET_STORAGE\" to move the archive folder to this tablet."
        } else {
            "Recording is paused; live video still works. Tablet storage is also low, so nothing is recorded " +
                "until you free space or choose another folder in $MANAGE_STORAGE."
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
