package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient

/** A live stream that is not being recorded because Capture Streams is off. */
data class NotCapturingStream(val key: String, val designator: String)

/**
 * "Not capturing stream" panel state. When a stream goes live while Capture Streams is off,
 * the map shows a compact panel: Confirm keeps capture off for that stream; "Turn on Stream
 * capture" enables capture and recording starts at once (no video restart).
 */
object NotCapturingStreams {
    const val CONFIRM = "Confirm"
    const val TURN_ON = "Turn on Stream capture"

    fun title(stream: NotCapturingStream): String = "Not capturing stream: ${stream.designator}"

    /** One publish of one stream: a reconnect with a new publisher asks again. */
    fun key(info: StreamInfo): String = "${info.sourcePath}#${info.publisherConnId.orEmpty()}"

    /** Pure: live, published streams that are not captured and not yet confirmed. */
    fun pending(streams: Collection<StreamInfo>, captureEnabled: Boolean, dismissed: Set<String>): List<NotCapturingStream> {
        if (captureEnabled) return emptyList()
        return streams.asSequence()
            .filter { it.state == StreamState.LIVE && !it.isLocalPlayback }
            .map { NotCapturingStream(key(it), it.designator) }
            .filter { it.key !in dismissed }
            .distinctBy { it.key }
            .sortedBy { it.designator.lowercase() }
            .toList()
    }

    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    val dismissed: StateFlow<Set<String>> = _dismissed

    private val _captureEnabled = MutableStateFlow(runCatching { CaltopoClient.GetCaptureVideoStreamsFlag() }.getOrDefault(true))
    val captureEnabled: StateFlow<Boolean> = _captureEnabled

    init {
        runCatching {
            CaltopoClient.AddCaptureStreamsListener { _captureEnabled.value = CaltopoClient.GetCaptureVideoStreamsFlag() }
        }
    }

    /** Re-reads the setting (e.g. after settings load). */
    fun refresh() {
        _captureEnabled.value = CaltopoClient.GetCaptureVideoStreamsFlag()
    }

    fun confirm(stream: NotCapturingStream) {
        _dismissed.value = _dismissed.value + stream.key
        CaltopoClient.CTInfo("NotCapturingStreams", "Capture Streams left OFF for ${stream.designator} (confirmed on panel)")
    }

    /** Turns capture on; the stream server starts recording through its API without restarting. */
    fun turnOn() {
        CaltopoClient.SetCaptureVideoStreamsFlag(true, CaltopoClient.CAPTURE_SOURCE_PANEL)
        _captureEnabled.value = CaltopoClient.GetCaptureVideoStreamsFlag()
    }

    /** Forgets confirmations for streams that are no longer live, so the set stays small. */
    fun prune(liveKeys: Set<String>) {
        val kept = _dismissed.value.intersect(liveKeys)
        if (kept.size != _dismissed.value.size) _dismissed.value = kept
    }
}
