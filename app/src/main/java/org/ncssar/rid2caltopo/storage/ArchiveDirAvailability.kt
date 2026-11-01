package org.ncssar.rid2caltopo.storage

import java.io.File

/**
 * Soft-unavailable Archive Dir helpers. Retained operational bytes still live under
 * app-private paths; Archive Dir copy/hydrate waits until the operator reconnects.
 */
internal class ArchiveDirUnavailableException(message: String) : IllegalStateException(message)

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

    fun operationalStateFile(filesDir: File, name: String): File {
        val file = File(filesDir, "operational-state/$name")
        file.parentFile?.mkdirs()
        return file
    }
}
