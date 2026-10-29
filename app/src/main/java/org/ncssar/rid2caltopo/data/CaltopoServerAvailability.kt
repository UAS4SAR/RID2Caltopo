package org.ncssar.rid2caltopo.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Wording for a CalTopo HTTP result. Login text is only for 401 and 403. */
object CaltopoServerAvailability {
    @JvmStatic fun isUnavailable(code: Int, timedOut: Boolean): Boolean =
        timedOut || code == 408 || code >= 500

    @JvmStatic fun message(code: Int, personal: Boolean, timedOut: Boolean): String {
        if (code == 401 || code == 403) {
            return if (personal) {
                "Personal map request failed; check your login and map permissions. Team credentials were not used."
            } else {
                "CalTopo request failed; check your login and map permissions."
            }
        }
        if (isUnavailable(code, timedOut)) {
            val detail = if (timedOut && code <= 0) "timed out" else "HTTP $code"
            return "CalTopo server unavailable ($detail), retrying..."
        }
        return if (personal) "Personal map request failed." else "CalTopo request failed."
    }
}

/** Shown on the connecting dialog while a full map read backs off after a server failure. */
object CaltopoMapOpenNotice {
    var text by mutableStateOf<String?>(null)
        private set

    @JvmStatic fun show(message: String) { text = message }
    @JvmStatic fun clear() { text = null }
}
