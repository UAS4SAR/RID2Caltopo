package org.ncssar.rid2caltopo.app

import kotlinx.coroutines.CoroutineExceptionHandler
import org.ncssar.rid2caltopo.data.CaltopoClient

/**
 * Top-level safety net for startup / long-lived app scopes. Logs without secrets or
 * precise coordinates; does not replace soft handling of Archive Dir unavailability.
 */
object StartupCoroutineSafety {
    private const val TAG = "StartupCoroutine"

    val handler = CoroutineExceptionHandler { _, error ->
        if (error is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
        CaltopoClient.CTError(TAG, "Uncaught ${error.javaClass.simpleName}: ${sanitize(error.message)}", error as? Exception)
    }

    /** Strip precise coordinates and long numeric tokens from exception text before logging. */
    internal fun sanitize(message: String?): String {
        if (message.isNullOrBlank()) return "(no message)"
        return message
            .replace(Regex("""-?\d+\.\d{3,}"""), "[coord]")
            .replace(Regex("""\b-?\d{5,}\b"""), "[n]")
            .take(400)
    }
}
