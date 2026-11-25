package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Live recording state for the REC marker: the recorder lifecycle per MediaMTX path (from
 * its log), when each path last went live or lost its recorder, and the media server's
 * recording inputs. Small in-memory state only; safe to read on the UI thread.
 */
object RecordingStatus {
    data class Snapshot(
        val inputs: RecordingInputs = RecordingInputs(),
        val recordingPaths: Set<String> = emptySet(),
        /** Elapsed-realtime ms since which each live path has been waiting for its recorder. */
        val waitingSince: Map<String, Long> = emptyMap(),
    ) {
        fun marker(sourcePath: String, live: Boolean, localPlayback: Boolean, nowMs: Long, archiveWritable: Boolean = inputs.archiveWritable): RecordingMarker {
            val active = sourcePath in recordingPaths
            val since = waitingSince[sourcePath] ?: nowMs
            return RecordingMarkerPolicy.marker(live, localPlayback, active, inputs.copy(archiveWritable = archiveWritable), (nowMs - since).coerceAtLeast(0L))
        }
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    @Volatile var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    fun updateInputs(transform: (RecordingInputs) -> RecordingInputs) {
        _state.update { current ->
            val next = transform(current.inputs)
            if (!next.serverRunning) current.copy(inputs = next, recordingPaths = emptySet()) else current.copy(inputs = next)
        }
    }

    /**
     * A publish (re)starts the wait for its recorder. Stops are not tracked here: a stopped
     * stream hides its marker, and MediaMTX logs "recording stopped" when the path closes.
     */
    fun onStreamStarted(path: String) {
        val now = clock()
        _state.update { it.copy(waitingSince = it.waitingSince + (key(path) to now)) }
    }

    fun onRecorderStarted(path: String) {
        _state.update { it.copy(recordingPaths = it.recordingPaths + key(path)) }
    }

    fun onRecorderStopped(path: String) {
        val now = clock()
        val p = key(path)
        _state.update { it.copy(recordingPaths = it.recordingPaths - p, waitingSince = it.waitingSince + (p to now)) }
    }

    /** A (re)started media server has no recorders yet. */
    fun onServerStarted() {
        val now = clock()
        _state.update { s -> s.copy(recordingPaths = emptySet(), waitingSince = s.waitingSince.mapValues { now }) }
    }

    private fun key(path: String) = path.trim().trim('/')

    internal fun resetForTests() { _state.value = Snapshot() }
}
