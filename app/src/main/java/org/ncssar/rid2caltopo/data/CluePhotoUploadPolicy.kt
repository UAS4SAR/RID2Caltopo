package org.ncssar.rid2caltopo.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Shared clue-photo upload timeouts and CalTopo 400 classification (keep in sync with Apple). */
object CluePhotoUploadPolicy {
    /** Marker / media metadata / MapMediaObject JSON calls. */
    const val JSON_TIMEOUT_SECONDS = 15
    /** Base allowance for the JPEG `/data` POST before size scaling. */
    const val PHOTO_TIMEOUT_BASE_SECONDS = 45
    /** Extra seconds per megabyte of JPEG payload (wire is larger after base64). */
    const val PHOTO_TIMEOUT_PER_MEGABYTE_SECONDS = 30
    const val PHOTO_TIMEOUT_CAP_SECONDS = 180

    const val EXISTING_BACKEND_MEDIA_TOKEN = "Cannot update existing backendMedia"

    /** Seconds for the media `/data` HTTP call from JPEG byte length. */
    @JvmStatic
    fun photoUploadTimeoutSeconds(jpegBytes: Int): Int {
        val bytes = max(0, jpegBytes)
        val megabytes = bytes / (1024.0 * 1024.0)
        val scaled = PHOTO_TIMEOUT_BASE_SECONDS + ceil(megabytes).toInt() * PHOTO_TIMEOUT_PER_MEGABYTE_SECONDS
        return min(PHOTO_TIMEOUT_CAP_SECONDS, max(PHOTO_TIMEOUT_BASE_SECONDS, scaled))
    }

    /** CalTopo rejects re-POSTing an id that already has backend media (common after client timeout). */
    @JvmStatic
    fun isExistingBackendMediaError(statusCode: Int, message: String?): Boolean {
        if (statusCode != 400) return false
        val text = message.orEmpty()
        return text.contains(EXISTING_BACKEND_MEDIA_TOKEN, ignoreCase = true)
    }

    /** Plain-language last-error text for the pending-clues sheet. */
    @JvmStatic
    fun plainUploadError(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Waiting to upload"
        if (text.contains(EXISTING_BACKEND_MEDIA_TOKEN, ignoreCase = true)) {
            return "Photo already on CalTopo; finishing link"
        }
        if (text.contains("timed out", ignoreCase = true) || text.contains("timeout", ignoreCase = true)) {
            return "Upload timed out; will retry"
        }
        if (text.contains("unavailable", ignoreCase = true)) {
            return "CalTopo temporarily unavailable"
        }
        if (text.contains("login", ignoreCase = true) || text.contains("permission", ignoreCase = true)) {
            return "CalTopo login or permissions need attention"
        }
        return if (text.length > 160) text.take(157) + "…" else text
    }
}
