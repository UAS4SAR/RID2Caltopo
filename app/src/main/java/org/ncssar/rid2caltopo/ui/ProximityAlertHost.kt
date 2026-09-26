package org.ncssar.rid2caltopo.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.ncssar.rid2caltopo.data.ProximityTelemetry
import org.ncssar.rid2caltopo.data.ProximityAlertConsent

data class ProximityAlertUiState(
    val alertInstanceId: Long,
    val pairKey: String,
    val thresholdFt: Double,
    val highSeverity: Boolean,
    val nearestDroneMappedId: String,
    val farthestDroneMappedId: String,
    val highestDroneMappedId: String,
    val lowestDroneMappedId: String,
    val horizontalSeparationFt: Double,
    val verticalSeparationFt: Double,
    val firstLat: Double,
    val firstLng: Double,
    val secondLat: Double,
    val secondLng: Double,
    val verticalSeparationKnown: Boolean = false,
    val usesProjection: Boolean = false
)

data class ProximityDebugPair(
    val firstMappedId: String,
    val secondMappedId: String,
    val horizontalSeparationFt: Double,
    val verticalSeparationFt: Double,
    val threeDSeparationFt: Double,
    val verticalSeparationKnown: Boolean,
    val alerting: Boolean
)

data class ComplianceAlertCandidate(
    val remoteId: String,
    val mappedId: String,
    val aglFt: Double,
    val thresholdFt: Double,
    val staleDem: Boolean,
    /** Wall-clock time of the position sample from which [aglFt] was derived. */
    val telemetryTimestampMs: Long,
)

data class ComplianceAlertUiState(
    val alertInstanceId: Long,
    val mappedId: String,
    val aglFt: Double,
    val thresholdFt: Double,
    val highSeverity: Boolean,
    val staleDem: Boolean
)

private enum class ComplianceAlertSeverity {
    None,
    Near,
    Over
}

object ComplianceAlertCenter {
    private const val NEAR_LIMIT_RATIO = 0.90
    private const val NEAR_ALERT_COOLDOWN_MS = 30_000L
    private const val OVER_ALERT_COOLDOWN_MS = 15_000L
    internal const val MAX_ALTITUDE_SAMPLE_AGE_MS = 5_000L

    private val _uiState = MutableStateFlow<ComplianceAlertUiState?>(null)
    val uiState: StateFlow<ComplianceAlertUiState?> = _uiState.asStateFlow()

    private var lastSeverity = ComplianceAlertSeverity.None
    private var lastAlertToneAtMs = 0L
    private var nextAlertInstanceId = 1L

    fun updateCandidates(
        candidates: List<ComplianceAlertCandidate>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val bestCandidate = candidates
            .filter {
                isLocalAlertEligible(it.remoteId) &&
                    it.aglFt.isFinite() &&
                    it.thresholdFt > 0.0 &&
                    isFreshAltitudeSample(it.telemetryTimestampMs, nowMs)
            }
            .mapNotNull { candidate ->
                val severity = when {
                    candidate.aglFt >= candidate.thresholdFt -> ComplianceAlertSeverity.Over
                    candidate.aglFt >= candidate.thresholdFt * NEAR_LIMIT_RATIO -> ComplianceAlertSeverity.Near
                    else -> ComplianceAlertSeverity.None
                }
                if (severity == ComplianceAlertSeverity.None) null else candidate to severity
            }
            .maxWithOrNull(
                compareBy<Pair<ComplianceAlertCandidate, ComplianceAlertSeverity>> { it.second.ordinal }
                    .thenBy { it.first.aglFt }
            )

        if (bestCandidate == null) {
            lastSeverity = ComplianceAlertSeverity.None
            lastAlertToneAtMs = 0L
            _uiState.value = null
            return
        }

        val (candidate, severity) = bestCandidate
        val cooldownMs =
            if (severity == ComplianceAlertSeverity.Over) OVER_ALERT_COOLDOWN_MS else NEAR_ALERT_COOLDOWN_MS
        val shouldNotify = severity != lastSeverity || nowMs - lastAlertToneAtMs >= cooldownMs

        lastSeverity = severity

        val currentState = _uiState.value
        val highSeverity = severity == ComplianceAlertSeverity.Over
        if (shouldNotify || currentState == null) {
            _uiState.value = ComplianceAlertUiState(
                alertInstanceId = nextAlertInstanceId++,
                mappedId = candidate.mappedId,
                aglFt = candidate.aglFt,
                thresholdFt = candidate.thresholdFt,
                highSeverity = highSeverity,
                staleDem = candidate.staleDem
            )
            lastAlertToneAtMs = nowMs
        } else if (currentState.mappedId == candidate.mappedId) {
            _uiState.value = currentState.copy(
                aglFt = candidate.aglFt,
                thresholdFt = candidate.thresholdFt,
                highSeverity = highSeverity,
                staleDem = candidate.staleDem
            )
        }
    }

    fun dismissCurrentAlert() {
        _uiState.value = null
    }

    internal fun resetForTests() {
        lastSeverity = ComplianceAlertSeverity.None
        lastAlertToneAtMs = 0L
        nextAlertInstanceId = 1L
        _uiState.value = null
    }

    internal fun isFreshAltitudeSample(telemetryTimestampMs: Long, nowMs: Long): Boolean =
        telemetryTimestampMs > 0L &&
            nowMs >= telemetryTimestampMs &&
            nowMs - telemetryTimestampMs <= MAX_ALTITUDE_SAMPLE_AGE_MS
}

object ProximityAlertCenter {
    private const val MIN_DRONE_MOVE_FT = 1.0
    private const val MAX_PROJECTION_MS = 2_000L
    private const val MIN_STALE_MULTIPLIER = 1_000L
    private const val CLEAR_DELAY_MS = 3_000L
    private const val FT_PER_METER = 3.28084
    private const val METERS_PER_FOOT = 0.3048
    private const val PROXIMITY_UPDATE_SLOW_MS = 250L

    private data class DroneSample(
        val remoteId: String,
        val mappedId: String,
        val lat: Double,
        val lng: Double,
        val wallTimeMs: Long,
        val horizontalAccuracyMeters: Double
    )

    private data class DroneInput(
        val remoteId: String,
        val mappedId: String,
        val lastLat: Double,
        val lastLng: Double,
        val lastAlt: Double,
        val mostRecentMsecTimestamp: Long,
        val localArchiveOnly: Boolean,
        val telemetry: ProximityTelemetry,
        val locallyConfirmed: Boolean
    )

    private data class ProximityUpdateRequest(
        val drones: List<DroneInput>,
        val submittedAtMs: Long
    )

    private data class EvaluatedDrone(
        val remoteId: String,
        val mappedId: String,
        val teamDrone: Boolean,
        val localAlertEligible: Boolean,
        val currentLat: Double,
        val currentLng: Double,
        val currentAltFt: Double,
        val effectiveLat: Double,
        val effectiveLng: Double,
        val effectiveAltFt: Double,
        val distanceToDeviceFt: Double?,
        val telemetry: ProximityTelemetry,
        val ageSeconds: Double,
        val horizontalUncertaintyFt: Double,
        val projectedUncertaintyFt: Double,
        val projectionSeconds: Double
    )

    private data class PairSnapshot(
        val effectiveHorizontalFt: Double,
        val effectiveVerticalFt: Double,
        val effectiveThreeDFt: Double
    )

    internal data class PairThresholdDecision(
        val insideThreshold: Boolean,
        val crossedIntoThreshold: Boolean,
        val isGettingFartherApart: Boolean,
        val predictedCloser: Boolean,
        val actuallyApproaching: Boolean,
        val highSeverity: Boolean,
        val shouldAlert: Boolean,
        val severityScore: Double
    )

    private data class PairEvaluation(
        val pairKey: String,
        val first: EvaluatedDrone,
        val second: EvaluatedDrone,
        val effectiveHorizontalFt: Double,
        val effectiveVerticalFt: Double,
        val effectiveThreeDFt: Double,
        val currentHorizontalFt: Double,
        val currentVerticalFt: Double,
        val currentThreeDFt: Double,
        val altitudeSensitive: Boolean,
        val decisionHorizontalFt: Double,
        val decisionVerticalFt: Double,
        val usesProjection: Boolean,
        val shouldAlert: Boolean,
        val isGettingFartherApart: Boolean,
        val highSeverity: Boolean,
        val severityScore: Double
    )

    private val _uiState = MutableStateFlow<ProximityAlertUiState?>(null)
    val uiState: StateFlow<ProximityAlertUiState?> = _uiState.asStateFlow()
    private val _suspendedAlert = MutableStateFlow<ProximityAlertUiState?>(null)
    val suspendedAlert: StateFlow<ProximityAlertUiState?> = _suspendedAlert.asStateFlow()
    private val _canResumeAlert = MutableStateFlow(false)
    val canResumeAlert: StateFlow<Boolean> = _canResumeAlert.asStateFlow()
    private val _debugPairs = MutableStateFlow<List<ProximityDebugPair>>(emptyList())
    val debugPairs: StateFlow<List<ProximityDebugPair>> = _debugPairs.asStateFlow()

    private val stateLock = Any()
    private val pendingUpdate = AtomicReference<ProximityUpdateRequest?>(null)
    private var latestRequest: ProximityUpdateRequest? = null
    internal var evaluationTimeForTests: Long? = null
    private val _stalePositionCount = MutableStateFlow(0)
    val stalePositionCount = _stalePositionCount.asStateFlow()
    @Volatile private var periodicEvaluationEnabled = true
    private val workerScheduled = AtomicBoolean(false)
    @Volatile
    private var evaluationExecutor: Executor = createDefaultEvaluationExecutor()

    private val sampleHistoryByRemoteId = linkedMapOf<String, ArrayDeque<DroneSample>>()
    private val previousPairSnapshots = linkedMapOf<String, PairSnapshot>()
    private var latestEvaluationsByKey = emptyMap<String, PairEvaluation>()
    private val _isSuspended = MutableStateFlow(false)
    val isSuspended = _isSuspended.asStateFlow()
    private var alertsSuspended: Boolean
        get() = _isSuspended.value
        set(value) { _isSuspended.value = value }
    private var clearEligibleSinceMs: Long? = null

    private fun logUpdateIfSlow(
        elapsedMs: Long,
        inputCount: Int,
        activeCount: Int,
        evaluationCount: Int
    ) {
        if (elapsedMs < PROXIMITY_UPDATE_SLOW_MS) return
        CaltopoClient.CTWarn(
            "ProximityAlertCenter",
            String.format(
                Locale.US,
                "updateDrones slow elapsedMs=%d input=%d active=%d pairs=%d thread=%s",
                elapsedMs,
                inputCount,
                activeCount,
                evaluationCount,
                Thread.currentThread().name
            )
        )
    }

    fun updateDrones(drones: List<CtDroneSpec>) {
        val request = ProximityUpdateRequest(
            // Some callers supply the retained identity table, others the active list.
            // Apply the same flight membership rule before counting stale positions.
            drones = drones.filter { it.isActive }.map { spec ->
                val position = spec.proximityPosition
                DroneInput(
                    remoteId = spec.remoteId,
                    mappedId = spec.mappedId,
                    lastLat = position?.latitude ?: spec.lastLat,
                    lastLng = position?.longitude ?: spec.lastLng,
                    lastAlt = position?.telemetry?.absoluteAltitudeMeters ?: 0.0,
                    mostRecentMsecTimestamp = position?.receivedAtMillis ?: spec.mostRecentMsecTimestamp,
                    localArchiveOnly = spec.isLocalArchiveOnly,
                    telemetry = position?.telemetry ?: ProximityTelemetry(),
                    locallyConfirmed = spec.isCurrentFlightConfirmed
                )
            },
            submittedAtMs = System.currentTimeMillis()
        )
        pendingUpdate.set(request)
        if (workerScheduled.compareAndSet(false, true)) {
            evaluationExecutor.execute(::drainPendingUpdates)
        }
    }

    private fun drainPendingUpdates() {
        try {
            while (true) {
                val request = pendingUpdate.getAndSet(null) ?: break
                updateDronesOnWorker(request)
            }
        } catch (throwable: Throwable) {
            CaltopoClient.CTWarn(
                "ProximityAlertCenter",
                "updateDrones worker failed: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
            )
        } finally {
            workerScheduled.set(false)
            if (pendingUpdate.get() != null && workerScheduled.compareAndSet(false, true)) {
                evaluationExecutor.execute(::drainPendingUpdates)
            }
        }
    }

    private fun updateDronesOnWorker(request: ProximityUpdateRequest, nowMs: Long = evaluationTimeForTests ?: System.currentTimeMillis()) = synchronized(stateLock) {
        latestRequest = request
        val startedAtMs = request.submittedAtMs
        val drones = request.drones
        val thresholdFt = CaltopoClient.GetProximityAlertSpacingFeet().toDouble()
        if (!ProximityAlertConsent.state.value.enabled || thresholdFt <= 0.0) {
            sampleHistoryByRemoteId.clear()
            previousPairSnapshots.clear()
            alertsSuspended = false
            clearEligibleSinceMs = null
            _uiState.value = null
            _suspendedAlert.value = null
            _canResumeAlert.value = false
            _debugPairs.value = emptyList()
            latestEvaluationsByKey = emptyMap()
            logUpdateIfSlow(
                elapsedMs = System.currentTimeMillis() - startedAtMs,
                inputCount = drones.size,
                activeCount = 0,
                evaluationCount = 0
            )
            return
        }

        _stalePositionCount.value = if (drones.size < 2) 0 else drones.count {
            nowMs - it.mostRecentMsecTimestamp !in 0..ProximityTelemetry.MAX_POSITION_AGE_MS
        }
        val activeDrones = drones.filter { spec ->
            nowMs - spec.mostRecentMsecTimestamp in 0..ProximityTelemetry.MAX_POSITION_AGE_MS &&
                spec.lastLat.isFinite() &&
                spec.lastLng.isFinite() &&
                !(spec.lastLat == 0.0 && spec.lastLng == 0.0)
        }

        val activeRemoteIds = activeDrones.mapTo(linkedSetOf()) { it.remoteId }
        sampleHistoryByRemoteId.keys.toList().forEach { remoteId ->
            if (remoteId !in activeRemoteIds) sampleHistoryByRemoteId.remove(remoteId)
        }

        activeDrones.forEach { spec ->
            val history = sampleHistoryByRemoteId.getOrPut(spec.remoteId) { ArrayDeque() }
            val latest = history.lastOrNull()
            if (latest == null || latest.wallTimeMs != spec.mostRecentMsecTimestamp) {
                history.addLast(
                    DroneSample(
                        remoteId = spec.remoteId,
                        mappedId = spec.mappedId,
                        lat = spec.lastLat,
                        lng = spec.lastLng,
                        wallTimeMs = spec.mostRecentMsecTimestamp,
                        horizontalAccuracyMeters = spec.telemetry.horizontalAccuracyMeters
                    )
                )
                while (history.size > 2) history.removeFirst()
            }
        }

        val predictiveEnabled = false
        val myLocation = CaltopoMap.GetMyLocation()
        val evaluated = activeDrones.map { spec ->
            evaluateDrone(spec, predictiveEnabled, myLocation, nowMs)
        }

        val evaluations = buildList {
            for (i in 0 until evaluated.size) {
                for (j in i + 1 until evaluated.size) {
                    evaluatePair(
                        first = evaluated[i],
                        second = evaluated[j],
                        thresholdFt = thresholdFt,
                        predictionEnabled = predictiveEnabled
                    )?.let(::add)
                }
            }
        }

        val currentEvaluationsByKey = evaluations.associateBy { it.pairKey }
        latestEvaluationsByKey = currentEvaluationsByKey
        _debugPairs.value = evaluations
            .sortedWith(compareBy<PairEvaluation> { it.effectiveThreeDFt }.thenBy { it.pairKey })
            .map { evaluation ->
                ProximityDebugPair(
                    firstMappedId = evaluation.first.mappedId,
                    secondMappedId = evaluation.second.mappedId,
                    horizontalSeparationFt = evaluation.effectiveHorizontalFt,
                    verticalSeparationFt = evaluation.effectiveVerticalFt,
                    threeDSeparationFt = evaluation.effectiveThreeDFt,
                    verticalSeparationKnown = evaluation.altitudeSensitive,
                    alerting = evaluation.shouldAlert
                )
            }
        val bestCandidate = evaluations
            .asSequence()
            .filter { it.shouldAlert }
            .minWithOrNull(
                compareBy<PairEvaluation>({ it.severityScore }, { it.effectiveThreeDFt })
            )

        val activeAlertState = _uiState.value ?: _suspendedAlert.value
        when {
            bestCandidate != null -> {
                val instanceId = if (activeAlertState?.pairKey == bestCandidate.pairKey) {
                    activeAlertState.alertInstanceId
                } else {
                    nowMs
                }
                val alertState = bestCandidate.toUiState(instanceId, thresholdFt)
                if (alertsSuspended) {
                    _uiState.value = null
                    _suspendedAlert.value = alertState
                } else {
                    _uiState.value = alertState
                    _suspendedAlert.value = null
                }
                clearEligibleSinceMs = null
            }

            activeAlertState != null -> {
                val currentEval = currentEvaluationsByKey[activeAlertState.pairKey]
                val clearCondition = currentEval == null || !currentEval.isInsideThreshold(thresholdFt)
                if (clearCondition) {
                    if (clearEligibleSinceMs == null) clearEligibleSinceMs = nowMs
                    if (nowMs - (clearEligibleSinceMs ?: nowMs) >= CLEAR_DELAY_MS) {
                        _uiState.value = null
                        _suspendedAlert.value = null
                        clearEligibleSinceMs = null
                    }
                } else {
                    currentEval?.let { evaluation ->
                        val refreshedState = evaluation.toUiState(activeAlertState.alertInstanceId, thresholdFt)
                        if (alertsSuspended) {
                            _uiState.value = null
                            _suspendedAlert.value = refreshedState
                        } else {
                            _uiState.value = refreshedState
                            _suspendedAlert.value = null
                        }
                    }
                    clearEligibleSinceMs = null
                }
            }

            else -> {
                _suspendedAlert.value = null
            }
        }

        refreshResumeVisibility()

        previousPairSnapshots.clear()
        evaluations.forEach { evaluation ->
            previousPairSnapshots[evaluation.pairKey] = PairSnapshot(
                effectiveHorizontalFt = evaluation.decisionHorizontalFt,
                effectiveVerticalFt = evaluation.decisionVerticalFt,
                effectiveThreeDFt = threeDistanceFt(evaluation.decisionHorizontalFt, evaluation.decisionVerticalFt)
            )
        }
        logUpdateIfSlow(
            elapsedMs = System.currentTimeMillis() - startedAtMs,
            inputCount = drones.size,
            activeCount = activeDrones.size,
            evaluationCount = evaluations.size
        )
    }

    fun suspendCurrentAlert() {
        synchronized(stateLock) {
            alertsSuspended = true
            clearEligibleSinceMs = null
            _suspendedAlert.value = _uiState.value
            _uiState.value = null
            refreshResumeVisibility()
        }
    }

    fun resumeSuspendedAlert() {
        synchronized(stateLock) {
            if (!ProximityAlertConsent.state.value.enabled) return
            alertsSuspended = false
            val suspended = _suspendedAlert.value ?: return
            val currentEval = latestEvaluationsByKey[suspended.pairKey]
            val stillWithinThreshold = currentEval != null && currentEval.isInsideThreshold(suspended.thresholdFt)
            alertsSuspended = false
            if (stillWithinThreshold) {
                _uiState.value = currentEval.toUiState(suspended.alertInstanceId, suspended.thresholdFt)
                _suspendedAlert.value = null
            } else {
                _uiState.value = null
                _suspendedAlert.value = null
            }
            clearEligibleSinceMs = null
            refreshResumeVisibility()
        }
    }

    private fun refreshResumeVisibility() {
        val suspended = _suspendedAlert.value
        _canResumeAlert.value = suspended != null && latestEvaluationsByKey[suspended.pairKey]?.let { evaluation ->
            evaluation.isInsideThreshold(suspended.thresholdFt)
        } == true
    }

    private fun evaluateDrone(
        spec: DroneInput,
        predictiveEnabled: Boolean,
        myLocation: android.location.Location?,
        nowMs: Long
    ): EvaluatedDrone {
        val currentAltFt = if (spec.telemetry.hasUsableAltitude()) spec.lastAlt * FT_PER_METER else 0.0
        val effectiveLat = spec.lastLat
        val effectiveLng = spec.lastLng
        val effectiveAltFt = currentAltFt
        val age = (nowMs - spec.mostRecentMsecTimestamp).coerceAtLeast(0) / 1000.0
        val accuracy = spec.telemetry.horizontalAccuracyMeters.takeIf { it.isFinite() && it > 0 }
            ?: ProximityTelemetry.UNKNOWN_HORIZONTAL_METERS
        val uncertainty = accuracy * FT_PER_METER
        val projectedUncertainty = uncertainty
        val distanceToDeviceFt = myLocation?.let { location ->
            horizontalDistanceFt(effectiveLat, effectiveLng, location.latitude, location.longitude)
        }
        return EvaluatedDrone(
            remoteId = spec.remoteId,
            mappedId = spec.mappedId,
            teamDrone = !spec.localArchiveOnly,
            localAlertEligible = spec.locallyConfirmed && isLocalAlertEligible(spec.remoteId),
            currentLat = spec.lastLat,
            currentLng = spec.lastLng,
            currentAltFt = currentAltFt,
            effectiveLat = effectiveLat,
            effectiveLng = effectiveLng,
            effectiveAltFt = effectiveAltFt,
            distanceToDeviceFt = distanceToDeviceFt,
            telemetry = spec.telemetry,
            ageSeconds = age,
            horizontalUncertaintyFt = uncertainty,
            projectedUncertaintyFt = projectedUncertainty,
            projectionSeconds = 0.0
        )
    }

    private fun evaluatePair(
        first: EvaluatedDrone,
        second: EvaluatedDrone,
        thresholdFt: Double,
        predictionEnabled: Boolean
    ): PairEvaluation? {
        val currentHorizontalFt = horizontalDistanceFt(
            first.currentLat,
            first.currentLng,
            second.currentLat,
            second.currentLng
        )
        val currentVerticalFt = abs(first.currentAltFt - second.currentAltFt)
        val currentThreeDFt = threeDistanceFt(currentHorizontalFt, currentVerticalFt)

        val projectedHorizontalFt = horizontalDistanceFt(
            first.effectiveLat,
            first.effectiveLng,
            second.effectiveLat,
            second.effectiveLng
        )
        val currentLowerBound = (currentHorizontalFt - first.horizontalUncertaintyFt - second.horizontalUncertaintyFt).coerceAtLeast(0.0)
        val projectedLowerBound = (projectedHorizontalFt - first.projectedUncertaintyFt - second.projectedUncertaintyFt).coerceAtLeast(0.0)
        val usesProjection = false
        val effectiveHorizontalFt = if (usesProjection) projectedHorizontalFt else currentHorizontalFt
        val decisionHorizontalFt = min(currentLowerBound, projectedLowerBound)
        val effectiveVerticalFt = currentVerticalFt
        val effectiveThreeDFt = threeDistanceFt(effectiveHorizontalFt, effectiveVerticalFt)

        if (!effectiveHorizontalFt.isFinite() || !effectiveVerticalFt.isFinite()) return null

        val altitudeSensitive = first.teamDrone && second.teamDrone &&
            first.telemetry.hasUsableAltitude() && second.telemetry.hasUsableAltitude() &&
            first.telemetry.altitudeReference == second.telemetry.altitudeReference &&
            first.ageSeconds <= ProximityTelemetry.MAX_ALTITUDE_AGE_SECONDS &&
            second.ageSeconds <= ProximityTelemetry.MAX_ALTITUDE_AGE_SECONDS
        val verticalUncertaintyFt = ((first.telemetry.verticalAccuracyMeters ?: 0.0) +
            (second.telemetry.verticalAccuracyMeters ?: 0.0)) * FT_PER_METER
        val decisionEffectiveVerticalFt = if (altitudeSensitive)
            (currentVerticalFt - verticalUncertaintyFt).coerceAtLeast(0.0) else 0.0
        val decisionCurrentVerticalFt = decisionEffectiveVerticalFt
        val decisionCurrentThreeDFt = threeDistanceFt(currentLowerBound, decisionCurrentVerticalFt)
        val decisionEffectiveThreeDFt = threeDistanceFt(decisionHorizontalFt, decisionEffectiveVerticalFt)
        val pairKey = pairKey(first.remoteId, second.remoteId)
        val previous = previousPairSnapshots[pairKey]
        val decision = evaluateThresholdDecision(
            effectiveHorizontalFt = decisionHorizontalFt,
            effectiveVerticalFt = decisionEffectiveVerticalFt,
            effectiveThreeDFt = decisionEffectiveThreeDFt,
            currentThreeDFt = decisionCurrentThreeDFt,
            thresholdFt = thresholdFt,
            predictionEnabled = predictionEnabled,
            previous = previous
        )
        return PairEvaluation(
            pairKey = pairKey,
            first = first,
            second = second,
            effectiveHorizontalFt = effectiveHorizontalFt,
            effectiveVerticalFt = effectiveVerticalFt,
            effectiveThreeDFt = effectiveThreeDFt,
            currentHorizontalFt = currentHorizontalFt,
            currentVerticalFt = currentVerticalFt,
            currentThreeDFt = currentThreeDFt,
            altitudeSensitive = altitudeSensitive,
            decisionHorizontalFt = decisionHorizontalFt,
            decisionVerticalFt = decisionEffectiveVerticalFt,
            usesProjection = usesProjection,
            shouldAlert = decision.insideThreshold && shouldAlertForPair(first, second),
            isGettingFartherApart = decision.isGettingFartherApart,
            highSeverity = decision.highSeverity,
            severityScore = decision.severityScore
        )
    }

    private fun shouldAlertForPair(first: EvaluatedDrone, second: EvaluatedDrone): Boolean =
        ProximityAlertConsent.alertAllAircraft.value || ((first.teamDrone || second.teamDrone) &&
            (first.localAlertEligible || second.localAlertEligible))

    fun setAlertAllAircraft(value: Boolean) {
        synchronized(stateLock) {
            if (ProximityAlertConsent.alertAllAircraft.value == value) return
            ProximityAlertConsent.setAlertAllAircraft(value)
            SpokenWarningCenter.cancelProximityWarning()
            _uiState.value = null
            _suspendedAlert.value = null
            _canResumeAlert.value = false
            _debugPairs.value = emptyList()
            latestEvaluationsByKey = emptyMap()
            previousPairSnapshots.clear()
            clearEligibleSinceMs = null
            latestRequest?.let { pendingUpdate.set(it.copy(submittedAtMs = System.currentTimeMillis())) }
        }
        if (pendingUpdate.get() != null && workerScheduled.compareAndSet(false, true)) {
            evaluationExecutor.execute(::drainPendingUpdates)
        }
    }

    internal fun shouldAlertForPairForTests(
        firstTeamDrone: Boolean,
        firstLocalAlertEligible: Boolean,
        secondTeamDrone: Boolean,
        secondLocalAlertEligible: Boolean
    ): Boolean =
        (firstTeamDrone || secondTeamDrone) &&
            (firstLocalAlertEligible || secondLocalAlertEligible)

    fun disableAlerts() = synchronized(stateLock) {
        ProximityAlertConsent.disable()
        _stalePositionCount.value = 0
        SpokenWarningCenter.cancelProximityWarning()
        pendingUpdate.set(null)
        _uiState.value = null
        _suspendedAlert.value = null
        _canResumeAlert.value = false
        _debugPairs.value = emptyList()
        sampleHistoryByRemoteId.clear()
        previousPairSnapshots.clear()
        latestEvaluationsByKey = emptyMap()
        alertsSuspended = false
        clearEligibleSinceMs = null
    }

    internal fun resetForTests() {
        latestRequest = null
        _stalePositionCount.value = 0
        pendingUpdate.set(null)
        workerScheduled.set(false)
        synchronized(stateLock) {
            _uiState.value = null
            _suspendedAlert.value = null
            _canResumeAlert.value = false
            _debugPairs.value = emptyList()
            sampleHistoryByRemoteId.clear()
            previousPairSnapshots.clear()
            latestEvaluationsByKey = emptyMap()
            alertsSuspended = false
            clearEligibleSinceMs = null
        }
    }

    internal fun setEvaluationExecutorForTests(executor: Executor) {
        periodicEvaluationEnabled = false
        evaluationExecutor = executor
    }

    internal fun clearEvaluationExecutorForTests() {
        periodicEvaluationEnabled = true
        evaluationTimeForTests = null
        evaluationExecutor = createDefaultEvaluationExecutor()
    }

    private fun evaluateThresholdDecision(
        effectiveHorizontalFt: Double,
        effectiveVerticalFt: Double,
        effectiveThreeDFt: Double,
        currentThreeDFt: Double,
        thresholdFt: Double,
        predictionEnabled: Boolean,
        previous: PairSnapshot?
    ): PairThresholdDecision {
        val insideThreshold =
            effectiveHorizontalFt <= thresholdFt && effectiveVerticalFt <= thresholdFt
        val crossedIntoThreshold = previous == null ||
            previous.effectiveHorizontalFt > thresholdFt ||
            previous.effectiveVerticalFt > thresholdFt
        val isGettingFartherApart = previous != null &&
            effectiveThreeDFt > previous.effectiveThreeDFt + MIN_DRONE_MOVE_FT
        val predictedCloser = effectiveThreeDFt + MIN_DRONE_MOVE_FT < currentThreeDFt
        val actuallyApproaching = previous == null ||
            effectiveThreeDFt + MIN_DRONE_MOVE_FT < previous.effectiveThreeDFt
        val highSeverity =
            effectiveHorizontalFt < thresholdFt * 0.75 || effectiveVerticalFt < thresholdFt * 0.75
        val shouldAlert = if (predictionEnabled) {
            insideThreshold && (predictedCloser || crossedIntoThreshold)
        } else {
            insideThreshold && (actuallyApproaching || crossedIntoThreshold)
        }
        val severityScore = maxOf(
            effectiveHorizontalFt / thresholdFt,
            effectiveVerticalFt / thresholdFt
        )
        return PairThresholdDecision(
            insideThreshold = insideThreshold,
            crossedIntoThreshold = crossedIntoThreshold,
            isGettingFartherApart = isGettingFartherApart,
            predictedCloser = predictedCloser,
            actuallyApproaching = actuallyApproaching,
            highSeverity = highSeverity,
            shouldAlert = shouldAlert,
            severityScore = severityScore
        )
    }

    internal fun evaluateThresholdDecisionForTests(
        effectiveHorizontalFt: Double,
        effectiveVerticalFt: Double,
        effectiveThreeDFt: Double,
        currentThreeDFt: Double,
        thresholdFt: Double,
        predictionEnabled: Boolean,
        altitudeSensitive: Boolean = true,
        previousHorizontalFt: Double? = null,
        previousVerticalFt: Double? = null,
        previousThreeDFt: Double? = null
    ): PairThresholdDecision {
        val decisionEffectiveVerticalFt = if (altitudeSensitive) effectiveVerticalFt else 0.0
        val decisionEffectiveThreeDFt = if (altitudeSensitive) {
            effectiveThreeDFt
        } else {
            threeDistanceFt(effectiveHorizontalFt, decisionEffectiveVerticalFt)
        }
        val decisionCurrentThreeDFt = if (altitudeSensitive) {
            currentThreeDFt
        } else {
            threeDistanceFt(effectiveHorizontalFt, decisionEffectiveVerticalFt)
        }
        val previous = if (
            previousHorizontalFt != null &&
            previousVerticalFt != null &&
            previousThreeDFt != null
        ) {
            PairSnapshot(
                effectiveHorizontalFt = previousHorizontalFt,
                effectiveVerticalFt = previousVerticalFt,
                effectiveThreeDFt = previousThreeDFt
            )
        } else {
            null
        }
        return evaluateThresholdDecision(
            effectiveHorizontalFt = effectiveHorizontalFt,
            effectiveVerticalFt = decisionEffectiveVerticalFt,
            effectiveThreeDFt = decisionEffectiveThreeDFt,
            currentThreeDFt = decisionCurrentThreeDFt,
            thresholdFt = thresholdFt,
            predictionEnabled = predictionEnabled,
            previous = previous
        )
    }

    private fun PairEvaluation.isInsideThreshold(thresholdFt: Double): Boolean =
        decisionHorizontalFt <= thresholdFt && decisionVerticalFt <= thresholdFt

    private fun PairEvaluation.toUiState(alertInstanceId: Long, thresholdFt: Double): ProximityAlertUiState {
        val nearest = listOf(first, second).sortedWith(
            compareBy<EvaluatedDrone>({ it.distanceToDeviceFt ?: Double.MAX_VALUE }, { it.mappedId })
        ).first()
        val farthest = if (nearest.remoteId == first.remoteId) second else first
        val highest = if (first.effectiveAltFt >= second.effectiveAltFt) first else second
        val lowest = if (highest.remoteId == first.remoteId) second else first
        return ProximityAlertUiState(
            alertInstanceId = alertInstanceId,
            pairKey = pairKey,
            thresholdFt = thresholdFt,
            highSeverity = highSeverity,
            nearestDroneMappedId = nearest.mappedId,
            farthestDroneMappedId = farthest.mappedId,
            highestDroneMappedId = highest.mappedId,
            lowestDroneMappedId = lowest.mappedId,
            horizontalSeparationFt = effectiveHorizontalFt,
            verticalSeparationFt = effectiveVerticalFt,
            firstLat = first.currentLat,
            firstLng = first.currentLng,
            secondLat = second.currentLat,
            secondLng = second.currentLng,
            verticalSeparationKnown = altitudeSensitive,
            usesProjection = usesProjection
        )
    }

    private fun pairKey(firstRemoteId: String, secondRemoteId: String): String {
        return if (firstRemoteId <= secondRemoteId) {
            "$firstRemoteId|$secondRemoteId"
        } else {
            "$secondRemoteId|$firstRemoteId"
        }
    }

    private fun horizontalDistanceFt(
        firstLat: Double,
        firstLng: Double,
        secondLat: Double,
        secondLng: Double
    ): Double {
        // Same spherical geometry as Apple RidGeometry; independent of Android framework stubs.
        val lat1 = Math.toRadians(firstLat)
        val lat2 = Math.toRadians(secondLat)
        val deltaLat = lat2 - lat1
        val deltaLng = Math.toRadians(secondLng - firstLng)
        val a = (sin(deltaLat / 2) * sin(deltaLat / 2) + cos(lat1) * cos(lat2) *
            sin(deltaLng / 2) * sin(deltaLng / 2)).coerceIn(0.0, 1.0)
        return 6_371_008.8 * 2 * atan2(sqrt(a), sqrt(1 - a)) * FT_PER_METER
    }

    private fun threeDistanceFt(horizontalFt: Double, verticalFt: Double): Double =
        sqrt(horizontalFt * horizontalFt + verticalFt * verticalFt)

    private fun destinationPoint(
        startLat: Double,
        startLng: Double,
        bearingDeg: Double,
        distanceM: Double
    ): org.osmdroid.util.GeoPoint {
        val earthRadiusM = 6_371_000.0
        val angularDistance = distanceM / earthRadiusM
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(startLat)
        val lon1 = Math.toRadians(startLng)

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return org.osmdroid.util.GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    internal fun reevaluateForTests(nowMs: Long) {
        latestRequest?.let { updateDronesOnWorker(it, nowMs) }
    }

    // Aging and the clear delay must advance even after the final packet.
    private val freshnessTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "r2c-proximity-freshness").apply { isDaemon = true }
    }.apply {
        scheduleAtFixedRate({
            if (periodicEvaluationEnabled && ProximityAlertConsent.state.value.enabled) {
                synchronized(stateLock) {
                    if (periodicEvaluationEnabled) latestRequest?.let { pendingUpdate.compareAndSet(null, it.copy(submittedAtMs = System.currentTimeMillis())) }
                }
                if (pendingUpdate.get() != null && workerScheduled.compareAndSet(false, true)) evaluationExecutor.execute(::drainPendingUpdates)
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun createDefaultEvaluationExecutor(): Executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "r2c-proximity-alert").apply {
                isDaemon = true
            }
        }
}

private fun isLocalAlertEligible(remoteId: String): Boolean =
    R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.isLocalAlertEligible(remoteId)

@Composable
fun ProximityAlertHost(
    onSuspend: () -> Unit,
    onMap: (ProximityAlertUiState) -> Unit
) {
    val context = LocalContext.current
    val alert by ProximityAlertCenter.uiState.collectAsState()

    LaunchedEffect(alert?.alertInstanceId) {
        if (alert != null && ProximityAlertConsent.state.value.enabled) {
            SpokenWarningCenter.requestWarning(
                kind = SpokenWarningKind.Proximity,
                sourceKey = alert?.pairKey ?: "proximity",
                nowMs = System.currentTimeMillis(),
                cooldownMs = 30_000L
            )
            vibrateBriefly(context)
        }
    }

    alert?.let { uiState ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Proximity Alert") },
            text = { ProximityAlertBody(uiState) },
            confirmButton = {
                TextButton(onClick = { onMap(uiState) }) {
                    Text("Map")
                }
            },
            dismissButton = {
                TextButton(onClick = onSuspend) {
                    Text("Suspend")
                }
            }
        )
    }
}

@Composable
fun ComplianceAlertHost() {
    val context = LocalContext.current
    val alert by ComplianceAlertCenter.uiState.collectAsState()

    LaunchedEffect(alert?.alertInstanceId) {
        alert?.let { uiState ->
            SpokenWarningCenter.requestWarning(
                kind = SpokenWarningKind.Altitude,
                sourceKey = uiState.mappedId,
                nowMs = System.currentTimeMillis(),
                cooldownMs = 15_000L
            )
            vibrateBriefly(context)
            val staleSuffix = if (uiState.staleDem) " (DEM AGL may be stale)" else ""
            val toastMessage = if (uiState.highSeverity) {
                "${uiState.mappedId} above ${formatFeet(uiState.thresholdFt)} AGL at ${formatFeet(uiState.aglFt)}$staleSuffix"
            } else {
                "${uiState.mappedId} near ${formatFeet(uiState.thresholdFt)} AGL at ${formatFeet(uiState.aglFt)}$staleSuffix"
            }
            CaltopoClient.ShowToast(toastMessage)
        }
    }
}

@Composable
fun ResumeProximityAlertButton(onSettings: (() -> Unit)? = null) {
    val consent by ProximityAlertConsent.state.collectAsState()
    val suspended by ProximityAlertCenter.isSuspended.collectAsState()
    val staleCount by ProximityAlertCenter.stalePositionCount.collectAsState()
    val status = when {
        !consent.enabled -> "Off"
        suspended -> "Suspended"
        staleCount > 0 -> "Unavailable"
        else -> "On"
    }
    if (suspended && consent.enabled || onSettings != null) {
        TextButton(onClick = { if (suspended && consent.enabled) ProximityAlertCenter.resumeSuspendedAlert() else onSettings?.invoke() }) {
            Text("Proximity: $status")
        }
    } else {
        Text("Proximity: $status", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProximityAlertBody(alert: ProximityAlertUiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "${formatFeet(alert.thresholdFt)} base spacing plus position uncertainty." +
                if (alert.verticalSeparationKnown) "" else " Vertical separation unknown.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        ProximityAlertGrid(alert)
    }
}

@Composable
private fun ProximityAlertGrid(alert: ProximityAlertUiState) {
    val orange = Color(0xFFF57C00)
    val red = MaterialTheme.colorScheme.error
    val horizontalColor = if (alert.horizontalSeparationFt < alert.thresholdFt * 0.75) red else orange
    val verticalColor = if (alert.verticalSeparationFt < alert.thresholdFt * 0.75) red else orange
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            GridCell(if (alert.verticalSeparationKnown) alert.highestDroneMappedId else "", emphasis = true)
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GridCell(alert.nearestDroneMappedId, emphasis = true)
            GridCell(
                primary = "${if (alert.usesProjection) "Projected H" else "H"}: ${formatFeet(alert.horizontalSeparationFt)}",
                secondary = if (alert.verticalSeparationKnown) "V: ${formatFeet(alert.verticalSeparationFt)}" else "V: Unknown",
                primaryColor = horizontalColor,
                secondaryColor = verticalColor
            )
            GridCell(alert.farthestDroneMappedId, emphasis = true)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            GridCell(if (alert.verticalSeparationKnown) alert.lowestDroneMappedId else "", emphasis = true)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.GridCell(
    text: String = "",
    emphasis: Boolean = false,
    primary: String? = null,
    secondary: String? = null,
    primaryColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .sizeIn(minHeight = 58.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            primary != null || secondary != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    primary?.let {
                        Text(it, color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                    secondary?.let {
                        Text(it, color = secondaryColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
            text.isNotBlank() -> {
                Text(
                    text = text,
                    fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

private fun formatFeet(value: Double): String =
    String.format(Locale.US, "%.0f ft", value)

private fun vibrateBriefly(context: Context) {
    val effect = VibrationEffect.createOneShot(
        180L,
        VibrationEffect.DEFAULT_AMPLITUDE
    )
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    vibrator?.takeIf { it.hasVibrator() }?.vibrate(effect)
}
