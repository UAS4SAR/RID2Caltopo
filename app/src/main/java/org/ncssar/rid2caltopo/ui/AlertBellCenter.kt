package org.ncssar.rid2caltopo.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.ncssar.rid2caltopo.data.ProximityAlertConsent
import org.opendroneid.android.bluetooth.DroneScoutBridgeMonitor

/**
 * Session alert-bell kinds shown in the top-bar panel. Labels match the
 * operator-facing names; speech kinds map through [SpokenWarningKind.toAlertBellKind].
 */
enum class AlertBellKind(val displayName: String) {
    Proximity("Proximity"),
    Altitude("Altitude"),
    Distance("Distance"),
    DroneSignalLoss("Drone signal loss"),
    BridgeSignalLoss("Bridge signal loss"),
    WifiStrength("WiFi strength"),
    VideoRequest("Video request"),
}

enum class AlertBellColor {
    White,
    Orange,
    Red,
}

/**
 * Pure 80%-approach / active-threshold rules for the alert bell.
 *
 * Semantics:
 * - Altitude: orange ≥ 80% of AGL limit (160 of 200 ft); red ≥ limit.
 *   Map-marker “near” colour remains 90%/180 ft elsewhere.
 * - Distance: orange ≥ 80% of 5280 ft range limit; red ≥ limit.
 * - Proximity: red while actively alerting (or separation ≤ threshold);
 *   orange while within 1.25× threshold (1/0.8) but not yet alerting.
 * - WiFi: red below 60%; orange below 75% (60/0.8) and ≥ 60%.
 * - Bridge: red when monitoring and ping age ≥ 32 s; orange at ≥ 80% of 32 s.
 * - Drone signal loss / Video: red while the active condition is true.
 *
 * Mute is **session-only** (in-memory): it does not survive process restart and
 * is distinct from Settings enable/disable.
 */
object AlertBellThresholdPolicy {
    const val APPROACH_RATIO = 0.80
    const val PROXIMITY_APPROACH_MULTIPLIER = 1.0 / APPROACH_RATIO
    const val ALTITUDE_LIMIT_FT = 200.0
    const val DISTANCE_LIMIT_FT = 5280.0
    const val WIFI_WEAK_PERCENT = 60
    const val BRIDGE_LOSS_SECONDS = 32.0

    fun altitudeColor(aglFt: Double?, limitFt: Double = ALTITUDE_LIMIT_FT): AlertBellColor {
        if (aglFt == null || !aglFt.isFinite() || limitFt <= 0.0) return AlertBellColor.White
        if (aglFt >= limitFt) return AlertBellColor.Red
        if (aglFt >= limitFt * APPROACH_RATIO) return AlertBellColor.Orange
        return AlertBellColor.White
    }

    fun distanceColor(rangeFt: Double?, limitFt: Double = DISTANCE_LIMIT_FT): AlertBellColor {
        if (rangeFt == null || !rangeFt.isFinite() || limitFt <= 0.0) return AlertBellColor.White
        if (rangeFt >= limitFt) return AlertBellColor.Red
        if (rangeFt >= limitFt * APPROACH_RATIO) return AlertBellColor.Orange
        return AlertBellColor.White
    }

    fun proximityColor(
        separationFt: Double?,
        thresholdFt: Double,
        isActivelyAlerting: Boolean,
    ): AlertBellColor {
        // Red is reserved for an engine alert that has actually been spoken.
        // Geometry alone (even inside the minimum) is at most "approaching".
        if (isActivelyAlerting) return AlertBellColor.Red
        if (separationFt == null || !separationFt.isFinite() || thresholdFt <= 0.0) {
            return AlertBellColor.White
        }
        if (separationFt <= thresholdFt * PROXIMITY_APPROACH_MULTIPLIER) return AlertBellColor.Orange
        return AlertBellColor.White
    }

    /** True only when the active alert instance was spoken (red ⇒ audio played). */
    fun proximityAlarmAnnounced(
        activeAlertInstanceId: Long?,
        announcedAlertInstanceId: Long?,
        suspended: Boolean,
    ): Boolean = !suspended && activeAlertInstanceId != null &&
        announcedAlertInstanceId == activeAlertInstanceId

    fun wifiColor(
        signalPercent: Int?,
        weakThresholdPercent: Int = WIFI_WEAK_PERCENT,
    ): AlertBellColor {
        if (signalPercent == null) return AlertBellColor.White
        val clamped = signalPercent.coerceIn(0, 100)
        if (clamped < weakThresholdPercent) return AlertBellColor.Red
        val approachCeiling = kotlin.math.ceil(weakThresholdPercent / APPROACH_RATIO).toInt()
        if (clamped < approachCeiling) return AlertBellColor.Orange
        return AlertBellColor.White
    }

    fun bridgeColor(
        secondsSinceLastPing: Double?,
        monitoringActive: Boolean,
        lossThresholdSeconds: Double = BRIDGE_LOSS_SECONDS,
    ): AlertBellColor {
        if (!monitoringActive || lossThresholdSeconds <= 0.0) return AlertBellColor.White
        val age = secondsSinceLastPing ?: Double.POSITIVE_INFINITY
        if (age >= lossThresholdSeconds) return AlertBellColor.Red
        if (age >= lossThresholdSeconds * APPROACH_RATIO) return AlertBellColor.Orange
        return AlertBellColor.White
    }

    fun droneSignalLossColor(isAlerting: Boolean): AlertBellColor =
        if (isAlerting) AlertBellColor.Red else AlertBellColor.White

    fun videoColor(pendingRequest: Boolean): AlertBellColor =
        if (pendingRequest) AlertBellColor.Red else AlertBellColor.White

    fun worst(vararg colors: AlertBellColor): AlertBellColor =
        colors.maxByOrNull { it.ordinal } ?: AlertBellColor.White
}

data class AlertBellMetrics(
    val proximitySeparationFt: Double? = null,
    val proximityThresholdFt: Double = 0.0,
    val proximityActivelyAlerting: Boolean = false,
    val maxAglFt: Double? = null,
    val maxRangeFt: Double? = null,
    val droneSignalLossActive: Boolean = false,
    val bridgeSecondsSinceLastPing: Double? = null,
    val bridgeMonitoringActive: Boolean = false,
    val wifiSignalPercent: Int? = null,
    val videoRequestPending: Boolean = false,
) {
    fun colors(): Map<AlertBellKind, AlertBellColor> = mapOf(
        AlertBellKind.Proximity to AlertBellThresholdPolicy.proximityColor(
            proximitySeparationFt,
            proximityThresholdFt,
            proximityActivelyAlerting,
        ),
        AlertBellKind.Altitude to AlertBellThresholdPolicy.altitudeColor(maxAglFt),
        AlertBellKind.Distance to AlertBellThresholdPolicy.distanceColor(maxRangeFt),
        AlertBellKind.DroneSignalLoss to AlertBellThresholdPolicy.droneSignalLossColor(
            droneSignalLossActive,
        ),
        AlertBellKind.BridgeSignalLoss to AlertBellThresholdPolicy.bridgeColor(
            bridgeSecondsSinceLastPing,
            bridgeMonitoringActive,
        ),
        AlertBellKind.WifiStrength to AlertBellThresholdPolicy.wifiColor(wifiSignalPercent),
        AlertBellKind.VideoRequest to AlertBellThresholdPolicy.videoColor(videoRequestPending),
    )
}

data class AlertBellUiState(
    val muted: Set<AlertBellKind> = emptySet(),
    val playedCounts: Map<AlertBellKind, Int> = emptyMap(),
    val lastPlayedAtMs: Map<AlertBellKind, Long> = emptyMap(),
    /** Latched only by audible playback starting this process/session. */
    val hasEverAlarmed: Boolean = false,
    val colors: Map<AlertBellKind, AlertBellColor> =
        AlertBellKind.entries.associateWith { AlertBellColor.White },
) {
    val showBell: Boolean get() = hasEverAlarmed
    val aggregateColor: AlertBellColor
        get() = colors.values.maxByOrNull { it.ordinal } ?: AlertBellColor.White

    fun isMuted(kind: AlertBellKind): Boolean = kind in muted
}

fun SpokenWarningKind.toAlertBellKind(): AlertBellKind? = when (this) {
    SpokenWarningKind.Proximity -> AlertBellKind.Proximity
    SpokenWarningKind.Altitude -> AlertBellKind.Altitude
    SpokenWarningKind.DroneTelemetry -> AlertBellKind.DroneSignalLoss
    SpokenWarningKind.BridgeNotDetected -> AlertBellKind.BridgeSignalLoss
    SpokenWarningKind.ControllerSignalStrength -> AlertBellKind.WifiStrength
    SpokenWarningKind.VideoStreamRequest -> AlertBellKind.VideoRequest
}

/**
 * Session-scoped mute store and bell colour aggregator. Mutes are **not**
 * persisted across process restarts.
 */
object AlertBellCenter {
    private val _uiState = MutableStateFlow(AlertBellUiState())
    val uiState: StateFlow<AlertBellUiState> = _uiState.asStateFlow()

    /** Optional hook to clear per-drone altitude mutes when the type is unmuted. */
    @Volatile
    var onAltitudeTypeUnmuted: (() -> Unit)? = null

    /** Optional hook to clear per-flight signal-loss mutes when the type is unmuted. */
    @Volatile
    var onDroneSignalLossTypeUnmuted: (() -> Unit)? = null

    fun isMuted(kind: AlertBellKind): Boolean = _uiState.value.isMuted(kind)

    fun isMuted(kind: SpokenWarningKind): Boolean {
        val mapped = kind.toAlertBellKind() ?: return false
        return isMuted(mapped)
    }

    fun setMuted(kind: AlertBellKind, muted: Boolean) {
        _uiState.update { state ->
            val next = if (muted) state.muted + kind else state.muted - kind
            state.copy(muted = next)
        }
        when (kind) {
            AlertBellKind.Proximity -> {
                if (muted) {
                    ProximityAlertCenter.suspendCurrentAlert()
                } else if (ProximityAlertConsent.state.value.enabled) {
                    ProximityAlertCenter.resumeSuspendedAlert()
                }
            }
            AlertBellKind.BridgeSignalLoss -> {
                DroneScoutBridgeMonitor.setAudioMuted(muted)
            }
            AlertBellKind.Altitude -> {
                if (!muted) onAltitudeTypeUnmuted?.invoke()
            }
            AlertBellKind.DroneSignalLoss -> {
                if (!muted) onDroneSignalLossTypeUnmuted?.invoke()
            }
            else -> Unit
        }
    }

    fun toggleMuted(kind: AlertBellKind) {
        setMuted(kind, !isMuted(kind))
    }

    /** Updates mute flags without invoking mute side effects (Suspend / Settings sync). */
    fun reflectExternalMute(kind: AlertBellKind, muted: Boolean) {
        _uiState.update { state ->
            val next = if (muted) state.muted + kind else state.muted - kind
            state.copy(muted = next)
        }
    }

    fun updateMetrics(metrics: AlertBellMetrics) {
        val colors = metrics.colors()
        // Do not latch hasEverAlarmed from ambient red metrics (bridge never
        // seen, weak WiFi, etc.). Visibility latches only via recordPlayback().
        _uiState.update { state ->
            state.copy(colors = colors)
        }
    }

    /** Called only from the audio engine's start callback, once per utterance. */
    fun recordPlayback(kind: AlertBellKind, atMs: Long = System.currentTimeMillis(), audible: Boolean = true) {
        if (!audible || isMuted(kind)) return
        _uiState.update { state ->
            state.copy(hasEverAlarmed = true,
                playedCounts = state.playedCounts + (kind to ((state.playedCounts[kind] ?: 0) + 1)),
                lastPlayedAtMs = state.lastPlayedAtMs + (kind to atMs))
        }
    }

    @Volatile
    private var videoRequestPending: Boolean = false

    fun setVideoRequestPending(pending: Boolean) {
        videoRequestPending = pending
    }

    fun videoRequestPending(): Boolean = videoRequestPending

    fun resetForTests() {
        videoRequestPending = false
        _uiState.value = AlertBellUiState()
        onAltitudeTypeUnmuted = null
        onDroneSignalLossTypeUnmuted = null
    }
}
