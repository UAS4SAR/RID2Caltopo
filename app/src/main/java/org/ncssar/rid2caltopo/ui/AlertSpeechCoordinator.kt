package org.ncssar.rid2caltopo.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.ProximityAlertConsent

/**
 * Decides when the spoken "Proximity" advisory is (re)announced.
 *
 * Shared rule with iOS `RidProximitySpeechSchedule` (R2CCore): announce when a new
 * alert instance becomes active, then repeat every [REPEAT_INTERVAL_MS] while that
 * alert stays active and is not suspended. A suspended, cleared, or disabled alert
 * resets the schedule, so a resumed alert is treated as newly active. The per-pair
 * 30 s cooldown in [SpokenWarningCenter] still applies on top of this schedule.
 */
internal class ProximitySpeechSchedule {
    enum class Announcement { NewInstance, Repeat }

    private var lastInstanceId: Long? = null
    private var lastAnnouncedAtMs: Long? = null

    fun next(
        activeAlertInstanceId: Long?,
        suspended: Boolean,
        enabled: Boolean,
        nowMs: Long,
    ): Announcement? {
        if (!enabled || suspended || activeAlertInstanceId == null) {
            lastInstanceId = null
            lastAnnouncedAtMs = null
            return null
        }
        if (activeAlertInstanceId != lastInstanceId) {
            lastInstanceId = activeAlertInstanceId
            lastAnnouncedAtMs = nowMs
            return Announcement.NewInstance
        }
        val last = lastAnnouncedAtMs ?: return null
        if (nowMs - last < REPEAT_INTERVAL_MS) return null
        lastAnnouncedAtMs = nowMs
        return Announcement.Repeat
    }

    companion object {
        /** Same value as iOS `RidProximitySpeechSchedule.repeatInterval` (30 s). */
        const val REPEAT_INTERVAL_MS = 30_000L
    }
}

/**
 * Issues alert speech, vibration, and toasts from a process-scoped coroutine
 * instead of Compose effects. Compose pauses recomposition while the activity is
 * stopped (display off), so effects keyed on alert state never ran while the
 * display was locked. Started with the UI and stopped on full app shutdown,
 * alongside [ScanningService]/[MediaMTXService].
 */
object AlertSpeechCoordinator {
    private var scope: CoroutineScope? = null
    private val proximitySchedule = ProximitySpeechSchedule()

    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        val appContext = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        SpokenWarningPlayer.start(appContext, newScope)

        // Proximity: new instance immediately, then every 30 s while active.
        newScope.launch {
            ProximityAlertCenter.uiState
                .distinctUntilChangedBy { it?.alertInstanceId }
                .collect { evaluateProximity(appContext, System.currentTimeMillis()) }
        }
        newScope.launch {
            while (isActive) {
                delay(1_000L)
                evaluateProximity(appContext, System.currentTimeMillis())
            }
        }

        // Altitude: every new ComplianceAlertCenter instance (same as the former
        // ComplianceAlertHost effect).
        newScope.launch {
            ComplianceAlertCenter.uiState
                .distinctUntilChangedBy { it?.alertInstanceId }
                .collect { uiState ->
                    uiState ?: return@collect
                    SpokenWarningCenter.requestWarning(
                        kind = SpokenWarningKind.Altitude,
                        sourceKey = uiState.mappedId,
                        nowMs = System.currentTimeMillis(),
                        cooldownMs = 15_000L
                    )
                    vibrateBriefly(appContext)
                    val staleSuffix = if (uiState.staleDem) " (DEM AGL may be stale)" else ""
                    val toastMessage = if (uiState.highSeverity) {
                        "${uiState.mappedId} above ${formatFeet(uiState.thresholdFt)} AGL at ${formatFeet(uiState.aglFt)}$staleSuffix"
                    } else {
                        "${uiState.mappedId} near ${formatFeet(uiState.thresholdFt)} AGL at ${formatFeet(uiState.aglFt)}$staleSuffix"
                    }
                    CaltopoClient.ShowToast(toastMessage)
                }
        }

        // Drone signal loss (same gate as the former DroneSignalLossAlertHost effect).
        val signalLossGate = DroneSignalLossSpokenWarningGate(DroneSignalLossAlertCenter.uiState.value?.flightKey)
        newScope.launch {
            DroneSignalLossAlertCenter.uiState
                .distinctUntilChangedBy { it?.flightKey }
                .collect { alert ->
                    if (!signalLossGate.shouldRequestWarning(alert?.flightKey)) return@collect
                    alert ?: return@collect
                    SpokenWarningCenter.requestSpokenPhrase(
                        kind = SpokenWarningKind.DroneTelemetry,
                        sourceKey = alert.flightKey,
                        phrase = if (alert.bridgeRecentlySeen) "Drone Location Stale" else "Drone Signal Lost",
                        nowMs = System.currentTimeMillis(),
                        cooldownMs = CaltopoClient.LOSS_OF_SIGNAL_TONE_DURATION_SECONDS * 1000L
                    )
                }
        }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        SpokenWarningPlayer.stop()
    }

    private fun evaluateProximity(context: Context, nowMs: Long) {
        val alert = ProximityAlertCenter.uiState.value
        val announcement = proximitySchedule.next(
            activeAlertInstanceId = alert?.alertInstanceId,
            suspended = ProximityAlertCenter.isSuspended.value,
            enabled = ProximityAlertConsent.state.value.enabled,
            nowMs = nowMs,
        ) ?: return
        alert ?: return
        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.Proximity,
            sourceKey = alert.pairKey,
            nowMs = nowMs,
            cooldownMs = ProximitySpeechSchedule.REPEAT_INTERVAL_MS
        )
        if (announcement == ProximitySpeechSchedule.Announcement.NewInstance) {
            vibrateBriefly(context)
        }
    }
}
