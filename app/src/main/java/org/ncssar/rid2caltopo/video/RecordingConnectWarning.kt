package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.ui.SpokenWarningCenter
import org.ncssar.rid2caltopo.ui.SpokenWarningKind

/** The "Video is not being recorded" prompt: at a live publish, or from a tap on the marker. */
data class RecordingPrompt(
    val key: String,
    val designator: String,
    val reason: NotRecordingReason,
    /** From a tap on the marker rather than a new publish (no speech). */
    val fromTap: Boolean = false,
) {
    val title: String get() = RecordingMarkerPolicy.PROMPT_TITLE
    val text: String get() = RecordingMarkerPolicy.promptText(designator, reason)
    /** "Turn on" is the default button only when the setting is the problem. */
    val offersTurnOn: Boolean get() = reason == NotRecordingReason.CAPTURE_OFF
}

/**
 * Spoken warning and prompt when a stream goes live without being recorded (Ken, Nov 25 2026).
 * Runs in the process-scoped alert coroutine (works with the display off), not in Compose.
 */
object RecordingConnectWarning {
    private const val TAG = "RecordingWarning"
    private val policy = RecordingConnectWarningPolicy()
    private val _prompts = MutableStateFlow<List<RecordingPrompt>>(emptyList())
    /** Oldest first; the host shows the first one. */
    val prompts: StateFlow<List<RecordingPrompt>> = _prompts

    fun streamKey(info: StreamInfo): String = NotCapturingStreams.key(info)

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(StreamRegistry.streams, RecordingStatus.state) { streams, status -> streams to status }
                .collect { (streams, status) -> evaluate(streams.values, status) }
        }
        // STARTING turns into NOT RECORDING by time alone.
        scope.launch {
            while (isActive) {
                delay(1_000L)
                evaluate(StreamRegistry.streams.value.values, RecordingStatus.state.value)
            }
        }
    }

    @Synchronized
    internal fun evaluate(streams: Collection<StreamInfo>, status: RecordingStatus.Snapshot, nowMs: Long = RecordingStatus.clock()) {
        val archiveWritable = runCatching { org.ncssar.rid2caltopo.app.ArchiveWritability.writable }.getOrDefault(true)
        val candidates = streams.map { info ->
            RecordingConnectWarningPolicy.Stream(
                key = streamKey(info),
                designator = info.designator,
                marker = status.marker(info.sourcePath, info.state == StreamState.LIVE, info.isLocalPlayback, nowMs, archiveWritable),
            )
        }
        val liveKeys = candidates.filter { it.marker.state != RecordingMarkerState.HIDDEN }.map { it.key }.toSet()
        val warnings = policy.evaluate(candidates)
        val kept = _prompts.value.filter { it.fromTap || it.key in liveKeys }
        if (warnings.isEmpty()) {
            if (kept.size != _prompts.value.size) _prompts.value = kept
            return
        }
        warnings.forEach { warning ->
            CaltopoClient.CTWarn(TAG, "Live stream ${warning.designator} is not being recorded reason=${warning.reason}")
            SpokenWarningCenter.requestSpokenPhrase(
                kind = SpokenWarningKind.VideoNotRecording,
                sourceKey = warning.key,
                phrase = RecordingMarkerPolicy.SPOKEN_PHRASE,
            )
        }
        _prompts.value = kept + warnings.map { RecordingPrompt(it.key, it.designator, it.reason) }
    }

    /** The amber marker was tapped and the setting is not the problem: say why. */
    fun showReason(key: String, designator: String, reason: NotRecordingReason) {
        val prompt = RecordingPrompt(key, designator, reason, fromTap = true)
        _prompts.value = _prompts.value.filterNot { it.key == key } + prompt
    }

    fun turnOn(prompt: RecordingPrompt) {
        CaltopoClient.CTInfo(TAG, "Turn on from the not-recording prompt for ${prompt.designator}")
        NotCapturingStreams.turnOn()
        dismiss(prompt)
    }

    /** "Not now": capture stays off for this publish, like Confirm on the strip; the marker stays. */
    fun notNow(prompt: RecordingPrompt) {
        if (prompt.offersTurnOn) NotCapturingStreams.confirm(NotCapturingStream(prompt.key, prompt.designator))
        else CaltopoClient.CTInfo(TAG, "Not-recording reason seen for ${prompt.designator}: ${prompt.reason}")
        dismiss(prompt)
    }

    fun dismiss(prompt: RecordingPrompt) {
        _prompts.value = _prompts.value.filterNot { it == prompt }
    }

    internal fun resetForTests() {
        _prompts.value = emptyList()
        policy.evaluate(emptyList())
    }
}
