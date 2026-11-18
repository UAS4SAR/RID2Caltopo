package org.ncssar.rid2caltopo.storage

import java.io.File

/**
 * Soft-unavailable Archive Dir helpers. Retained operational bytes still live under
 * app-private paths; Archive Dir copy/hydrate waits until the operator reconnects.
 */
internal open class ArchiveDirUnavailableException(message: String) : IllegalStateException(message)

/** Why retained operational state cannot be prepared right now. None of these may crash the app. */
internal enum class ArchiveStateProblem {
    /** App-private folders link to a card that is not in the tablet (for example after a card swap). */
    PREVIOUS_STORAGE_MISSING,
    /** Retained state belongs to a previous Archive Dir that must be reconnected before moving it. */
    PREVIOUS_ARCHIVE_REQUIRED,
    /** Another thread's preparation of the same state failed while this one waited for it. */
    PREPARATION_FAILED_ELSEWHERE,
}

/**
 * Typed "Reconnect the storage holding …" / "… is no longer available" failures. A subclass of
 * [ArchiveDirUnavailableException], so every soft path (tryRequireReady, tryRequireRecorderReady)
 * treats it as unavailable instead of rethrowing it on a worker thread.
 */
internal class ArchiveStateUnavailableException(
    message: String,
    val problem: ArchiveStateProblem,
    val areaId: String? = null,
    /** Volume id (for example "4A21-0000") of the missing card, when the link names one. */
    val missingVolume: String? = null,
) : ArchiveDirUnavailableException(message)

internal object ArchiveDirAvailability {
    const val CHOOSE_OR_RECONNECT =
        "Choose or reconnect Archive Dir to use retained operational data."
    const val CHOOSE_OR_RECONNECT_STATE =
        "Choose or reconnect Archive Dir to use retained operational state."
    const val CHOOSE_OR_RECONNECT_RECORDING =
        "Choose or reconnect Archive Dir before recording."
    const val CHOOSE_OR_RECONNECT_CLUES =
        "Choose or reconnect Archive Dir before deleting retained clues."

    fun isUnavailable(error: Throwable): Boolean {
        if (error is ArchiveDirUnavailableException) return true
        return isUnavailableMessage(error.message)
    }

    fun isUnavailableMessage(message: String?): Boolean {
        val text = message.orEmpty()
        return text.contains("Choose or reconnect Archive Dir") ||
            text.contains("Archive Dir is unavailable") ||
            text.contains("Archive Dir became unavailable") ||
            text.contains("Archive Dir is not readable and writable") ||
            text.contains("Reconnect Archive Dir")
    }

    /** Queue / journal files stay app-private when no tree URI is selected. */
    fun useAppPrivateQueueOnly(archiveUriPresent: Boolean): Boolean = !archiveUriPresent

    /** "4A21-0000" for "/storage/4A21-0000/…"; null for tablet storage or other paths. */
    fun removableVolumeOf(linkTarget: String?): String? {
        val match = Regex("^/(?:storage|mnt/media_rw)/([^/]+)(?:/|$)").find(linkTarget.orEmpty()) ?: return null
        val id = match.groupValues[1]
        return id.takeUnless { it == "emulated" || it == "self" || it.isBlank() }
    }

    fun previousStorageMissing(areaId: String, linkTarget: String?): ArchiveStateUnavailableException =
        ArchiveStateUnavailableException(
            "Reconnect the storage holding $areaId; its original files were retained.",
            ArchiveStateProblem.PREVIOUS_STORAGE_MISSING, areaId, removableVolumeOf(linkTarget),
        )

    fun previousArchiveRequired(areaId: String, linkTarget: String?): ArchiveStateUnavailableException =
        ArchiveStateUnavailableException(
            "Reconnect the previous Archive Dir before moving $areaId to another archive.",
            ArchiveStateProblem.PREVIOUS_ARCHIVE_REQUIRED, areaId, removableVolumeOf(linkTarget),
        )

    fun preparationFailedElsewhere(what: String): ArchiveStateUnavailableException =
        ArchiveStateUnavailableException("$what is no longer available.", ArchiveStateProblem.PREPARATION_FAILED_ELSEWHERE)

    fun operationalStateFile(filesDir: File, name: String): File {
        val file = File(filesDir, "operational-state/$name")
        file.parentFile?.mkdirs()
        return file
    }
}
