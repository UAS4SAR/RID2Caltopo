package org.ncssar.rid2caltopo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One consistent view of the MediaMTX server for the UI. */
data class MediaMTXServerState(
    val isServerRunning: Boolean = false,
    /** Non-empty only after onServerExited(); cleared on next onServerStarted(). */
    val serverExitReason: String = "",
    val version: String = "Unavailable",
    val startedAtMs: Long = 0L,
)

/**
 * MediaMTX server status. A [StateFlow], not Compose snapshot state: it is written from service
 * and native-callback threads, and Compose state created or written there could be read by a
 * composition snapshot taken earlier ("Reading a state that was created after the snapshot was
 * taken"), which crashed the app on 2026-11-18. Compose collects [state].
 */
object MediaMTXStatus {
    private val _state = MutableStateFlow(MediaMTXServerState())
    @JvmStatic val state: StateFlow<MediaMTXServerState> = _state

    val isServerRunning: Boolean get() = _state.value.isServerRunning
    val serverExitReason: String get() = _state.value.serverExitReason
    val version: String get() = _state.value.version
    val startedAtMs: Long get() = _state.value.startedAtMs

    @JvmStatic
    fun onServerStarted(version: String) {
        _state.value = MediaMTXServerState(
            isServerRunning = true,
            serverExitReason = "",
            version = version,
            startedAtMs = android.os.SystemClock.elapsedRealtime(),
        )
    }

    @JvmStatic
    fun onServerExited(exitDescription: String) {
        _state.update { it.copy(isServerRunning = false, serverExitReason = exitDescription) }
    }

    @JvmStatic
    fun waitForExit(maxDelayInMsec: Integer) {
        // no-op for now
    }
}
