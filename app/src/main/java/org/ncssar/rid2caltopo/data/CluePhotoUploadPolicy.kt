package org.ncssar.rid2caltopo.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Shared clue-photo upload timeouts and CalTopo 400 classification (keep in sync with Apple). */
object CluePhotoUploadPolicy {
    /** Marker / media metadata / MapMediaObject JSON calls (v2.5.4 session default). */
    const val JSON_TIMEOUT_SECONDS = 60
    /** Base allowance for the JPEG `/data` POST before size scaling. */
    const val PHOTO_TIMEOUT_BASE_SECONDS = 45
    /** Extra seconds per megabyte of JPEG payload (wire is larger after base64). */
    const val PHOTO_TIMEOUT_PER_MEGABYTE_SECONDS = 30
    const val PHOTO_TIMEOUT_CAP_SECONDS = 180
    /** Non-/data happy-path steps: marker-post, media-get, media-post, link-get, link-post. */
    const val ATTEMPT_BUDGET_JSON_STEP_COUNT = 5
    /** Overhead for marker/media/link steps around the photo POST in one send budget. */
    const val ATTEMPT_BUDGET_OVERHEAD_SECONDS = JSON_TIMEOUT_SECONDS * ATTEMPT_BUDGET_JSON_STEP_COUNT

    const val EXISTING_BACKEND_MEDIA_TOKEN = "Cannot update existing backendMedia"
    /** Optional test hook; production leaves this null. Never includes secrets or coordinates. */
    @JvmField
    @Volatile
    var testingFailureLogSink: ((String) -> Unit)? = null
    /** Returned when media create/data state cannot be verified; caller must mint a new media id. */
    const val AMBIGUOUS_MEDIA_STATE_TOKEN = "media state ambiguous"
    const val NEEDS_ATTENTION_MESSAGE = "Needs attention"
    /** Terminal local failure when the JPEG cannot be recovered from any known root. */
    const val PHOTO_MISSING_MESSAGE = "Photo missing"
    /** Appended to the CalTopo marker description when uploading a marker without its photo. */
    const val PHOTO_LOST_DESCRIPTION_NOTE =
        "Photo was lost on this device before upload; marker only."
    /** Identical permanent 4xx fingerprints before auto-retry stops (still saved; woken on reconnect). */
    const val PERMANENT_FAILURE_LIMIT = 5

    /** True when auto-upload must stop and wait for an explicit operator action. */
    @JvmStatic
    fun stopsAutoRetry(lastUploadError: String?): Boolean {
        val text = lastUploadError?.trim().orEmpty()
        if (text.isEmpty()) return false
        return text.contains(NEEDS_ATTENTION_MESSAGE, ignoreCase = true) ||
            isPhotoMissing(text)
    }

    /** True when the durable last-error means the JPEG is gone. */
    @JvmStatic
    fun isPhotoMissing(lastUploadError: String?): Boolean {
        val text = lastUploadError?.trim().orEmpty()
        if (text.isEmpty()) return false
        return text.contains(PHOTO_MISSING_MESSAGE, ignoreCase = true) ||
            text.equals("missing-photo", ignoreCase = true)
    }

    @JvmStatic
    fun descriptionNotesLostPhoto(description: String?): Boolean {
        val text = description.orEmpty()
        return text.contains(PHOTO_LOST_DESCRIPTION_NOTE, ignoreCase = true)
    }

    @JvmStatic
    fun descriptionWithLostPhotoNote(description: String?): String {
        val base = description?.trim().orEmpty()
        if (descriptionNotesLostPhoto(base)) return base
        return if (base.isEmpty()) PHOTO_LOST_DESCRIPTION_NOTE else "$base\n\n$PHOTO_LOST_DESCRIPTION_NOTE"
    }

    private val localClueTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a zzz", Locale.US)
    private val utcClueTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    /** Seconds for the media `/data` HTTP call from JPEG byte length. */
    @JvmStatic
    fun photoUploadTimeoutSeconds(jpegBytes: Int): Int {
        val bytes = max(0, jpegBytes)
        val megabytes = bytes / (1024.0 * 1024.0)
        val scaled = PHOTO_TIMEOUT_BASE_SECONDS + ceil(megabytes).toInt() * PHOTO_TIMEOUT_PER_MEGABYTE_SECONDS
        return min(PHOTO_TIMEOUT_CAP_SECONDS, max(PHOTO_TIMEOUT_BASE_SECONDS, scaled))
    }

    /** Multi-step publish budget measured from when PublishStoredPhoto starts. */
    @JvmStatic
    fun attemptBudgetSeconds(jpegBytes: Int): Int {
        return min(
            PHOTO_TIMEOUT_CAP_SECONDS + ATTEMPT_BUDGET_OVERHEAD_SECONDS,
            photoUploadTimeoutSeconds(jpegBytes) + ATTEMPT_BUDGET_OVERHEAD_SECONDS,
        )
    }

    /** Overall wall-clock from attempt start; send begins immediately (no folder wait). */
    @JvmStatic
    fun submitDeadlineSeconds(jpegBytes: Int): Int {
        return attemptBudgetSeconds(jpegBytes)
    }

    /** CalTopo rejects re-POSTing an id that already has backend media (common after client timeout). */
    @JvmStatic
    fun isExistingBackendMediaError(statusCode: Int, message: String?): Boolean {
        if (statusCode != 400) return false
        val text = message.orEmpty()
        return text.contains(EXISTING_BACKEND_MEDIA_TOKEN, ignoreCase = true)
    }

    /** Media create/data failed in a way that must not reuse the same media id. */
    @JvmStatic
    fun isAmbiguousMediaState(statusCode: Int, message: String?): Boolean {
        val text = message.orEmpty()
        if (text.contains(AMBIGUOUS_MEDIA_STATE_TOKEN, ignoreCase = true)) return true
        return statusCode == 409 && text.contains(EXISTING_BACKEND_MEDIA_TOKEN, ignoreCase = true)
    }

    /** Deterministic client errors that will not succeed by blind retry of the same payload. */
    @JvmStatic
    fun isPermanentClientFailure(statusCode: Int): Boolean {
        return statusCode == 400 || statusCode == 401 || statusCode == 403 || statusCode == 404 ||
            statusCode == 409 || statusCode == 410 || statusCode == 413 || statusCode == 422
    }

    /** Fingerprint for consecutive identical permanent failures (status + brief body token). */
    @JvmStatic
    fun permanentFailureFingerprint(statusCode: Int, message: String?): String {
        return "$statusCode:${plainUploadError(message)}"
    }

    /** Plain-language last-error text for the pending-clues sheet. */
    @JvmStatic
    fun plainUploadError(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Waiting to upload"
        if (text.contains(NEEDS_ATTENTION_MESSAGE, ignoreCase = true)) {
            return NEEDS_ATTENTION_MESSAGE
        }
        if (text.contains(PHOTO_MISSING_MESSAGE, ignoreCase = true) ||
            text.equals("missing-photo", ignoreCase = true)
        ) {
            return PHOTO_MISSING_MESSAGE
        }
        if (text.contains(AMBIGUOUS_MEDIA_STATE_TOKEN, ignoreCase = true) ||
            text.contains(EXISTING_BACKEND_MEDIA_TOKEN, ignoreCase = true)
        ) {
            return "Photo upload incomplete; replacing photo id"
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

    /** Marker description time: local with zone abbreviation plus UTC in parentheses. */
    @JvmStatic
    @JvmOverloads
    fun formatClueMarkerTime(timestampMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(timestampMs).atZone(zoneId).format(localClueTimeFormatter)
        val utc = utcClueTimeFormatter.format(Instant.ofEpochMilli(timestampMs))
        return "$local ($utc)"
    }

    private val uuidSegment =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Path for clue failure logs: strip query; keep map id; scrub other UUID segments. */
    @JvmStatic
    fun cluePathForLog(path: String?): String {
        if (path.isNullOrEmpty()) return "/"
        val withoutQuery = path.substringBefore('?')
        return uuidSegment.replace(withoutQuery, "…")
    }

    /** Stable failure line for clue HTTP steps (no codes, keys, or coordinates). */
    @JvmStatic
    fun clueStepFailureMessage(
        step: String,
        method: String,
        path: String?,
        elapsedMs: Long,
        timeoutSeconds: Int,
    ): String {
        return "Clue step failed step=$step method=$method path=${cluePathForLog(path)} " +
            "elapsedMs=$elapsedMs timeoutSeconds=$timeoutSeconds"
    }

    @JvmStatic
    fun recordClueStepFailure(
        step: String,
        method: String,
        path: String?,
        elapsedMs: Long,
        timeoutSeconds: Int,
    ) {
        val line = clueStepFailureMessage(step, method, path, elapsedMs, timeoutSeconds)
        testingFailureLogSink?.invoke(line)
        CaltopoClient.CTWarn("CluePhotoUpload", line)
    }
}
