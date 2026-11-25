package org.ncssar.rid2caltopo.data

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What MediaMTX's path defaults should say about recording. */
data class MediaMTXRecordingState(val record: Boolean, val recordingRoot: File?) {
    val recordPath: String? get() = recordingRoot?.let { MediaMTXConfig.recordPathFor(it) }
}

/** What recording depends on: the setting, storage, the recorder folder and the server. */
data class MediaMTXRecordingInputs(
    val serverRunning: Boolean,
    val captureEnabled: Boolean,
    val storageBlocked: Boolean,
    val recorderReady: Boolean,
    val recordApplied: Boolean,
)

/** Applies [MediaMTXRecordingState] to the running server through its local API. */
fun interface MediaMTXRecordingApi {
    /** True once MediaMTX accepted the change. */
    fun apply(state: MediaMTXRecordingState): Boolean
}

/** PATCH /v3/config/pathdefaults/patch on 127.0.0.1:9997 (hot reload, no restart). */
class MediaMTXHttpRecordingApi(
    private val baseUrl: String = "http://127.0.0.1:9997",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build(),
) : MediaMTXRecordingApi {
    override fun apply(state: MediaMTXRecordingState): Boolean = try {
        val request = Request.Builder()
            .url("$baseUrl/v3/config/pathdefaults/patch")
            .patch(patchBody(state).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        CaltopoClient.CTDebug(TAG, "Recording API unavailable: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "MediaMTXRecordingControl"

        /** JSON body. Recording settings ride along so turning recording on is self-contained. */
        @JvmStatic
        fun patchBody(state: MediaMTXRecordingState): String = buildString {
            append("{\"record\":").append(state.record)
            state.recordPath?.let {
                append(",\"recordPath\":\"").append(jsonEscape(it)).append('"')
                append(",\"recordFormat\":\"").append(MediaMTXConfig.RECORD_FORMAT_FMP4).append('"')
                append(",\"recordDeleteAfter\":\"0s\"")
            }
            append('}')
        }

        private fun jsonEscape(value: String): String = buildString {
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
                }
            }
        }
    }
}

/**
 * Keeps MediaMTX recording in line with capture, storage and recorder readiness without
 * restarting the server, so the RTMP listener and live publishers are never interrupted.
 *
 * Storage safety: when recording must go off and the API cannot be reached after the
 * retries, [onDisableFailed] runs (the service falls back to a restart).
 */
class MediaMTXRecordingReconciler(
    private val api: MediaMTXRecordingApi,
    private val executor: Executor,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val retryDelaysMs: List<Long> = listOf(50, 100, 200, 400, 800, 1_600, 3_200),
    private val onDisableFailed: () -> Unit = {},
    /** Every change of what recording depends on, for the REC marker (RecordingStatus). */
    private val onInputsChanged: (MediaMTXRecordingInputs) -> Unit = {},
) {
    constructor(api: MediaMTXRecordingApi, executor: Executor, onDisableFailed: Runnable) :
        this(api, executor, onDisableFailed = { onDisableFailed.run() })

    constructor(
        api: MediaMTXRecordingApi,
        executor: Executor,
        onDisableFailed: Runnable,
        onInputsChanged: java.util.function.Consumer<MediaMTXRecordingInputs>,
    ) : this(api, executor, onDisableFailed = { onDisableFailed.run() }, onInputsChanged = { onInputsChanged.accept(it) })

    private val lock = Any()
    private var serverGeneration = 0L
    private var applied: MediaMTXRecordingState? = null
    private var captureEnabled = false
    private var storageBlocked = true
    private var recordingRoot: File? = null
    private var queued = false

    /** What the config file said when this server generation started. */
    fun onServerStarted(configured: MediaMTXRecordingState, captureEnabled: Boolean, storageBlocked: Boolean, recordingRoot: File?) {
        synchronized(lock) {
            serverGeneration++
            applied = configured
            this.captureEnabled = captureEnabled
            this.storageBlocked = storageBlocked
            this.recordingRoot = recordingRoot
        }
        publishInputs()
        reconcile()
    }

    fun onServerStopped() { synchronized(lock) { serverGeneration++; applied = null }; publishInputs() }

    fun updateStorageBlocked(blocked: Boolean) { synchronized(lock) { storageBlocked = blocked }; publishInputs(); reconcile() }

    fun updateRecorderRoot(root: File?) { synchronized(lock) { recordingRoot = root }; publishInputs(); reconcile() }

    fun updateCaptureEnabled(enabled: Boolean) { synchronized(lock) { captureEnabled = enabled }; publishInputs(); reconcile() }

    fun inputs(): MediaMTXRecordingInputs = synchronized(lock) {
        MediaMTXRecordingInputs(applied != null, captureEnabled, storageBlocked, recordingRoot != null, applied?.record == true)
    }

    private fun publishInputs() = onInputsChanged(inputs())

    fun desired(): MediaMTXRecordingState = synchronized(lock) { desiredLocked() }

    fun appliedState(): MediaMTXRecordingState? = synchronized(lock) { applied }

    private fun desiredLocked(): MediaMTXRecordingState {
        val root = recordingRoot
        return MediaMTXRecordingState(captureEnabled && !storageBlocked && root != null, root ?: applied?.recordingRoot)
    }

    private fun reconcile() {
        synchronized(lock) {
            if (queued || applied == null) return
            queued = true
        }
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val generation: Long
            val target: MediaMTXRecordingState
            synchronized(lock) {
                val current = applied
                target = desiredLocked()
                if (current == null || current.record == target.record &&
                    (!target.record || current.recordingRoot == target.recordingRoot)) {
                    queued = false
                    return
                }
                generation = serverGeneration
            }
            var accepted = false
            for (delayMs in listOf(0L) + retryDelaysMs) {
                if (delayMs > 0) sleeper(delayMs)
                if (synchronized(lock) { serverGeneration != generation }) break
                if (api.apply(target)) { accepted = true; break }
            }
            synchronized(lock) {
                if (serverGeneration != generation) return@synchronized
                if (accepted) {
                    applied = target
                    CaltopoClient.CTDebug(TAG, "Recording ${if (target.record) "on" else "off"} via API")
                }
            }
            if (accepted) publishInputs()
            if (!accepted && synchronized(lock) { serverGeneration == generation }) {
                synchronized(lock) { queued = false }
                CaltopoClient.CTError(TAG, "MediaMTX recording API did not accept record=${target.record}")
                if (!target.record) onDisableFailed()
                return
            }
        }
    }

    private companion object { const val TAG = "MediaMTXRecordingControl" }
}
