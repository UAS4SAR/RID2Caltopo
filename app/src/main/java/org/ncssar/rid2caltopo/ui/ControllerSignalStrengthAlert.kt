package org.ncssar.rid2caltopo.ui

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.ncssar.rid2caltopo.video.StreamState

data class ControllerSignalStrengthAlertUiState(
    val alertInstanceId: Long,
    val signalPercent: Int,
    val liveStreamCount: Int,
)

object ControllerSignalStrengthAlertCenter {
    private const val WEAK_SIGNAL_THRESHOLD_PERCENT = 60
    private const val RESTORE_SIGNAL_THRESHOLD_PERCENT = 65
    private const val WEAK_SIGNAL_DEBOUNCE_MS = 4_000L
    private const val REPEAT_COOLDOWN_MS = 30_000L
    private const val SPOKEN_SOURCE_KEY = "stream-controller"

    private val _uiState = MutableStateFlow<ControllerSignalStrengthAlertUiState?>(null)
    val uiState: StateFlow<ControllerSignalStrengthAlertUiState?> = _uiState.asStateFlow()

    private val _lastSignalPercent = MutableStateFlow<Int?>(null)
    /** Latest Wi-Fi percent while live streams exist; used by the alert-bell colour logic. */
    val lastSignalPercent: StateFlow<Int?> = _lastSignalPercent.asStateFlow()

    private var weakSinceMs: Long? = null
    private var lastSpokenAtMs: Long? = null
    private var nextAlertInstanceId = 1L

    fun update(
        liveStreamCount: Int,
        signalPercent: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (liveStreamCount <= 0 || signalPercent == null) {
            _lastSignalPercent.value = null
            clearProblem()
            return
        }

        val clampedSignal = signalPercent.coerceIn(0, 100)
        _lastSignalPercent.value = clampedSignal
        if (clampedSignal >= RESTORE_SIGNAL_THRESHOLD_PERCENT) {
            clearProblem()
            return
        }

        if (clampedSignal >= WEAK_SIGNAL_THRESHOLD_PERCENT) {
            weakSinceMs = null
            _uiState.value = null
            return
        }

        val startedAtMs = weakSinceMs ?: nowMs
        weakSinceMs = startedAtMs
        if (nowMs - startedAtMs < WEAK_SIGNAL_DEBOUNCE_MS) return

        val shouldSpeak = lastSpokenAtMs == null || nowMs - (lastSpokenAtMs ?: nowMs) >= REPEAT_COOLDOWN_MS
        if (shouldSpeak) {
            SpokenWarningCenter.requestWarning(
                kind = SpokenWarningKind.ControllerSignalStrength,
                sourceKey = SPOKEN_SOURCE_KEY,
                nowMs = nowMs,
                cooldownMs = REPEAT_COOLDOWN_MS
            )
            lastSpokenAtMs = nowMs
        }
        _uiState.value = ControllerSignalStrengthAlertUiState(
            alertInstanceId = nextAlertInstanceId++,
            signalPercent = clampedSignal,
            liveStreamCount = liveStreamCount,
        )
    }

    private fun clearProblem() {
        weakSinceMs = null
        _uiState.value = null
    }

    fun resetForTests() {
        _uiState.value = null
        _lastSignalPercent.value = null
        weakSinceMs = null
        lastSpokenAtMs = null
        nextAlertInstanceId = 1L
    }
}

@Composable
fun ControllerSignalStrengthAlertHost() {
    val context = LocalContext.current
    val streams by StreamRegistry.streams.collectAsState()
    val liveStreamCount = streams.values.count { it.state == StreamState.LIVE }

    LaunchedEffect(context, liveStreamCount) {
        while (true) {
            val signalPercent = if (liveStreamCount > 0) {
                readCurrentWifiSignalPercent(context)
            } else {
                null
            }
            ControllerSignalStrengthAlertCenter.update(
                liveStreamCount = liveStreamCount,
                signalPercent = signalPercent
            )
            delay(2_000L)
        }
    }
}

@Suppress("DEPRECATION")
private fun readCurrentWifiSignalPercent(context: Context): Int? {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return null
    val info = wifiManager.connectionInfo ?: return null
    val rssi = info.rssi
    if (rssi <= -127 || rssi >= 0) return null
    return WifiManager.calculateSignalLevel(rssi, 101).coerceIn(0, 100)
}
