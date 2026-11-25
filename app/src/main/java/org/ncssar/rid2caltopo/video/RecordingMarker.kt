package org.ncssar.rid2caltopo.video

/**
 * The always-visible recording marker (Ken, Nov 25 2026: two searches were flown with stream
 * capture off and nothing on screen said so). Red "● REC" only while MediaMTX's recorder is
 * actually writing the stream; amber "NOT RECORDING" otherwise, with the reason. Same rules,
 * text and timings as the iPad's RecordingMarkerPolicy (R2CCore).
 */
enum class RecordingMarkerState { HIDDEN, RECORDING, STARTING, NOT_RECORDING }

enum class NotRecordingReason { CAPTURE_OFF, STORAGE_BLOCKED, ARCHIVE_NOT_WRITABLE, RECORDER_NOT_READY, RECORDER_NOT_STARTED }

data class RecordingMarker(val state: RecordingMarkerState, val reason: NotRecordingReason? = null) {
    val label: String? get() = when (state) {
        RecordingMarkerState.HIDDEN -> null
        RecordingMarkerState.RECORDING -> RecordingMarkerPolicy.REC_LABEL
        RecordingMarkerState.STARTING -> RecordingMarkerPolicy.STARTING_LABEL
        RecordingMarkerState.NOT_RECORDING -> RecordingMarkerPolicy.NOT_RECORDING_LABEL
    }
}

/** What the media server and storage say about recording right now. */
data class RecordingInputs(
    val serverRunning: Boolean = false,
    val captureEnabled: Boolean = true,
    val storageBlocked: Boolean = false,
    val recorderReady: Boolean = false,
    val archiveWritable: Boolean = true,
)

object RecordingMarkerPolicy {
    const val REC_LABEL = "● REC"
    const val STARTING_LABEL = "● REC…"
    const val NOT_RECORDING_LABEL = "NOT RECORDING"
    const val SPOKEN_PHRASE = "Video not recording"
    const val PROMPT_TITLE = "Video is not being recorded"
    const val TURN_ON = "Turn on"
    const val NOT_NOW = "Not now"
    const val OK = "OK"

    /** The recorder normally starts within a second of a publish; wait this long before saying it did not. */
    const val START_GRACE_MS = 10_000L

    /**
     * [waitingForMs]: time since the stream went live or the recorder last stopped, whichever
     * is later (a publisher hand-off restarts the recorder).
     */
    fun marker(live: Boolean, localPlayback: Boolean, recorderActive: Boolean, inputs: RecordingInputs, waitingForMs: Long): RecordingMarker {
        if (!live || localPlayback) return RecordingMarker(RecordingMarkerState.HIDDEN)
        if (recorderActive) return RecordingMarker(RecordingMarkerState.RECORDING)
        val reason = when {
            !inputs.captureEnabled -> NotRecordingReason.CAPTURE_OFF
            inputs.storageBlocked && !inputs.archiveWritable -> NotRecordingReason.ARCHIVE_NOT_WRITABLE
            inputs.storageBlocked -> NotRecordingReason.STORAGE_BLOCKED
            waitingForMs < START_GRACE_MS -> return RecordingMarker(RecordingMarkerState.STARTING)
            !inputs.recorderReady -> NotRecordingReason.RECORDER_NOT_READY
            else -> NotRecordingReason.RECORDER_NOT_STARTED
        }
        return RecordingMarker(RecordingMarkerState.NOT_RECORDING, reason)
    }

    /** Tapping the amber marker: the setting can be turned on in place; anything else is explained. */
    fun tapTurnsOnCapture(marker: RecordingMarker): Boolean =
        marker.state == RecordingMarkerState.NOT_RECORDING && marker.reason == NotRecordingReason.CAPTURE_OFF

    fun reasonText(reason: NotRecordingReason): String = when (reason) {
        NotRecordingReason.CAPTURE_OFF -> "Capture Streams is off, so this video is not being saved."
        NotRecordingReason.STORAGE_BLOCKED -> "Flight storage is full or at its limit, so recording is paused. Free space in Manage Storage."
        NotRecordingReason.ARCHIVE_NOT_WRITABLE -> "The archive folder cannot be written and tablet storage is low, so recording is paused. Free space or choose another folder in Manage Storage."
        NotRecordingReason.RECORDER_NOT_READY -> "Recording storage is not ready, so this video is not being saved. Check Manage Storage."
        NotRecordingReason.RECORDER_NOT_STARTED -> "The media server has not started recording this stream. Close and reopen the stream, or restart the media server."
    }

    fun promptText(designator: String, reason: NotRecordingReason): String = "$designator: ${reasonText(reason)}"
}

/**
 * Decides when a live publish gets the spoken warning and prompt: once per publish (path and
 * publisher connection), as soon as the marker says NOT RECORDING. A stream that started
 * recording is never warned about later in the same publish. Same as the iPad's
 * RecordingConnectWarningPolicy.
 */
class RecordingConnectWarningPolicy {
    data class Stream(val key: String, val designator: String, val marker: RecordingMarker)
    data class Warning(val key: String, val designator: String, val reason: NotRecordingReason)

    private val settled = mutableSetOf<String>()

    fun evaluate(streams: List<Stream>): List<Warning> {
        val liveKeys = streams.filter { it.marker.state != RecordingMarkerState.HIDDEN }.map { it.key }.toSet()
        settled.retainAll(liveKeys)
        val warnings = mutableListOf<Warning>()
        for (stream in streams) {
            if (stream.key in settled) continue
            when (stream.marker.state) {
                RecordingMarkerState.RECORDING -> settled += stream.key
                RecordingMarkerState.NOT_RECORDING -> {
                    settled += stream.key
                    warnings += Warning(stream.key, stream.designator, stream.marker.reason ?: NotRecordingReason.RECORDER_NOT_STARTED)
                }
                RecordingMarkerState.STARTING, RecordingMarkerState.HIDDEN -> Unit
            }
        }
        return warnings
    }
}
