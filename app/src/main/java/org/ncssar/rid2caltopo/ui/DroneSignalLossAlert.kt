package org.ncssar.rid2caltopo.ui

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import org.ncssar.rid2caltopo.video.StreamFlightActivityRegistry
import org.opendroneid.android.bluetooth.DroneScoutBridgeMonitor
import kotlin.math.max

private const val SIGNAL_LOSS_ALERT_TAG = "DroneSignalLossAlert"

private data class DroneSignalLossCandidate(
    val flightKey: String,
    val remoteId: String,
    val mappedId: String,
    val signalIdleMs: Long,
    val thresholdMs: Long,
    val learnedIntervalMs: Long,
    val learnedSamples: Int,
    val newTrackDelayMs: Long,
    val bridgeCheckDistanceFt: Double,
    val outOfRange: Boolean,
    val lossObservedWhileFar: Boolean,
    val hasExceededDistanceThreshold: Boolean,
    val distanceFromTabletFt: Double,
    val distanceFromTakeoffFt: Double?,
    val strongestRssiDbm: Int?,
    val strongestRssiTransport: CtDroneSpec.TransportTypeEnum,
    val bridgeRecentlySeen: Boolean,
)

data class DroneSignalLossAlertUiState(
    val flightKey: String,
    val remoteId: String,
    val mappedId: String,
    val signalIdleMs: Long,
    val distanceFromTabletFt: Double,
    val volumeFraction: Float,
    val bridgeRecentlySeen: Boolean,
)

data class DroneSignalLossFlightUiState(
    val flightKey: String,
    val remoteId: String,
    val mappedId: String,
    val muted: Boolean,
    val alerting: Boolean,
    val signalIdleMs: Long?,
    val distanceFromTabletFt: Double?,
    val bridgeRecentlySeen: Boolean,
)

internal class DroneSignalLossSpokenWarningGate(initialFlightKey: String?) {
    private var observedFlightKey: String? = initialFlightKey

    fun shouldRequestWarning(currentFlightKey: String?): Boolean {
        if (currentFlightKey == observedFlightKey) return false
        observedFlightKey = currentFlightKey
        return currentFlightKey != null
    }
}

private data class FlightMonitorState(
    val hasExceededDistanceThreshold: Boolean = false,
    val lossObservedWhileFar: Boolean = false,
    val alertStartedAtMs: Long? = null,
    val toneLimited: Boolean = false,
    val outOfRangeNotified: Boolean = false
)

object DroneSignalLossAlertCenter : CtDroneSpec.DroneSpecsChangedListener {
    private const val ALERT_IDLE_THRESHOLD_MS = 2_000L
    private const val ALERT_BOOTSTRAP_IDLE_THRESHOLD_MS = 10_000L
    private const val MIN_VOLUME = 0.20f
    private const val MAX_VOLUME = 0.80f

    private val _uiState = MutableStateFlow<DroneSignalLossAlertUiState?>(null)
    val uiState: StateFlow<DroneSignalLossAlertUiState?> = _uiState.asStateFlow()

    private val _flights = MutableStateFlow<List<DroneSignalLossFlightUiState>>(emptyList())
    val flights: StateFlow<List<DroneSignalLossFlightUiState>> = _flights.asStateFlow()

    @Volatile
    private var registered = false
    private var currentAlertFlightKey: String? = null
    private var previouslyLoggedAlertFlightKey: String? = null
    private var lastDroneSpecs: List<CtDroneSpec> = emptyList()
    private val mutedFlightKeys = linkedSetOf<String>()
    private val flightMonitorState = linkedMapOf<String, FlightMonitorState>()

    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            CaltopoClient.AddDroneSpecsChangedListener(this)
            registered = true
        }
    }

    override fun onDroneSpecsChanged(droneSpecs: List<CtDroneSpec>) {
        synchronized(this) {
            lastDroneSpecs = droneSpecs
            recomputeLocked()
        }
    }

    fun setMuted(flightKey: String, muted: Boolean) {
        synchronized(this) {
            if (muted) {
                mutedFlightKeys.add(flightKey)
            } else {
                mutedFlightKeys.remove(flightKey)
            }
            recomputeLocked()
        }
    }

    fun clearAllMutes() {
        synchronized(this) {
            if (mutedFlightKeys.isEmpty()) return
            mutedFlightKeys.clear()
            recomputeLocked()
        }
    }

    private fun recomputeLocked() {
        val activeFlights = linkedMapOf<String, CtDroneSpec>()
        lastDroneSpecs.forEach { spec ->
            if (!isLocalAlertEligible(spec.remoteId)) return@forEach
            val flightKey = spec.flightKey() ?: return@forEach
            activeFlights[flightKey] = spec
        }
        mutedFlightKeys.retainAll(activeFlights.keys)
        flightMonitorState.keys.retainAll(activeFlights.keys)

        val nowMs = System.currentTimeMillis()
        val nowMonotonicMs = System.nanoTime() / 1_000_000L
        val bridgeRecentlySeen = isBridgeRecentlySeen(
            lastSeenMonotonicMs = DroneScoutBridgeMonitor.signal.value?.lastSeenMonotonicMs,
            nowMonotonicMs = nowMonotonicMs,
            freshnessMs = DroneScoutBridgeMonitor.SIGNAL_STALE_AFTER_MS
        )
        val newTrackDelayMs = CaltopoClient.GetNewTrackDelayInSeconds() * 1000L
        val bridgeCheckDistanceFt = CaltopoClient.GetBridgeCheckDistanceFeet().toDouble()
        val outOfRangeDistanceFt = CaltopoClient.OUT_OF_RANGE_DISTANCE_FEET.toDouble()
        val returnToTakeoffDistanceFt = CaltopoClient.RETURN_TO_TAKEOFF_DISTANCE_FEET.toDouble()
        val signalToneDurationMs = CaltopoClient.LOSS_OF_SIGNAL_TONE_DURATION_SECONDS * 1000L
        val tabletLocation = CaltopoMap.GetMyLocation()
        val eligible = if (tabletLocation != null && newTrackDelayMs > ALERT_IDLE_THRESHOLD_MS) {
            activeFlights.values.mapNotNull { spec ->
                if (spec.lastLat == 0.0 || spec.lastLng == 0.0) return@mapNotNull null
                val signalIdleMs = spec.signalIdleTimeInMsec(nowMs)
                val distanceFt = distanceFeetFromTablet(spec, tabletLocation) ?: return@mapNotNull null
                val takeoffDistanceFt = distanceFeetFromTakeoff(spec)
                val oorReferenceDistanceFt = takeoffDistanceFt ?: distanceFt
                val flightKey = spec.flightKey() ?: return@mapNotNull null
                val priorState = flightMonitorState[flightKey] ?: FlightMonitorState()
                val effectiveIdleThresholdMs = effectiveIdleThresholdMs(
                    learnedIntervalMs = spec.learnedSignalIntervalMs,
                    learnedSamples = spec.learnedSignalIntervalSamples,
                    maxTrackDelayMs = newTrackDelayMs
                )
                val trackTelemetryIdleMs = spec.trackTelemetryIdleTimeInMsec(nowMs)
                val pairedSei = StreamFlightActivityRegistry.seiActivityForRemoteId(spec.remoteId)
                val pairedSeiIdleMs = pairedSei.lastSeiActivityAtMs
                    .takeIf { pairedSei.paired && it > 0L }
                    ?.let { (nowMs - it).coerceAtLeast(0L) }
                    ?: Long.MAX_VALUE
                val redundantTelemetryIdleMs = minOf(trackTelemetryIdleMs, pairedSeiIdleMs)
                if (shouldSuppressForRedundantTelemetry(
                        signalIdleMs = signalIdleMs,
                        redundantTelemetryIdleMs = redundantTelemetryIdleMs,
                        thresholdMs = effectiveIdleThresholdMs
                    )
                ) {
                    CTDebug(
                        SIGNAL_LOSS_ALERT_TAG,
                        "Suppressing LOS for redundant telemetry ${spec.mappedId} flightKey=$flightKey " +
                            "signalIdleMs=$signalIdleMs trackTelemetryIdleMs=$trackTelemetryIdleMs " +
                            "pairedSeiIdleMs=${pairedSeiIdleMs.takeUnless { it == Long.MAX_VALUE } ?: "unavailable"} " +
                            "thresholdMs=$effectiveIdleThresholdMs"
                    )
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = priorState.hasExceededDistanceThreshold,
                        lossObservedWhileFar = false,
                        alertStartedAtMs = null,
                        outOfRangeNotified = priorState.outOfRangeNotified && spec.isOutOfRange
                    )
                    return@mapNotNull null
                }
                if (spec.isOutOfRange &&
                    (signalIdleMs <= effectiveIdleThresholdMs || oorReferenceDistanceFt < outOfRangeDistanceFt)
                ) {
                    CTDebug(
                        SIGNAL_LOSS_ALERT_TAG,
                        "Clearing OOR for ${spec.mappedId} flightKey=$flightKey " +
                            "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                            "thresholdMs=$effectiveIdleThresholdMs " +
                            "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"}"
                    )
                    spec.setOutOfRange(false)
                    CaltopoClient.InvalidateTrackAgingSchedule()
                }
                val bridgeVerified = priorState.hasExceededDistanceThreshold ||
                    distanceFt > bridgeCheckDistanceFt ||
                    (takeoffDistanceFt != null && takeoffDistanceFt > bridgeCheckDistanceFt)
                var lossWhileFar = priorState.lossObservedWhileFar ||
                    (bridgeVerified &&
                        signalIdleMs > effectiveIdleThresholdMs &&
                        distanceFt > bridgeCheckDistanceFt &&
                        (takeoffDistanceFt == null || takeoffDistanceFt > returnToTakeoffDistanceFt))
                val returnedToBridge = isReturnedToBridge(
                    bridgeVerified = bridgeVerified,
                    distanceFt = distanceFt,
                    bridgeCheckDistanceFt = bridgeCheckDistanceFt
                )
                val returnedToTakeoff = isReturnedToTakeoff(
                    bridgeVerified = bridgeVerified,
                    takeoffDistanceFt = takeoffDistanceFt,
                    returnToTakeoffDistanceFt = returnToTakeoffDistanceFt
                )
                val stationaryNearBridge = isStationaryNearBridge(
                    bridgeVerified = bridgeVerified,
                    stationaryRidReports = spec.hasStationaryRidReports(),
                    referenceDistanceFt = oorReferenceDistanceFt,
                    bridgeCheckDistanceFt = bridgeCheckDistanceFt
                )
                if (returnedToBridge || returnedToTakeoff || stationaryNearBridge) {
                    if (priorState.lossObservedWhileFar) {
                        CTDebug(
                            SIGNAL_LOSS_ALERT_TAG,
                            "Clearing far-loss latch for ${spec.mappedId} flightKey=$flightKey " +
                                "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                                "thresholdMs=$effectiveIdleThresholdMs " +
                                "bridgeCheckDistanceFt=${"%.1f".format(bridgeCheckDistanceFt)} " +
                                "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"}"
                        )
                    }
                    lossWhileFar = false
                    if (signalIdleMs > effectiveIdleThresholdMs) {
                        CTDebug(
                            SIGNAL_LOSS_ALERT_TAG,
                            "Suppressing LOS for returned/stationary ${spec.mappedId} flightKey=$flightKey " +
                                "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                                "thresholdMs=$effectiveIdleThresholdMs " +
                                "bridgeCheckDistanceFt=${"%.1f".format(bridgeCheckDistanceFt)} " +
                                "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"} " +
                                "returnToTakeoffDistanceFt=${"%.1f".format(returnToTakeoffDistanceFt)} " +
                                "stationaryRidReports=${spec.hasStationaryRidReports()}"
                        )
                    }
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = true,
                        lossObservedWhileFar = false,
                        alertStartedAtMs = null,
                        outOfRangeNotified = priorState.outOfRangeNotified && spec.isOutOfRange
                    )
                    return@mapNotNull null
                }
                val shouldEvaluateForAlertWindow =
                    signalIdleMs > effectiveIdleThresholdMs && signalIdleMs < newTrackDelayMs
                if (!shouldEvaluateForAlertWindow) {
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = bridgeVerified,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = null,
                        outOfRangeNotified = priorState.outOfRangeNotified && spec.isOutOfRange
                    )
                    return@mapNotNull null
                }
                val outOfRangeLoss = oorReferenceDistanceFt >= outOfRangeDistanceFt
                if (outOfRangeLoss && !spec.isOutOfRange) {
                    CTDebug(
                        SIGNAL_LOSS_ALERT_TAG,
                        "Classifying ${spec.mappedId} as OOR flightKey=$flightKey " +
                            "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                            "thresholdMs=$effectiveIdleThresholdMs " +
                            "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"} " +
                            "trackAgeOutMs=$newTrackDelayMs"
                    )
                    spec.setOutOfRange(true)
                    CaltopoClient.InvalidateTrackAgingSchedule()
                }
                if (outOfRangeLoss && priorState.outOfRangeNotified) {
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = bridgeVerified,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = priorState.alertStartedAtMs,
                        toneLimited = true,
                        outOfRangeNotified = true
                    )
                    return@mapNotNull null
                }
                val startedAtMs = priorState.alertStartedAtMs ?: nowMs
                if (signalToneDurationMs > 0L && nowMs - startedAtMs >= signalToneDurationMs) {
                    if (!priorState.toneLimited) {
                        CTDebug(
                            SIGNAL_LOSS_ALERT_TAG,
                            "Stopping LOS tone for ${spec.mappedId} flightKey=$flightKey after " +
                                "${nowMs - startedAtMs} ms playback outOfRange=$outOfRangeLoss"
                        )
                    }
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = bridgeVerified,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = startedAtMs,
                        toneLimited = true,
                        outOfRangeNotified = priorState.outOfRangeNotified || outOfRangeLoss
                    )
                    return@mapNotNull null
                }
                val nextState = FlightMonitorState(
                    hasExceededDistanceThreshold = bridgeVerified,
                    lossObservedWhileFar = lossWhileFar,
                    alertStartedAtMs = startedAtMs,
                    outOfRangeNotified = priorState.outOfRangeNotified
                )
                flightMonitorState[flightKey] = nextState
                val shouldAlertNearTablet = !nextState.hasExceededDistanceThreshold || nextState.lossObservedWhileFar
                if (distanceFt <= bridgeCheckDistanceFt && !shouldAlertNearTablet) {
                    return@mapNotNull null
                }
                DroneSignalLossCandidate(
                    flightKey = flightKey,
                    remoteId = spec.remoteId,
                    mappedId = spec.mappedId,
                    signalIdleMs = signalIdleMs,
                    thresholdMs = effectiveIdleThresholdMs,
                    learnedIntervalMs = spec.learnedSignalIntervalMs,
                    learnedSamples = spec.learnedSignalIntervalSamples,
                    newTrackDelayMs = newTrackDelayMs,
                    bridgeCheckDistanceFt = bridgeCheckDistanceFt,
                    outOfRange = spec.isOutOfRange,
                    lossObservedWhileFar = nextState.lossObservedWhileFar,
                    hasExceededDistanceThreshold = nextState.hasExceededDistanceThreshold,
                    distanceFromTabletFt = distanceFt,
                    distanceFromTakeoffFt = takeoffDistanceFt,
                    strongestRssiDbm = spec.lastRssi.takeIf { it != 0 },
                    strongestRssiTransport = spec.lastRssiTransport,
                    bridgeRecentlySeen = bridgeRecentlySeen,
                )
            }
        } else {
            emptyList()
        }

        val eligibleByKey = eligible.associateBy { it.flightKey }
        val stickyFlights = activeFlights.keys.filter { it in mutedFlightKeys }
        val orderedKeys = linkedSetOf<String>()
        eligible.sortedWith(
            compareByDescending<DroneSignalLossCandidate> { it.signalIdleMs }
                .thenByDescending { it.distanceFromTabletFt }
        ).forEach { orderedKeys.add(it.flightKey) }
        stickyFlights.forEach { orderedKeys.add(it) }

        _flights.value = orderedKeys.mapNotNull { flightKey ->
            val spec = activeFlights[flightKey] ?: return@mapNotNull null
            val candidate = eligibleByKey[flightKey]
            DroneSignalLossFlightUiState(
                flightKey = flightKey,
                remoteId = spec.remoteId,
                mappedId = spec.mappedId,
                muted = flightKey in mutedFlightKeys,
                alerting = candidate != null,
                signalIdleMs = candidate?.signalIdleMs,
                distanceFromTabletFt = candidate?.distanceFromTabletFt,
                bridgeRecentlySeen = candidate?.bridgeRecentlySeen == true
            )
        }

        val chosen = eligible
            .filterNot { it.flightKey in mutedFlightKeys }
            .let { unmutedEligible ->
                unmutedEligible.firstOrNull { it.flightKey == currentAlertFlightKey }
                    ?: unmutedEligible.maxWithOrNull(
                        compareBy<DroneSignalLossCandidate> { it.signalIdleMs }
                            .thenBy { it.distanceFromTabletFt }
                    )
            }

        currentAlertFlightKey = chosen?.flightKey
        if (chosen?.flightKey != previouslyLoggedAlertFlightKey) {
            if (chosen != null) {
                CTDebug(
                    SIGNAL_LOSS_ALERT_TAG,
                    "Alerting ${chosen.mappedId} flightKey=${chosen.flightKey} " +
                        "signalIdleMs=${chosen.signalIdleMs} thresholdMs=${chosen.thresholdMs} " +
                        "learnedIntervalMs=${chosen.learnedIntervalMs} " +
                        "learnedSamples=${chosen.learnedSamples} " +
                        "newTrackDelayMs=${chosen.newTrackDelayMs} " +
                        "bridgeCheckDistanceFt=${"%.1f".format(chosen.bridgeCheckDistanceFt)} " +
                        "distanceFt=${"%.1f".format(chosen.distanceFromTabletFt)} " +
                        "takeoffDistanceFt=${chosen.distanceFromTakeoffFt?.let { "%.1f".format(it) } ?: "unknown"} " +
                        "strongestRssiDbm=${chosen.strongestRssiDbm ?: "unavailable"} " +
                        "strongestRssiTransport=${chosen.strongestRssiTransport} " +
                        "bridgeRecentlySeen=${chosen.bridgeRecentlySeen} " +
                        "outOfRange=${chosen.outOfRange} " +
                        "hasExceededDistance=${chosen.hasExceededDistanceThreshold} " +
                        "lossObservedWhileFar=${chosen.lossObservedWhileFar}"
                )
            } else if (previouslyLoggedAlertFlightKey != null) {
                CTDebug(
                    SIGNAL_LOSS_ALERT_TAG,
                    "Clearing active signal-loss alert for flightKey=$previouslyLoggedAlertFlightKey"
                )
            }
            previouslyLoggedAlertFlightKey = chosen?.flightKey
        }
        _uiState.value = chosen?.toUiState(newTrackDelayMs)
    }

    private fun DroneSignalLossCandidate.toUiState(newTrackDelayMs: Long): DroneSignalLossAlertUiState {
        val rampTargetMs = max(ALERT_IDLE_THRESHOLD_MS, newTrackDelayMs / 2L)
        val volumeFraction = when {
            signalIdleMs >= rampTargetMs -> MAX_VOLUME
            rampTargetMs == ALERT_IDLE_THRESHOLD_MS -> MAX_VOLUME
            else -> {
                val progress = (signalIdleMs - ALERT_IDLE_THRESHOLD_MS).toFloat() /
                    (rampTargetMs - ALERT_IDLE_THRESHOLD_MS).toFloat()
                MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * progress.coerceIn(0f, 1f)
            }
        }
        val adjustedVolumeFraction = (volumeFraction * CaltopoClient.GetAlarmVolumeMultiplier())
            .coerceIn(0f, 1f)
        return DroneSignalLossAlertUiState(
            flightKey = flightKey,
            remoteId = remoteId,
            mappedId = mappedId,
            signalIdleMs = signalIdleMs,
            distanceFromTabletFt = distanceFromTabletFt,
            volumeFraction = adjustedVolumeFraction,
            bridgeRecentlySeen = bridgeRecentlySeen
        )
    }

    private fun CtDroneSpec.flightKey(): String? {
        val startMsec = startMsecTimestamp
        if (!isActive || startMsec <= 0L) return null
        return "$remoteId:$startMsec"
    }

    private fun isLocalAlertEligible(remoteId: String): Boolean =
        R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.isLocalAlertEligible(remoteId)

    private fun effectiveIdleThresholdMs(
        learnedIntervalMs: Long,
        learnedSamples: Int,
        maxTrackDelayMs: Long
    ): Long {
        if (learnedSamples < 2 || learnedIntervalMs <= 0L) {
            return ALERT_BOOTSTRAP_IDLE_THRESHOLD_MS
                .coerceAtMost((maxTrackDelayMs - 1L).coerceAtLeast(ALERT_IDLE_THRESHOLD_MS))
        }
        val dynamicThresholdMs = learnedIntervalMs * 5L / 2L
        return dynamicThresholdMs
            .coerceAtLeast(ALERT_BOOTSTRAP_IDLE_THRESHOLD_MS)
            .coerceAtMost((maxTrackDelayMs - 1L).coerceAtLeast(ALERT_IDLE_THRESHOLD_MS))
    }

    private fun isReturnedToBridge(
        bridgeVerified: Boolean,
        distanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = bridgeVerified && distanceFt <= bridgeCheckDistanceFt

    private fun isReturnedToTakeoff(
        bridgeVerified: Boolean,
        takeoffDistanceFt: Double?,
        returnToTakeoffDistanceFt: Double
    ): Boolean = bridgeVerified &&
        takeoffDistanceFt != null &&
        takeoffDistanceFt <= returnToTakeoffDistanceFt

    private fun isStationaryNearBridge(
        bridgeVerified: Boolean,
        stationaryRidReports: Boolean,
        referenceDistanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = bridgeVerified && stationaryRidReports && referenceDistanceFt <= bridgeCheckDistanceFt

    private fun shouldSuppressForRedundantTelemetry(
        signalIdleMs: Long,
        redundantTelemetryIdleMs: Long,
        thresholdMs: Long
    ): Boolean = signalIdleMs > thresholdMs && redundantTelemetryIdleMs <= thresholdMs

    private fun isBridgeRecentlySeen(
        lastSeenMonotonicMs: Long?,
        nowMonotonicMs: Long,
        freshnessMs: Long
    ): Boolean {
        val lastSeen = lastSeenMonotonicMs ?: return false
        return nowMonotonicMs - lastSeen in 0..freshnessMs
    }

    internal fun isReturnedToBridgeForTests(
        bridgeVerified: Boolean,
        distanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = isReturnedToBridge(bridgeVerified, distanceFt, bridgeCheckDistanceFt)

    internal fun isReturnedToTakeoffForTests(
        bridgeVerified: Boolean,
        takeoffDistanceFt: Double?,
        returnToTakeoffDistanceFt: Double
    ): Boolean = isReturnedToTakeoff(
        bridgeVerified = bridgeVerified,
        takeoffDistanceFt = takeoffDistanceFt,
        returnToTakeoffDistanceFt = returnToTakeoffDistanceFt
    )

    internal fun isStationaryNearBridgeForTests(
        bridgeVerified: Boolean,
        stationaryRidReports: Boolean,
        referenceDistanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = isStationaryNearBridge(
        bridgeVerified = bridgeVerified,
        stationaryRidReports = stationaryRidReports,
        referenceDistanceFt = referenceDistanceFt,
        bridgeCheckDistanceFt = bridgeCheckDistanceFt
    )

    internal fun shouldSuppressForPeerVisibleTelemetryForTests(
        signalIdleMs: Long,
        trackTelemetryIdleMs: Long,
        thresholdMs: Long
    ): Boolean = shouldSuppressForRedundantTelemetry(
        signalIdleMs = signalIdleMs,
        redundantTelemetryIdleMs = trackTelemetryIdleMs,
        thresholdMs = thresholdMs
    )

    internal fun effectiveIdleThresholdMsForTests(
        learnedIntervalMs: Long,
        learnedSamples: Int,
        maxTrackDelayMs: Long
    ): Long = effectiveIdleThresholdMs(learnedIntervalMs, learnedSamples, maxTrackDelayMs)

    internal fun isBridgeRecentlySeenForTests(
        lastSeenMonotonicMs: Long?,
        nowMonotonicMs: Long,
        freshnessMs: Long
    ): Boolean = isBridgeRecentlySeen(lastSeenMonotonicMs, nowMonotonicMs, freshnessMs)

    private fun distanceFeetFromTablet(
        spec: CtDroneSpec,
        tabletLocation: Location
    ): Double? {
        val result = FloatArray(1)
        return try {
            Location.distanceBetween(
                tabletLocation.latitude,
                tabletLocation.longitude,
                spec.lastLat,
                spec.lastLng,
                result
            )
            result[0] * 3.28084
        } catch (_: Exception) {
            null
        }
    }

    private fun distanceFeetFromTakeoff(spec: CtDroneSpec): Double? {
        if (spec.lastLat == 0.0 || spec.lastLng == 0.0) return null
        val originLat: Double
        val originLng: Double
        if (spec.hasHomeLocation()) {
            originLat = spec.homeLat
            originLng = spec.homeLng
        } else if (spec.hasTakeoffLocation()) {
            originLat = spec.takeoffLat
            originLng = spec.takeoffLng
        } else {
            return null
        }
        val result = FloatArray(1)
        return try {
            Location.distanceBetween(
                originLat,
                originLng,
                spec.lastLat,
                spec.lastLng,
                result
            )
            result[0] * 3.28084
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun DroneSignalLossAlertHost() {
    // Speech is issued by AlertSpeechCoordinator so it continues while the
    // display is off.
    LaunchedEffect(Unit) {
        DroneSignalLossAlertCenter.ensureRegistered()
    }
}
