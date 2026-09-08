package org.ncssar.rid2caltopo.video.ffmpeg

import android.os.Debug
import android.os.Process
import android.view.Surface
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.DelayedExec
import org.ncssar.rid2caltopo.data.NetworkDiagnostics
import org.ncssar.rid2caltopo.data.CaltopoClient.RegisterDebugTags
import org.ncssar.rid2caltopo.video.UpstreamBoundaryMarker
import org.ncssar.rid2caltopo.video.UpstreamTimingRegistry
import org.ncssar.rid2caltopo.video.anomaly.NativeAnomalyConfig
import org.ncssar.rid2caltopo.video.anomaly.PersonRelevanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max

data class StreamTelemetrySnapshot(
    val sourceTag: String? = null,
    val confidence: Double? = null,
    val sourceTimestampUs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val gimbalPitchDeg: Double? = null,
    val cameraYawDeg: Double? = null,
    val headingDeg: Double? = null,
    val horizontalFovDeg: Double? = null,
    val verticalFovDeg: Double? = null,
    val djiAttitudeAnglesDeg: List<Double> = emptyList(),
    val djiNorthMm: Int? = null,
    val djiEastMm: Int? = null,
    val djiDownMm: Int? = null,
    val latestRemoteId: String? = null,
    val remoteIdCandidates: List<String> = emptyList(),
)

data class TelemetryMergeResult(
    val mergedTelemetry: FfmpegTelemetry,
    val updatedCandidates: LinkedHashSet<String>,
    val addedRemoteId: String? = null,
)

data class StreamRuntimeSnapshot(
    val decodedFrameCount: Long,
    val renderedFrameCount: Long,
    val avgDecodedFps: Double,
    val avgRenderedFps: Double,
    val idlePollCount: Int,
    val lastFrameAgeMs: Long,
    val renderDelayMs: Long?,
    val anomalyAnalyzedFrameCount: Long,
    val anomalyAnnotatedFrameCount: Long,
    val anomalyAvgProcessMs: Double?,
    val anomalyMaxProcessMs: Double?,
    val anomalyLastProcessMs: Double?,
    val localPlaybackRealtimeFactor: Double?,
    val localPlaybackSessionRealtimeFactor: Double?,
    val localPlaybackMediaSpanMs: Long?,
    val localPlaybackWallSpanMs: Long?,
    val registrationHealthyFrameCount: Long,
    val registrationSoftDegradedFrameCount: Long,
    val registrationHardDegradedFrameCount: Long,
    val registrationInvalidFrameCount: Long,
    val rescanFullFrameCount: Long,
    val rescanPartialFrameCount: Long,
    val rescanTargetOnlyFrameCount: Long,
    val rescanAppearanceStrideSkipFrameCount: Long,
    val currentRegistrationHealth: Int,
    val currentRescanMode: Int,
    val currentSourceTimestampUs: Long?,
    val anomalyDebugSummary: String?,
    val adInputQueueDepth: Int,
    val adInputQueueDepthMax: Int,
    val adForwardedWithoutAnalysisCount: Long,
    val adFullQueueDisableCount: Long,
    val adAnalyzedRenderedFrameCount: Long,
    val adBypassedRenderedFrameCount: Long,
    val adRuntimeMode: Int,
    val adWorkerProcessedFrameCount: Long,
    val adWorkerAnnotatedFrameCount: Long,
    val adWorkerOverlayEnqueuedCount: Long,
)

object FfmpegTelemetryReducer {
    fun merge(
        existing: FfmpegTelemetry?,
        incoming: FfmpegTelemetry,
        existingCandidates: Set<String>,
    ): TelemetryMergeResult {
        val merged = FfmpegTelemetry(
            sourceTag = incoming.sourceTag ?: existing?.sourceTag,
            confidence = incoming.confidence ?: existing?.confidence,
            remoteId = incoming.remoteId ?: existing?.remoteId,
            sourceTimestampUs = incoming.sourceTimestampUs ?: existing?.sourceTimestampUs,
            renderLatencyMs = incoming.renderLatencyMs ?: existing?.renderLatencyMs,
            latitude = incoming.latitude ?: existing?.latitude,
            longitude = incoming.longitude ?: existing?.longitude,
            altitudeMeters = incoming.altitudeMeters ?: existing?.altitudeMeters,
            gimbalPitchDeg = incoming.gimbalPitchDeg ?: existing?.gimbalPitchDeg,
            cameraYawDeg = incoming.cameraYawDeg ?: existing?.cameraYawDeg,
            headingDeg = incoming.headingDeg ?: existing?.headingDeg,
            horizontalFovDeg = incoming.horizontalFovDeg ?: existing?.horizontalFovDeg,
            verticalFovDeg = incoming.verticalFovDeg ?: existing?.verticalFovDeg,
            djiAttitudeAnglesDeg = incoming.djiAttitudeAnglesDeg.takeIf { it.size == 9 }
                ?: existing?.djiAttitudeAnglesDeg.orEmpty(),
            djiNorthMm = incoming.djiNorthMm ?: existing?.djiNorthMm,
            djiEastMm = incoming.djiEastMm ?: existing?.djiEastMm,
            djiDownMm = incoming.djiDownMm ?: existing?.djiDownMm,
        )
        val candidates = LinkedHashSet(existingCandidates)
        val addedRemoteId = incoming.remoteId?.takeIf { candidates.add(it) }
        return TelemetryMergeResult(
            mergedTelemetry = merged,
            updatedCandidates = candidates,
            addedRemoteId = addedRemoteId,
        )
    }
}

private enum class RenderSessionPhase {
    STARTING,
    PRIMED,
    LIVE,
    SUSPENDED,
    RETIRING,
}

private enum class RenderSessionHealth {
    HEALTHY,
    STALLED,
    LONG_IDLE,
}

internal fun shouldRecoverRenderedNoProgress(
    isLocalFilePlayback: Boolean,
    renderedFrameCount: Long,
    idlePollCount: Int,
    recoveryPollThreshold: Int,
    hasRecentReaderWait: Boolean,
): Boolean {
    if (isLocalFilePlayback) return false
    return renderedFrameCount > 0L &&
        idlePollCount >= recoveryPollThreshold &&
        !hasRecentReaderWait
}

internal class AuxiliaryRenderSessionTracker {
    private val startingDesignators = mutableSetOf<String>()
    private val sessions = mutableMapOf<Long, String>()

    fun reserve(designator: String): Boolean = startingDesignators.add(designator)

    fun complete(designator: String, sessionId: Long): Boolean {
        startingDesignators.remove(designator)
        if (sessionId <= 0L) return false
        sessions[sessionId] = designator
        return true
    }

    fun isTracked(designator: String, sessionId: Long): Boolean =
        designator in startingDesignators || sessions[sessionId] == designator

    fun remove(sessionId: Long) {
        sessions.remove(sessionId)
    }

    fun sessionIds(): Set<Long> = sessions.keys.toSet()

    fun clear() {
        startingDesignators.clear()
        sessions.clear()
    }
}

private data class UpstreamRepublishMarker(
    val observedAtMs: Long,
    val previousPublisherConnId: String?,
    val publisherConnId: String?,
    val upstreamBoundary: String?,
    val upstreamBoundaryOutcome: String?,
    val upstreamBoundaryObservedAtMs: Long?,
    val upstreamBoundaryRotationSeq: Long?,
    val upstreamBoundaryPublisherConnId: String?,
)

private data class ManagedRenderSession(
    val designator: String,
    val createdAtMs: Long,
    var phase: RenderSessionPhase,
    var phaseChangedAtMs: Long,
    var lastReaderWaitAtMs: Long? = null,
    var lastFrameAtMs: Long? = null,
    var sourceClockOffsetMs: Long? = null,
    var lastSourceTimestampUs: Long? = null,
    var readerWaitWindowStartedAtMs: Long? = null,
    var readerWaitEventCountInWindow: Int = 0,
    var pendingRepublishMarker: UpstreamRepublishMarker? = null,
    var startupRecoveryAttempted: Boolean = false,
    var recoveryFrameStreak: Int = 0,
    var decodedFrameCount: Long = 0L,
    var renderedFrameCount: Long = 0L,
    var lastObservedDecodedFrameCount: Long = 0L,
    var lastObservedRenderedFrameCount: Long = 0L,
    var idlePollCount: Int = 0,
    var lastStartupRecoveryDeferredLogAtMs: Long = 0L,
    var lastStallDiagnosticBucket: Int = -1,
)

private data class RuntimeSnapshotSeed(
    val sessionId: Long,
    val session: ManagedRenderSession,
    val decodedFps: Double,
    val renderedFps: Double,
    val renderDelayMs: Long?,
    val debugEnabled: Boolean,
)

class FfmpegProbeService(
    private val onLocalPlaybackEnded: (String) -> Unit = {},
    private val onLocalPlaybackEof: (String) -> Unit = {},
    private val onLiveFrame: (String, Long) -> Unit = { _, _ -> },
    private val onCameraTelemetry: (String, StreamCameraTelemetrySample?) -> Unit = { _, _ -> },
) {
    private val tag = "FfmpegProbeService"
    private val recentReaderWaitPenaltyMs = 5_000L
    private val readerWaitRecoveryFramesToClear = 3
    private val recoveryFrameContinuityGapMs = 250L
    private val sourceTimestampResetThresholdMs = 1_000L
    private val startupNoFrameRecoveryMs = 12_000L
    private val startupNoFrameAbsoluteRecoveryMs = 45_000L
    private val startupNoFrameDeferralLogMs = 5_000L
    private val noFrameCheckMs = 1_000L
    private val renderedNoProgressRecoveryPolls = 8
    private val renderSessions = mutableMapOf<String, Long>()
    private val suspendedRenderSessions = mutableMapOf<String, Long>()
    private val lastFrameAtMs = mutableMapOf<String, Long>()
    private val lastLiveFrameCallbackAtMs = mutableMapOf<String, Long>()
    private val lastCameraTelemetryCallbackAtMs = mutableMapOf<String, Long>()
    private val lastTelemetryLogAtMs = mutableMapOf<String, Long>()
    private val sourcePathByDesignator = mutableMapOf<String, String>()
    private val renderEnabledDesignators = mutableSetOf<String>()
    private val startingRenderDesignators = mutableSetOf<String>()
    private val telemetryProbeSessions = mutableMapOf<String, Long>()
    private val startingTelemetryProbeDesignators = mutableSetOf<String>()
    private val auxiliaryRenderSessions = AuxiliaryRenderSessionTracker()
    private val activeRenderSurfaceByDesignator = mutableMapOf<String, Surface>()
    private val renderSurfaceCandidatesByDesignator = mutableMapOf<String, MutableList<Surface>>()
    private val retiringSessionIds = mutableSetOf<Long>()
    private val managedRenderSessions = mutableMapOf<Long, ManagedRenderSession>()
    private val telemetryByDesignator = mutableMapOf<String, FfmpegTelemetry>()
    private val localPlaybackEofByDesignator = mutableSetOf<String>()
    private val remoteIdCandidatesByDesignator = mutableMapOf<String, LinkedHashSet<String>>()
    private val renderDelayMsByDesignator = mutableMapOf<String, Long>()
    private val renderDelayBaseMsByDesignator = mutableMapOf<String, Long>()
    private val renderDelayMeasuredAtMsByDesignator = mutableMapOf<String, Long>()
    private val anomalyConfigByDesignator = mutableMapOf<String, NativeAnomalyConfig>()
    private val personRelevanceModeByDesignator = mutableMapOf<String, PersonRelevanceMode>()
    private val cachedAnomalyDebugSummaryBySessionId = mutableMapOf<Long, String?>()
    private val lastAnomalyDebugFetchAtMsBySessionId = mutableMapOf<Long, Long>()
    private val sourceInfoBySessionId = mutableMapOf<Long, FfmpegBridge.VideoSourceInfo>()
    private val sourceInfoFetchPending = mutableSetOf<Long>()
    private val localPlaybackPausedByDesignator = mutableMapOf<String, Boolean>()
    private val pendingRepublishByDesignator = mutableMapOf<String, UpstreamRepublishMarker>()
    private val anomalyPauseReasonByDesignator = mutableMapOf<String, String>()
    private val _renderDelayMsByDesignator = MutableStateFlow<Map<String, Long>>(emptyMap())
    val renderDelayMsByDesignatorFlow: StateFlow<Map<String, Long>> =
        _renderDelayMsByDesignator.asStateFlow()
    private val noFramePoll = DelayedExec()
    // Native teardown can wait on an FFmpeg reader. Keep it off the control lane so a
    // reconnect can bind a surface and start a replacement session immediately.
    private val sessionExecutionLanes = FfmpegSessionExecutionLanes()
    private val stateLock = Any()
    private var lastPerfSampleAtMs = 0L
    private var lastPerfProcessCpuMs = 0L
    private var lastPerfMainThreadCpuNs = 0L
    private var lastPollAtMs = 0L
    @Volatile
    private var closing = false
    private val probeListener: (String, Long, String, FfmpegTelemetry) -> Unit =
        probeListener@{ designator, sessionId, eventType, telemetry ->
        val staleReason = staleSessionReason(designator, sessionId)
        if (staleReason != null && !(staleReason == "retiring" && eventType == "session_stopped")) {
            if (staleReason == "retiring") {
                CTDebug(tag, "Ignoring retiring FFmpeg session event for $designator sessionId=$sessionId type=$eventType")
            } else {
                CTWarn(
                    tag,
                    "Stopping stale FFmpeg session for $designator sessionId=$sessionId type=$eventType reason=$staleReason"
                )
                stopSessionAsync(
                    sessionId,
                    "Stopped stale FFmpeg session for $designator sessionId=$sessionId"
                )
            }
            return@probeListener
        }
        val now = System.currentTimeMillis()
        if (eventType == "telemetry") {
            val telemetryProbeSessionId = synchronized(stateLock) {
                telemetryProbeSessions[designator]
            }
            if (telemetry.sourceTag == "dji-sei-245" &&
                OperationalTelemetryAuthorityPolicy.accepts(
                    sessionId = sessionId,
                    telemetryProbeSessionId = telemetryProbeSessionId,
                )
            ) {
                StreamCameraTelemetryRegistry.update(designator, telemetry, now)
                val sample = StreamCameraTelemetryRegistry.fresh(designator, now)
                val shouldNotify = sample != null && synchronized(stateLock) {
                    val previous = lastCameraTelemetryCallbackAtMs[designator] ?: 0L
                    if (now - previous >= CAMERA_TELEMETRY_UI_INTERVAL_MS) {
                        lastCameraTelemetryCallbackAtMs[designator] = now
                        true
                    } else {
                        false
                    }
                }
                if (shouldNotify) {
                    onCameraTelemetry(designator, sample)
                }
            }
            mergeTelemetry(designator, telemetry)
        }
        when (eventType) {
            "anomaly_paused_overload" -> synchronized(stateLock) {
                anomalyPauseReasonByDesignator[designator] = "overloaded"
            }
            "anomaly_paused_thermal" -> synchronized(stateLock) {
                anomalyPauseReasonByDesignator[designator] = "device hot"
            }
            "anomaly_resumed" -> synchronized(stateLock) {
                anomalyPauseReasonByDesignator.remove(designator)
            }
        }

        if (eventType == "reader_wait_long") {
            var stallDiagnostic: String? = null
            synchronized(stateLock) {
                val session = managedRenderSessions[sessionId]
                session?.let {
                    if (it.readerWaitWindowStartedAtMs == null) {
                        it.readerWaitWindowStartedAtMs = now
                    }
                    it.readerWaitEventCountInWindow += 1
                    it.lastReaderWaitAtMs = now
                    it.recoveryFrameStreak = 0
                    stallDiagnostic = maybeBuildStallDiagnosticLocked(designator, sessionId, it, now)
                }
            }
            stallDiagnostic?.let { CTWarn(tag, it) }
        }
        if (
            eventType == "reader_wait_long" ||
                eventType == "video_packet_gap" ||
                eventType == "decoded_frame_gap"
        ) {
            CTDebug(
                tag,
                "FFmpeg pipeline correlation designator=$designator sessionId=$sessionId event=$eventType " +
                    pipelineCorrelationSummary(sessionId, now)
            )
        }
        when (eventType) {
            "decoder_opened" -> {
                synchronized(stateLock) {
                    managedRenderSessions[sessionId]?.let { session ->
                        setSessionPhaseLocked(
                            session = session,
                            phase = RenderSessionPhase.PRIMED,
                            nowMs = now,
                        )
                    }
                }
            }
            "decoder_open_error" -> handleDecoderOpenError(designator, sessionId)
            "local_playback_eof" -> {
                var notifyEof = false
                synchronized(stateLock) {
                    notifyEof = localPlaybackEofByDesignator.add(designator)
                }
                if (notifyEof) {
                    onLocalPlaybackEof(designator)
                }
            }
            "session_stopped" -> handleSessionStopped(designator, sessionId)
        }
        if (eventType == "frame_decoded") {
            synchronized(stateLock) {
                handleDecodedFrameEventLocked(
                    sessionId = sessionId,
                    now = now,
                    sourceTimestampUs = telemetry.sourceTimestampUs,
                )
            }
        }
        if (eventType == "frame_rendered") {
            val shouldNotifyLiveFrame = synchronized(stateLock) {
                val previous = lastLiveFrameCallbackAtMs[designator] ?: 0L
                if (now - previous >= 1_000L) {
                    lastLiveFrameCallbackAtMs[designator] = now
                    true
                } else {
                    false
                }
            }
            if (shouldNotifyLiveFrame) {
                onLiveFrame(designator, now)
            }
            synchronized(stateLock) {
                handleRenderedFrameEventLocked(
                    designator = designator,
                    sessionId = sessionId,
                    now = now,
                    sourceTimestampUs = telemetry.sourceTimestampUs,
                    renderLatencyMs = telemetry.renderLatencyMs,
                )
            }
        }
        when {
            eventType == "telemetry" -> {
                val shouldLog = synchronized(stateLock) {
                    val previous = lastTelemetryLogAtMs[designator] ?: 0L
                    if (now - previous >= 1_000L) {
                        lastTelemetryLogAtMs[designator] = now
                        true
                    } else {
                        false
                    }
                }
                if (shouldLog) {
                    CTDebug(tag, "FFmpeg telemetry designator=$designator telemetry=$telemetry")
                }
            }

            eventType == "render_lock_failed" -> {
                CTDebug(tag, "FFmpeg event designator=$designator type=$eventType")
            }
        }
        when (eventType) {
            "session_started",
            "session_stopped",
            "decoder_opened",
            "decoder_open_error",
            "decoder_alloc_error",
            "surface_attached",
            "surface_detached" -> {
                val sessionState = synchronized(stateLock) {
                    managedRenderSessions[sessionId]
                }
                CTDebug(
                    tag,
                    "Session lifecycle designator=$designator sessionId=$sessionId event=$eventType " +
                        "phase=${sessionState?.phase?.name?.lowercase()}"
                )
            }
        }
    }
    init {
        RegisterDebugTags(listOf(tag, FfmpegBridge.TAG, FfmpegBridge.NATIVE_TAG))
        FfmpegBridge.addProbeListener(probeListener)
        CTDebug(tag, "FFmpeg bridge backend=${FfmpegBridge.decoderBackend()}")
        if (!FfmpegBridge.isRealDecoderBackend()) {
            CTWarn(tag, "FFmpeg decoder backend is stub. Real video decode is not active yet.")
        }
        noFramePoll.start(this::pollForNoFrameSessions, noFrameCheckMs, noFrameCheckMs)
    }

    fun onStreamBecameLive(designator: String) {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            localPlaybackEofByDesignator.remove(designator)
        }
        CTDebug(
            tag,
            "Stream became live for $designator renderEnabled=${isRenderEnabled(designator)} " +
                upstreamCorrelationSummary(designator, now)
        )
        if (isRenderEnabled(designator) && hasBoundRenderSurface(designator)) {
            ensureRenderSession(designator)
        }
    }

    /**
     * Keep a packet-only reader attached while an upstream publisher is live.
     * This makes DJI SEI telemetry independent of whether Streams UI or a
     * render surface is currently visible.
     */
    fun ensureTelemetryProbeSession(designator: String) {
        val alreadyActiveOrStarting = synchronized(stateLock) {
            telemetryProbeSessions.containsKey(designator) ||
                startingTelemetryProbeDesignators.contains(designator)
        }
        if (alreadyActiveOrStarting) return
        sessionExecutionLanes.executeControl {
            ensureTelemetryProbeSessionOnControlLane(designator)
        }
    }

    private fun ensureTelemetryProbeSessionOnControlLane(designator: String): Long? {
        synchronized(stateLock) {
            telemetryProbeSessions[designator]?.let { return it }
            if (!startingTelemetryProbeDesignators.add(designator)) return null
        }
        val streamPath = synchronized(stateLock) { sourcePathByDesignator[designator] } ?: designator
        val inputUrl = if (streamPath.contains("://")) {
            streamPath
        } else {
            "rtsp://127.0.0.1:8554/$streamPath"
        }
        CTDebug(tag, "Starting packet-only telemetry probe for $designator url=$inputUrl")
        val sessionId = FfmpegBridge.startProbe(designator, inputUrl)
        synchronized(stateLock) {
            startingTelemetryProbeDesignators.remove(designator)
            if (sessionId > 0L) {
                telemetryProbeSessions[designator] = sessionId
            }
        }
        if (sessionId <= 0L) {
            CTWarn(tag, "Unable to start packet-only telemetry probe for $designator")
            return null
        }
        CTDebug(tag, "Started packet-only telemetry probe for $designator sessionId=$sessionId")
        return sessionId
    }

    private fun latestUpstreamBoundary(designator: String): UpstreamBoundaryMarker? {
        return UpstreamTimingRegistry.latestForDesignator(designator)
    }

    private fun upstreamCorrelationSummary(designator: String, observedAtMs: Long): String {
        val marker = latestUpstreamBoundary(designator)
        if (marker == null) {
            return "upstreamBoundary=none"
        }
        val upstreamAgeMs = observedAtMs - marker.observedAtMs
        return "upstreamBoundary=${marker.boundary} upstreamOutcome=${marker.outcome} " +
            "upstreamAgeMs=$upstreamAgeMs upstreamRotationSeq=${marker.rotationSequence} " +
            "upstreamPublisherConnId=${marker.publisherConnId} " +
            "upstreamPreviousPublisherConnId=${marker.previousPublisherConnId} " +
            "upstreamPublisherRotated=${marker.publisherRotated}"
    }

    private fun pipelineCorrelationSummary(sessionId: Long, observedAtMs: Long): String {
        val snapshot = synchronized(stateLock) {
            managedRenderSessions[sessionId]
        } ?: return "session=none"
        val lastFrameAgeMs = snapshot.lastFrameAtMs?.let { observedAtMs - it } ?: -1L
        val lastReaderWaitAgeMs = snapshot.lastReaderWaitAtMs?.let { observedAtMs - it } ?: -1L
        return "phase=${snapshot.phase.name.lowercase()} decodedFrames=${snapshot.decodedFrameCount} " +
            "renderedFrames=${snapshot.renderedFrameCount} idlePolls=${snapshot.idlePollCount} " +
            "lastFrameAgeMs=$lastFrameAgeMs lastReaderWaitAgeMs=$lastReaderWaitAgeMs"
    }

    private fun stallDiagnosticBucket(waitWindowMs: Long): Int {
        return when {
            waitWindowMs >= 25_000L -> 2
            waitWindowMs >= 10_000L -> 1
            waitWindowMs >= 3_000L -> 0
            else -> -1
        }
    }

    private fun maybeBuildStallDiagnosticLocked(
        designator: String,
        sessionId: Long,
        sessionState: ManagedRenderSession,
        nowMs: Long,
    ): String? {
        val waitWindowStartedAt = sessionState.readerWaitWindowStartedAtMs ?: return null
        val waitWindowMs = nowMs - waitWindowStartedAt
        val bucket = stallDiagnosticBucket(waitWindowMs)
        if (bucket < 0 || bucket <= sessionState.lastStallDiagnosticBucket) return null
        sessionState.lastStallDiagnosticBucket = bucket
        val upstreamBoundary = latestUpstreamBoundary(designator)
        val probableCause = when {
            sessionState.phase != RenderSessionPhase.LIVE && sessionState.decodedFrameCount == 0L ->
                "waiting_first_decodable_frame"
            upstreamBoundary?.boundary == "stream_error" &&
                upstreamBoundary.outcome == "deferred_transient_close" ->
                "upstream_transient_close"
            sessionState.decodedFrameCount == sessionState.lastObservedDecodedFrameCount ->
                "upstream_starved_or_encoder_stalled"
            sessionState.renderedFrameCount == sessionState.lastObservedRenderedFrameCount ->
                "render_progress_stalled"
            else -> "intermittent_upstream_gap"
        }
        val renderDelayMs = renderDelayMsByDesignator[designator]
        return "Stall diagnosis designator=$designator sessionId=$sessionId " +
            "probableCause=$probableCause waitWindowMs=$waitWindowMs " +
            "phase=${sessionState.phase.name.lowercase()} decodedFrames=${sessionState.decodedFrameCount} " +
            "renderedFrames=${sessionState.renderedFrameCount} idlePolls=${sessionState.idlePollCount} " +
            "renderDelayMs=${renderDelayMs ?: -1L} upstreamBoundary=${upstreamBoundary?.boundary ?: "none"} " +
            "upstreamOutcome=${upstreamBoundary?.outcome ?: "none"} " +
            "upstreamPublisherConnId=${upstreamBoundary?.publisherConnId} " +
            "upstreamReason=${upstreamBoundary?.reason} " +
            "networkSnapshotId=${NetworkDiagnostics.getCurrentSnapshotId()}"
    }

    private fun setSessionPhaseLocked(
        session: ManagedRenderSession,
        phase: RenderSessionPhase,
        nowMs: Long,
    ) {
        if (session.phase == phase) return
        session.phase = phase
        session.phaseChangedAtMs = nowMs
    }

    fun updateSourcePath(designator: String, sourcePath: String) {
        val normalized = sourcePath.trim().trim('/')
        val resolvedPath = normalized.ifEmpty { designator }
        var changedFrom: String? = null
        synchronized(stateLock) {
            val previous = sourcePathByDesignator.put(designator, resolvedPath)
            if (previous != null && previous != resolvedPath) {
                changedFrom = previous
            }
        }
        changedFrom?.let { previous ->
            CTDebug(tag, "Source path updated for $designator: $previous -> $resolvedPath")
        }
    }

    fun onStreamRepublished(
        designator: String,
        publisherChanged: Boolean = false,
        previousPublisherConnId: String? = null,
        publisherConnId: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val upstreamBoundary = latestUpstreamBoundary(designator)
        val marker = UpstreamRepublishMarker(
            observedAtMs = now,
            previousPublisherConnId = previousPublisherConnId,
            publisherConnId = publisherConnId,
            upstreamBoundary = upstreamBoundary?.boundary,
            upstreamBoundaryOutcome = upstreamBoundary?.outcome,
            upstreamBoundaryObservedAtMs = upstreamBoundary?.observedAtMs,
            upstreamBoundaryRotationSeq = upstreamBoundary?.rotationSequence,
            upstreamBoundaryPublisherConnId = upstreamBoundary?.publisherConnId,
        )
        var activeSessionId: Long? = null
        var keepActiveRender = false
        synchronized(stateLock) {
            val sessionId = renderSessions[designator]
            if (sessionId != null) {
                activeSessionId = sessionId
                managedRenderSessions[sessionId]?.let { session ->
                    session.pendingRepublishMarker = marker
                    keepActiveRender = session.renderedFrameCount > 0L
                }
            } else {
                pendingRepublishByDesignator[designator] = marker
            }
        }
        CTDebug(
            tag,
            "Republish detected for $designator renderEnabled=${isRenderEnabled(designator)} " +
                "publisherChanged=$publisherChanged prevPublisherConnId=$previousPublisherConnId " +
                "publisherConnId=$publisherConnId activeSessionId=$activeSessionId " +
                upstreamCorrelationSummary(designator, now)
        )
        val hasActiveRender = activeSessionId != null
        if (hasActiveRender && keepActiveRender) {
            CTDebug(
                tag,
                "Republish for $designator -> keeping active render session (publisherChanged=$publisherChanged)"
            )
            return
        }
        if (hasActiveRender) {
            CTDebug(
                tag,
                "Republish for $designator -> restarting non-rendering session (publisherChanged=$publisherChanged)"
            )
            activeSessionId?.let { sessionId ->
                synchronized(stateLock) {
                    if (renderSessions[designator] == sessionId) {
                        renderSessions.remove(designator)
                        lastFrameAtMs.remove(designator)
                        clearRenderDelayLocked(designator)
                        managedRenderSessions[sessionId]?.let { session ->
                            setSessionPhaseLocked(
                                session = session,
                                phase = RenderSessionPhase.RETIRING,
                                nowMs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
                stopSessionAsync(
                    sessionId,
                    "Stopped non-rendering FFmpeg render for $designator sessionId=$sessionId during republish"
                )
            }
        }
        CTDebug(tag, "Republish for $designator -> no active render session; ensuring FFmpeg render is started")
        sessionExecutionLanes.executeControl {
            if (isRenderEnabled(designator) && hasBoundRenderSurface(designator)) {
                ensureRenderSession(designator)
            }
        }
    }

    fun onStreamStopped(designator: String) {
        CTDebug(tag, "Stream stopped for $designator; tearing down FFmpeg sessions")
        StreamCameraTelemetryRegistry.clear(designator)
        onCameraTelemetry(designator, null)
        val sessionIds: List<Long>
        synchronized(stateLock) {
            sessionIds = sessionIdsForDesignatorLocked(designator)
            startingRenderDesignators.remove(designator)
            startingTelemetryProbeDesignators.remove(designator)
            localPlaybackEofByDesignator.remove(designator)
            localPlaybackPausedByDesignator.remove(designator)
            lastFrameAtMs.remove(designator)
            lastLiveFrameCallbackAtMs.remove(designator)
            lastCameraTelemetryCallbackAtMs.remove(designator)
            lastTelemetryLogAtMs.remove(designator)
            sourcePathByDesignator.remove(designator)
            renderEnabledDesignators.remove(designator)
            activeRenderSurfaceByDesignator.remove(designator)
            renderSurfaceCandidatesByDesignator.remove(designator)
            telemetryByDesignator.remove(designator)
            remoteIdCandidatesByDesignator.remove(designator)
            clearRenderDelayLocked(designator)
            pendingRepublishByDesignator.remove(designator)
            renderSessions.remove(designator)
            suspendedRenderSessions.remove(designator)
            telemetryProbeSessions.remove(designator)
        }
        sessionIds.forEach { sessionId ->
            stopSessionAsync(sessionId, "Stopped FFmpeg render for $designator sessionId=$sessionId")
        }
    }

    private companion object {
        const val CAMERA_TELEMETRY_UI_INTERVAL_MS = 200L
    }

    fun suspendRender(designator: String) {
        val sessionId = synchronized(stateLock) {
            val activeSessionId = renderSessions.remove(designator) ?: return@synchronized null
            suspendedRenderSessions[designator] = activeSessionId
            lastFrameAtMs.remove(designator)
            clearRenderDelayLocked(designator)
            managedRenderSessions[activeSessionId]?.let { session ->
                setSessionPhaseLocked(
                    session = session,
                    phase = RenderSessionPhase.SUSPENDED,
                    nowMs = System.currentTimeMillis(),
                )
                session.idlePollCount = 0
                session.lastObservedDecodedFrameCount = session.decodedFrameCount
                session.lastObservedRenderedFrameCount = session.renderedFrameCount
                session.lastReaderWaitAtMs = null
                session.readerWaitWindowStartedAtMs = null
                session.readerWaitEventCountInWindow = 0
            }
            activeSessionId
        } ?: return
        CTDebug(tag, "Suspending FFmpeg render for $designator sessionId=$sessionId")
        sessionExecutionLanes.executeControl {
            FfmpegBridge.detachSurface(sessionId)
        }
    }

    fun activeRenderSessionId(designator: String): Long? = synchronized(stateLock) {
        renderSessions[designator] ?: suspendedRenderSessions[designator]
    }

    /**
     * Managed preview and remote playback consume decoded frames without an
     * on-screen StreamTile. Start their decoder even when no render surface is
     * bound so navigating away from Streams cannot strand the media offer.
     */
    fun ensureManagedVideoRenderSession(designator: String) {
        if (!isRenderEnabled(designator)) return
        sessionExecutionLanes.executeControl {
            if (isRenderEnabled(designator)) {
                ensureRenderSession(designator)
            }
        }
    }

    /**
     * Start a file-backed decoder owned by a managed-video consumer rather
     * than by the on-screen stream renderer.
     *
     * Native start emits session_started synchronously. Reserve the
     * designator before entering native code, then register the returned ID,
     * so the global lifecycle listener cannot retire the new decoder as an
     * untracked session in that interval.
     */
    fun startAuxiliaryRenderSession(designator: String, inputUrl: String): Long? {
        synchronized(stateLock) {
            if (!auxiliaryRenderSessions.reserve(designator)) {
                CTWarn(tag, "FFmpeg auxiliary render start already pending for $designator")
                return null
            }
        }
        val sessionId = try {
            FfmpegBridge.startRender(designator, inputUrl)
        } catch (error: RuntimeException) {
            CTWarn(
                tag,
                "Unable to start managed auxiliary FFmpeg render for $designator: " +
                    (error.message ?: error.javaClass.simpleName)
            )
            0L
        }
        synchronized(stateLock) {
            if (!auxiliaryRenderSessions.complete(designator, sessionId)) return null
        }
        CTDebug(tag, "Started managed auxiliary FFmpeg render for $designator sessionId=$sessionId")
        return sessionId
    }

    fun videoSourceInfo(designator: String): FfmpegBridge.VideoSourceInfo? {
        val request = synchronized(stateLock) {
            val sessionId = renderSessions[designator] ?: suspendedRenderSessions[designator]
                ?: return@synchronized null
            Triple(
                sessionId,
                sourceInfoBySessionId[sessionId],
                !closing && sourceInfoFetchPending.add(sessionId),
            )
        } ?: return null
        if (request.third) {
            try {
                sessionExecutionLanes.executeControl {
                    val sourceInfo = try {
                        FfmpegBridge.videoSourceInfo(request.first)
                    } finally {
                        synchronized(stateLock) {
                            sourceInfoFetchPending.remove(request.first)
                        }
                    }
                    if (sourceInfo != null) {
                        synchronized(stateLock) {
                            if (renderSessions[designator] == request.first ||
                                suspendedRenderSessions[designator] == request.first ||
                                managedRenderSessions[request.first]?.designator == designator
                            ) {
                                sourceInfoBySessionId[request.first] = sourceInfo
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                synchronized(stateLock) {
                    sourceInfoFetchPending.remove(request.first)
                }
            }
        }
        return request.second
    }

    private fun sessionIdsForDesignatorLocked(designator: String): List<Long> {
        val activeSessionId = renderSessions[designator]
        val telemetrySessionId = telemetryProbeSessions[designator]
        val managedSessionIds = managedRenderSessions
            .filterValues { it.designator == designator }
            .keys
        return listOfNotNull(activeSessionId, telemetrySessionId)
            .plus(managedSessionIds)
            .distinct()
    }

    private fun collectActiveSessionsForShutdown(): List<Long> {
        return synchronized(stateLock) {
            val snapshot = renderSessions.values
                .plus(suspendedRenderSessions.values)
                .plus(managedRenderSessions.keys)
                .plus(auxiliaryRenderSessions.sessionIds())
                .plus(telemetryProbeSessions.values)
                .distinct()
            renderSessions.clear()
            suspendedRenderSessions.clear()
            startingRenderDesignators.clear()
            telemetryProbeSessions.clear()
            startingTelemetryProbeDesignators.clear()
            lastFrameAtMs.clear()
            lastTelemetryLogAtMs.clear()
            sourcePathByDesignator.clear()
            renderEnabledDesignators.clear()
            activeRenderSurfaceByDesignator.clear()
            renderSurfaceCandidatesByDesignator.clear()
            managedRenderSessions.clear()
            auxiliaryRenderSessions.clear()
            retiringSessionIds.clear()
            sourceInfoBySessionId.clear()
            sourceInfoFetchPending.clear()
            telemetryByDesignator.clear()
            remoteIdCandidatesByDesignator.clear()
            renderDelayMsByDesignator.clear()
            renderDelayBaseMsByDesignator.clear()
            renderDelayMeasuredAtMsByDesignator.clear()
            anomalyConfigByDesignator.clear()
            personRelevanceModeByDesignator.clear()
            pendingRepublishByDesignator.clear()
            _renderDelayMsByDesignator.value = emptyMap()
            snapshot
        }
    }

    fun stopAll() {
        val active = collectActiveSessionsForShutdown()
        noFramePoll.stop()
        active.forEach { sessionId ->
            stopSessionAsync(sessionId, "Stopped FFmpeg sessionId=$sessionId during stopAll()")
        }
    }

    fun close() {
        closing = true
        val active = collectActiveSessionsForShutdown()
        noFramePoll.stop()
        FfmpegBridge.removeProbeListener(probeListener)
        sessionExecutionLanes.shutdownControl()
        sessionExecutionLanes.executeStop {
            active.forEach { sessionId ->
                FfmpegBridge.stop(sessionId)
                CTDebug(tag, "Stopped FFmpeg sessionId=$sessionId during close()")
            }
            sessionExecutionLanes.shutdownStops()
        }
    }

    fun isRenderSupported(designator: String): Boolean {
        if (!BuildConfig.ENABLE_FFMPEG_RENDER) return false
        val configured = BuildConfig.FFMPEG_RENDER_DESIGNATOR.trim()
        return configured.isEmpty() || configured.equals(designator, ignoreCase = true)
    }

    fun setRenderEnabled(designator: String, enabled: Boolean) {
        synchronized(stateLock) {
            if (enabled) {
                if (isRenderSupported(designator)) {
                    renderEnabledDesignators += designator
                }
            } else {
                renderEnabledDesignators -= designator
            }
        }
    }

    fun isRenderEnabled(designator: String): Boolean {
        if (!isRenderSupported(designator)) return false
        return synchronized(stateLock) {
            renderEnabledDesignators.contains(designator)
        }
    }

    fun bindRenderSurface(designator: String, surface: Surface): Boolean {
        if (!isRenderEnabled(designator)) return false
        val existingRenderSessionId = synchronized(stateLock) {
            val candidates = renderSurfaceCandidatesByDesignator.getOrPut(designator) { mutableListOf() }
            candidates.removeAll { it === surface }
            candidates.add(surface)
            activeRenderSurfaceByDesignator[designator] = surface
            renderSessions[designator] ?: suspendedRenderSessions.remove(designator)?.also { sessionId ->
                renderSessions[designator] = sessionId
                managedRenderSessions[sessionId]?.let { session ->
                    setSessionPhaseLocked(
                        session = session,
                        phase = RenderSessionPhase.PRIMED,
                        nowMs = System.currentTimeMillis(),
                    )
                    session.idlePollCount = 0
                    session.lastObservedDecodedFrameCount = session.decodedFrameCount
                    session.lastObservedRenderedFrameCount = session.renderedFrameCount
                    session.lastReaderWaitAtMs = null
                    session.readerWaitWindowStartedAtMs = null
                    session.readerWaitEventCountInWindow = 0
                }
            }
        }
        if (existingRenderSessionId != null) {
            sessionExecutionLanes.executeControl {
                val isCurrentSurface = synchronized(stateLock) {
                    activeRenderSurfaceByDesignator[designator] === surface &&
                        renderSessions[designator] == existingRenderSessionId &&
                        !retiringSessionIds.contains(existingRenderSessionId)
                }
                if (!isCurrentSurface || !surface.isValid) {
                    CTDebug(
                        tag,
                        "Skipping stale render surface attach for $designator " +
                            "sessionId=$existingRenderSessionId surface=${System.identityHashCode(surface)}"
                    )
                    return@executeControl
                }
                CTDebug(
                    tag,
                    "Binding render surface for $designator sessionId=$existingRenderSessionId " +
                        "surface=${System.identityHashCode(surface)}"
                )
                if (!FfmpegBridge.attachSurface(existingRenderSessionId, surface)) {
                    CTWarn(
                        tag,
                        "Unable to bind render surface for $designator sessionId=$existingRenderSessionId"
                    )
                }
            }
            return true
        }
        sessionExecutionLanes.executeControl {
            ensureRenderSession(designator)
        }
        return true
    }

    private fun hasBoundRenderSurface(designator: String): Boolean {
        return synchronized(stateLock) {
            renderSurfaceCandidatesByDesignator[designator]?.isNotEmpty() == true
        }
    }

    private fun staleSessionReason(designator: String, sessionId: Long): String? {
        return synchronized(stateLock) {
            if (retiringSessionIds.contains(sessionId)) return@synchronized "retiring"
            if (sessionId == renderSessions[designator]) return@synchronized null
            if (sessionId == suspendedRenderSessions[designator]) return@synchronized null
            val managed = managedRenderSessions[sessionId]
            if (managed != null && managed.designator == designator) return@synchronized null
            if (auxiliaryRenderSessions.isTracked(designator, sessionId)) return@synchronized null
            if (startingRenderDesignators.contains(designator)) return@synchronized null
            if (telemetryProbeSessions[designator] == sessionId) return@synchronized null
            if (startingTelemetryProbeDesignators.contains(designator)) return@synchronized null
            "untracked"
        }
    }

    fun unbindRenderSurface(designator: String, surface: Surface?) {
        data class UnbindAction(
            val sessionId: Long,
            val requestedSurface: Surface?,
            val detachCurrent: Boolean,
            val fallbackSurface: Surface?,
        )

        val action = synchronized(stateLock) {
            val activeSurface = activeRenderSurfaceByDesignator[designator]
            val candidates = renderSurfaceCandidatesByDesignator[designator] ?: return@synchronized null
            val requestedSurface = surface ?: activeSurface ?: return@synchronized null
            val removed = candidates.removeAll { it === requestedSurface }
            if (!removed) {
                if (surface != null) {
                    CTDebug(
                        tag,
                        "Ignoring stale render surface unbind for $designator " +
                            "activeSurface=${activeSurface?.let { System.identityHashCode(it) }} " +
                            "requestedSurface=${System.identityHashCode(surface)}"
                    )
                }
                return@synchronized null
            }
            val sessionId = renderSessions[designator] ?: suspendedRenderSessions[designator] ?: return@synchronized null
            val detachCurrent = activeSurface === requestedSurface
            val fallbackSurface = when {
                candidates.isEmpty() -> null
                detachCurrent -> candidates.last()
                else -> activeSurface
            }
            if (candidates.isEmpty()) {
                renderSurfaceCandidatesByDesignator.remove(designator)
                activeRenderSurfaceByDesignator.remove(designator)
            } else {
                activeRenderSurfaceByDesignator[designator] = fallbackSurface!!
            }
            UnbindAction(
                sessionId = sessionId,
                requestedSurface = requestedSurface,
                detachCurrent = detachCurrent,
                fallbackSurface = if (detachCurrent) fallbackSurface else null,
            )
        } ?: return

        if (!action.detachCurrent) {
            CTDebug(
                tag,
                "Removed non-active render surface for $designator sessionId=${action.sessionId} " +
                    "surface=${action.requestedSurface?.let { System.identityHashCode(it) }}"
            )
            return
        }

        if (action.fallbackSurface != null) {
            CTDebug(
                tag,
                "Rebinding render surface for $designator sessionId=${action.sessionId} " +
                    "oldSurface=${action.requestedSurface?.let { System.identityHashCode(it) }} " +
                    "newSurface=${System.identityHashCode(action.fallbackSurface)}"
            )
            sessionExecutionLanes.executeControl {
                FfmpegBridge.detachSurface(action.sessionId)
                if (!FfmpegBridge.attachSurface(action.sessionId, action.fallbackSurface)) {
                    CTWarn(
                        tag,
                        "Unable to attach fallback render surface for $designator " +
                            "sessionId=${action.sessionId}"
                    )
                }
            }
            return
        }

        CTDebug(
            tag,
            "Unbinding render surface for $designator sessionId=${action.sessionId} " +
                "surface=${action.requestedSurface?.let { System.identityHashCode(it) }}"
        )
        sessionExecutionLanes.executeControl {
            FfmpegBridge.detachSurface(action.sessionId)
        }
    }

    fun telemetrySnapshot(designator: String): StreamTelemetrySnapshot? {
        val mergedAndCandidates = synchronized(stateLock) {
            val merged = telemetryByDesignator[designator] ?: return null
            merged to remoteIdCandidatesByDesignator[designator]?.toList().orEmpty()
        }
        val merged = mergedAndCandidates.first
        val candidates = mergedAndCandidates.second
        return StreamTelemetrySnapshot(
            sourceTag = merged.sourceTag,
            confidence = merged.confidence,
            sourceTimestampUs = merged.sourceTimestampUs,
            latitude = merged.latitude,
            longitude = merged.longitude,
            altitudeMeters = merged.altitudeMeters,
            gimbalPitchDeg = merged.gimbalPitchDeg,
            cameraYawDeg = merged.cameraYawDeg,
            headingDeg = merged.headingDeg,
            horizontalFovDeg = merged.horizontalFovDeg,
            verticalFovDeg = merged.verticalFovDeg,
            djiAttitudeAnglesDeg = merged.djiAttitudeAnglesDeg,
            djiNorthMm = merged.djiNorthMm,
            djiEastMm = merged.djiEastMm,
            djiDownMm = merged.djiDownMm,
            latestRemoteId = merged.remoteId,
            remoteIdCandidates = candidates,
        )
    }

    fun runtimeSnapshot(designator: String): StreamRuntimeSnapshot? {
        val nowMs = System.currentTimeMillis()
        val seed = synchronized(stateLock) {
            val sessionId = renderSessions[designator] ?: suspendedRenderSessions[designator] ?: return@synchronized null
            val session = managedRenderSessions[sessionId] ?: return@synchronized null
            val elapsedMs = (nowMs - session.createdAtMs).coerceAtLeast(1L)
            val decodedFps = (session.decodedFrameCount.toDouble() * 1000.0) / elapsedMs.toDouble()
            val renderedFps = (session.renderedFrameCount.toDouble() * 1000.0) / elapsedMs.toDouble()
            val debugEnabled = anomalyConfigByDesignator[designator]?.let { config ->
                config.enabled && config.troubleshootingDebug
            } == true
            RuntimeSnapshotSeed(
                sessionId = sessionId,
                session = session.copy(),
                decodedFps = decodedFps,
                renderedFps = renderedFps,
                renderDelayMs = renderDelayMsByDesignator[designator],
                debugEnabled = debugEnabled,
            )
        } ?: return null

        val perfStats = FfmpegBridge.sessionPerfStats(seed.sessionId)
        val anomalyDebugSummary =
            if (!seed.debugEnabled) {
                synchronized(stateLock) {
                    cachedAnomalyDebugSummaryBySessionId.remove(seed.sessionId)
                    lastAnomalyDebugFetchAtMsBySessionId.remove(seed.sessionId)
                }
                null
            } else {
                val shouldRefresh = synchronized(stateLock) {
                    val lastFetchAtMs = lastAnomalyDebugFetchAtMsBySessionId[seed.sessionId] ?: 0L
                    (nowMs - lastFetchAtMs) >= 500L
                }
                if (shouldRefresh) {
                    val refreshed = FfmpegBridge.sessionDebugSummary(seed.sessionId)
                    synchronized(stateLock) {
                        cachedAnomalyDebugSummaryBySessionId[seed.sessionId] = refreshed
                        lastAnomalyDebugFetchAtMsBySessionId[seed.sessionId] = nowMs
                    }
                    refreshed
                } else {
                    synchronized(stateLock) { cachedAnomalyDebugSummaryBySessionId[seed.sessionId] }
                }
            }

        return synchronized(stateLock) {
            val anomalyAnalyzedFrameCount = perfStats?.getOrNull(0) ?: 0L
            val anomalyAnnotatedFrameCount = perfStats?.getOrNull(1) ?: 0L
            val anomalyTotalProcessUs = perfStats?.getOrNull(2) ?: 0L
            val anomalyMaxProcessUs = perfStats?.getOrNull(3) ?: 0L
            val anomalyLastProcessUs = perfStats?.getOrNull(4) ?: 0L
            val localPlaybackFirstPtsUs = perfStats?.getOrNull(5) ?: 0L
            val localPlaybackLastPtsUs = perfStats?.getOrNull(6) ?: 0L
            val localPlaybackFirstRenderAtMs = perfStats?.getOrNull(7) ?: 0L
            val localPlaybackLastRenderAtMs = perfStats?.getOrNull(8) ?: 0L
            val localPlaybackMediaSpanMs =
                ((localPlaybackLastPtsUs - localPlaybackFirstPtsUs) / 1000L).takeIf { it > 0L }
            val localPlaybackWallSpanMs =
                (localPlaybackLastRenderAtMs - localPlaybackFirstRenderAtMs).takeIf { it > 0L }
            val recentPlaybackFirstPtsUs = perfStats?.getOrNull(20) ?: 0L
            val recentPlaybackLastPtsUs = perfStats?.getOrNull(21) ?: 0L
            val recentPlaybackFirstRenderAtMs = perfStats?.getOrNull(22) ?: 0L
            val recentPlaybackLastRenderAtMs = perfStats?.getOrNull(23) ?: 0L
            val recentPlaybackMediaSpanMs =
                ((recentPlaybackLastPtsUs - recentPlaybackFirstPtsUs) / 1000L).takeIf { it > 0L }
            val recentPlaybackWallSpanMs =
                (recentPlaybackLastRenderAtMs - recentPlaybackFirstRenderAtMs).takeIf { it > 0L }
            val registrationHealthyFrameCount = perfStats?.getOrNull(10) ?: 0L
            val registrationSoftDegradedFrameCount = perfStats?.getOrNull(11) ?: 0L
            val registrationHardDegradedFrameCount = perfStats?.getOrNull(12) ?: 0L
            val registrationInvalidFrameCount = perfStats?.getOrNull(13) ?: 0L
            val rescanFullFrameCount = perfStats?.getOrNull(14) ?: 0L
            val rescanPartialFrameCount = perfStats?.getOrNull(15) ?: 0L
            val rescanTargetOnlyFrameCount = perfStats?.getOrNull(16) ?: 0L
            val rescanAppearanceStrideSkipFrameCount = perfStats?.getOrNull(17) ?: 0L
            val currentRegistrationHealth = (perfStats?.getOrNull(18) ?: 0L).toInt()
            val currentRescanMode = (perfStats?.getOrNull(19) ?: 0L).toInt()
            val currentSourceTimestampUs = perfStats?.getOrNull(9)?.takeIf { it > 0L }
            val adInputQueueDepth = (perfStats?.getOrNull(24) ?: 0L).toInt()
            val adInputQueueDepthMax = (perfStats?.getOrNull(25) ?: 0L).toInt()
            val adForwardedWithoutAnalysisCount = perfStats?.getOrNull(26) ?: 0L
            val adFullQueueDisableCount = perfStats?.getOrNull(27) ?: 0L
            val adAnalyzedRenderedFrameCount = perfStats?.getOrNull(28) ?: 0L
            val adBypassedRenderedFrameCount = perfStats?.getOrNull(29) ?: 0L
            val adRuntimeMode = (perfStats?.getOrNull(30) ?: 0L).toInt()
            val adWorkerProcessedFrameCount = perfStats?.getOrNull(31) ?: 0L
            val adWorkerAnnotatedFrameCount = perfStats?.getOrNull(32) ?: 0L
            val adWorkerOverlayEnqueuedCount = perfStats?.getOrNull(33) ?: 0L
            val localPlaybackRealtimeFactor =
                if (recentPlaybackMediaSpanMs != null && recentPlaybackWallSpanMs != null && recentPlaybackWallSpanMs > 0L) {
                    recentPlaybackMediaSpanMs.toDouble() / recentPlaybackWallSpanMs.toDouble()
                } else {
                    null
                }
            val localPlaybackSessionRealtimeFactor =
                if (localPlaybackMediaSpanMs != null && localPlaybackWallSpanMs != null && localPlaybackWallSpanMs > 0L) {
                    localPlaybackMediaSpanMs.toDouble() / localPlaybackWallSpanMs.toDouble()
                } else {
                    null
                }
            StreamRuntimeSnapshot(
                decodedFrameCount = seed.session.decodedFrameCount,
                renderedFrameCount = seed.session.renderedFrameCount,
                avgDecodedFps = seed.decodedFps,
                avgRenderedFps = seed.renderedFps,
                idlePollCount = seed.session.idlePollCount,
                lastFrameAgeMs = seed.session.lastFrameAtMs?.let { nowMs - it } ?: -1L,
                renderDelayMs = seed.renderDelayMs,
                anomalyAnalyzedFrameCount = anomalyAnalyzedFrameCount,
                anomalyAnnotatedFrameCount = anomalyAnnotatedFrameCount,
                anomalyAvgProcessMs =
                    if (anomalyAnalyzedFrameCount > 0L) {
                        anomalyTotalProcessUs.toDouble() / anomalyAnalyzedFrameCount.toDouble() / 1000.0
                    } else {
                        null
                    },
                anomalyMaxProcessMs = anomalyMaxProcessUs.takeIf { it > 0L }?.toDouble()?.div(1000.0),
                anomalyLastProcessMs = anomalyLastProcessUs.takeIf { it > 0L }?.toDouble()?.div(1000.0),
                localPlaybackRealtimeFactor = localPlaybackRealtimeFactor,
                localPlaybackSessionRealtimeFactor = localPlaybackSessionRealtimeFactor,
                localPlaybackMediaSpanMs = localPlaybackMediaSpanMs,
                localPlaybackWallSpanMs = localPlaybackWallSpanMs,
                registrationHealthyFrameCount = registrationHealthyFrameCount,
                registrationSoftDegradedFrameCount = registrationSoftDegradedFrameCount,
                registrationHardDegradedFrameCount = registrationHardDegradedFrameCount,
                registrationInvalidFrameCount = registrationInvalidFrameCount,
                rescanFullFrameCount = rescanFullFrameCount,
                rescanPartialFrameCount = rescanPartialFrameCount,
                rescanTargetOnlyFrameCount = rescanTargetOnlyFrameCount,
                rescanAppearanceStrideSkipFrameCount = rescanAppearanceStrideSkipFrameCount,
                currentRegistrationHealth = currentRegistrationHealth,
                currentRescanMode = currentRescanMode,
                currentSourceTimestampUs = currentSourceTimestampUs,
                anomalyDebugSummary = anomalyDebugSummary,
                adInputQueueDepth = adInputQueueDepth,
                adInputQueueDepthMax = adInputQueueDepthMax,
                adForwardedWithoutAnalysisCount = adForwardedWithoutAnalysisCount,
                adFullQueueDisableCount = adFullQueueDisableCount,
                adAnalyzedRenderedFrameCount = adAnalyzedRenderedFrameCount,
                adBypassedRenderedFrameCount = adBypassedRenderedFrameCount,
                adRuntimeMode = adRuntimeMode,
                adWorkerProcessedFrameCount = adWorkerProcessedFrameCount,
                adWorkerAnnotatedFrameCount = adWorkerAnnotatedFrameCount,
                adWorkerOverlayEnqueuedCount = adWorkerOverlayEnqueuedCount,
            )
        }
    }

    fun anomalyPauseReason(designator: String): String? {
        return synchronized(stateLock) { anomalyPauseReasonByDesignator[designator] }
    }

    fun isLocalPlaybackPaused(designator: String): Boolean {
        return synchronized(stateLock) {
            localPlaybackPausedByDesignator[designator] == true
        }
    }

    fun setLocalPlaybackPaused(designator: String, paused: Boolean) {
        val sessionIds = synchronized(stateLock) {
            localPlaybackPausedByDesignator[designator] = paused
            sessionIdsForDesignatorLocked(designator)
        }
        sessionIds.forEach { sessionId ->
            FfmpegBridge.setLocalPlaybackPaused(sessionId, paused)
        }
    }

    fun stepLocalPlayback(designator: String, frameCount: Int = 1) {
        val sessionIds = synchronized(stateLock) {
            localPlaybackPausedByDesignator[designator] = true
            sessionIdsForDesignatorLocked(designator)
        }
        sessionIds.forEach { sessionId ->
            FfmpegBridge.setLocalPlaybackPaused(sessionId, true)
            FfmpegBridge.stepLocalPlayback(sessionId, frameCount)
        }
    }

    fun stepLocalPlaybackBack(designator: String) {
        val sessionIds = synchronized(stateLock) {
            localPlaybackPausedByDesignator[designator] = true
            sessionIdsForDesignatorLocked(designator)
        }
        sessionIds.forEach { sessionId ->
            FfmpegBridge.setLocalPlaybackPaused(sessionId, true)
            FfmpegBridge.stepLocalPlaybackBack(sessionId)
        }
    }

    fun hasRecentFrame(designator: String, maxAgeMs: Long = 2_500L): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(stateLock) {
            val lastFrameAt = lastFrameAtMs[designator] ?: return@synchronized false
            now - lastFrameAt <= maxAgeMs
        }
    }

    fun setAnomalyConfig(designator: String, config: NativeAnomalyConfig) {
        setAnomalyPolicy(
            designator = designator,
            config = config,
            personRelevanceMode = synchronized(stateLock) {
                personRelevanceModeByDesignator[designator] ?: PersonRelevanceMode.Off
            },
        )
    }

    fun setAnomalyPolicy(
        designator: String,
        config: NativeAnomalyConfig,
        personRelevanceMode: PersonRelevanceMode,
    ) {
        synchronized(stateLock) {
            anomalyConfigByDesignator[designator] = config
            personRelevanceModeByDesignator[designator] = personRelevanceMode
            if (!config.enabled) {
                anomalyPauseReasonByDesignator.remove(designator)
            }
        }
        applyAnomalyConfig(designator)
    }

    fun setAnomalyThermalPaused(designator: String, paused: Boolean) {
        val sessionIds = synchronized(stateLock) {
            if (!paused && anomalyPauseReasonByDesignator[designator] == "device hot") {
                anomalyPauseReasonByDesignator.remove(designator)
            } else if (paused) {
                anomalyPauseReasonByDesignator[designator] = "device hot"
            }
            listOfNotNull(renderSessions[designator]).distinct()
        }
        sessionIds.forEach { sessionId ->
            FfmpegBridge.setAnomalyThermalPaused(sessionId, paused)
        }
    }

    private fun ensureRenderSession(designator: String): Long? {
        synchronized(stateLock) {
            renderSessions[designator]?.let {
                CTDebug(tag, "Reusing FFmpeg render session for $designator sessionId=$it")
                return it
            }
            suspendedRenderSessions.remove(designator)?.let {
                renderSessions[designator] = it
                managedRenderSessions[it]?.let { session ->
                    setSessionPhaseLocked(
                        session = session,
                        phase = RenderSessionPhase.PRIMED,
                        nowMs = System.currentTimeMillis(),
                    )
                    session.idlePollCount = 0
                    session.lastObservedDecodedFrameCount = session.decodedFrameCount
                    session.lastObservedRenderedFrameCount = session.renderedFrameCount
                    session.lastReaderWaitAtMs = null
                    session.readerWaitWindowStartedAtMs = null
                    session.readerWaitEventCountInWindow = 0
                }
                CTDebug(tag, "Resuming suspended FFmpeg render session for $designator sessionId=$it")
                return it
            }
            if (!startingRenderDesignators.add(designator)) {
                CTDebug(tag, "FFmpeg render start already pending for $designator")
                return null
            }
        }
        synchronized(stateLock) {
            lastFrameAtMs.remove(designator)
            clearRenderDelayLocked(designator)
        }

        val streamPath = synchronized(stateLock) {
            sourcePathByDesignator[designator]
        } ?: designator
        val inputUrl =
            if (streamPath.contains("://")) {
                streamPath
            } else {
                "rtsp://127.0.0.1:8554/$streamPath"
            }
        CTDebug(
            tag,
            "Starting FFmpeg render for $designator url=$inputUrl " +
                upstreamCorrelationSummary(designator, System.currentTimeMillis())
        )
        val sessionId = FfmpegBridge.startRender(designator, inputUrl)
        if (sessionId > 0L) {
            var duplicateSessionId: Long? = null
            var existingSessionId: Long? = null
            val reboundSurface = synchronized(stateLock) {
                startingRenderDesignators.remove(designator)
                val existingActive = renderSessions[designator]
                if (existingActive != null) {
                    duplicateSessionId = sessionId
                    existingSessionId = existingActive
                    return@synchronized null
                }
                renderSessions[designator] = sessionId
                val created = ManagedRenderSession(
                    designator = designator,
                    createdAtMs = System.currentTimeMillis(),
                    phase = RenderSessionPhase.STARTING,
                    phaseChangedAtMs = System.currentTimeMillis(),
                )
                pendingRepublishByDesignator.remove(designator)?.let { marker ->
                    created.pendingRepublishMarker = marker
                }
                managedRenderSessions[sessionId] = created
                activeRenderSurfaceByDesignator[designator]
            }
            if (duplicateSessionId != null) {
                stopSessionAsync(sessionId, "Stopped duplicate FFmpeg render for $designator sessionId=$sessionId")
                return existingSessionId
            }
            reboundSurface?.let { surface ->
                if (!FfmpegBridge.attachSurface(sessionId, surface)) {
                    CTWarn(tag, "Unable to attach rebound render surface for $designator sessionId=$sessionId")
                }
            }
            applyAnomalyConfigToSession(designator, sessionId)
            applyLocalPlaybackControlsToSession(designator, sessionId)
            return sessionId
        }
        synchronized(stateLock) {
            startingRenderDesignators.remove(designator)
        }
        CTWarn(tag, "Unable to start FFmpeg render for $designator")
        return null
    }

    private fun recordDecodedFrameLocked(sessionState: ManagedRenderSession, now: Long) {
        val previousFrameAt = sessionState.lastFrameAtMs
        val frameGapMs = previousFrameAt?.let { now - it }
        val recentReaderWait =
            sessionState.lastReaderWaitAtMs != null &&
                now - sessionState.lastReaderWaitAtMs!! <= recentReaderWaitPenaltyMs
        sessionState.recoveryFrameStreak = when {
            !recentReaderWait -> 0
            frameGapMs == null || frameGapMs <= recoveryFrameContinuityGapMs -> sessionState.recoveryFrameStreak + 1
            else -> 1
        }
        if (recentReaderWait &&
            sessionState.recoveryFrameStreak >= readerWaitRecoveryFramesToClear
        ) {
            sessionState.lastReaderWaitAtMs = null
        }
        sessionState.decodedFrameCount += 1
        sessionState.lastFrameAtMs = now
    }

    private fun recordRenderedFrameLocked(sessionState: ManagedRenderSession) {
        sessionState.renderedFrameCount += 1
    }

    private fun updateSessionPollProgressLocked(
        sessionState: ManagedRenderSession,
        useRenderedProgress: Boolean,
    ) {
        val currentDecodedCount = sessionState.decodedFrameCount
        val currentRenderedCount = sessionState.renderedFrameCount
        val previousObservedCount =
            if (useRenderedProgress) {
                sessionState.lastObservedRenderedFrameCount
            } else {
                sessionState.lastObservedDecodedFrameCount
            }
        val currentObservedCount =
            if (useRenderedProgress) {
                currentRenderedCount
            } else {
                currentDecodedCount
            }
        val progressed = currentObservedCount > previousObservedCount
        if (progressed) {
            sessionState.idlePollCount = 0
        } else {
            sessionState.idlePollCount += 1
        }
        sessionState.lastObservedDecodedFrameCount = currentDecodedCount
        sessionState.lastObservedRenderedFrameCount = currentRenderedCount
    }

    private fun classifySessionHealthLocked(
        designator: String,
        sessionState: ManagedRenderSession,
        useRenderedProgress: Boolean,
        nowMs: Long,
    ): RenderSessionHealth {
        val isLocalFilePlayback =
            sourcePathByDesignator[designator]?.startsWith("file://", ignoreCase = true) == true
        val localPlaybackHeld =
            isLocalFilePlayback && localPlaybackPausedByDesignator[designator] == true
        if (localPlaybackHeld) return RenderSessionHealth.HEALTHY
        if (sessionState.idlePollCount == 0) return RenderSessionHealth.HEALTHY
        if (useRenderedProgress &&
            sessionState.renderedFrameCount > 0L &&
            sessionState.idlePollCount >= renderedNoProgressRecoveryPolls
        ) {
            // Suppress LONG_IDLE restart while the C reader is actively blocked in
            // ETIMEDOUT (av_read_frame stall).  In that state the C side fires
            // "reader_wait_long" every ~2.5 s and HOLD mode is stretching the last
            // good frames.  Killing and restarting the session here would only add a
            // cold-start delay (find_stream_info ≈ 2-3 s) on top of the ongoing stall.
            // We let the C side manage the visual freeze; recovery happens naturally
            // when the controller resumes sending data.
            val hasRecentReaderWait =
                sessionState.lastReaderWaitAtMs?.let { nowMs - it <= recentReaderWaitPenaltyMs } == true
            return if (shouldRecoverRenderedNoProgress(
                    isLocalFilePlayback = isLocalFilePlayback,
                    renderedFrameCount = sessionState.renderedFrameCount,
                    idlePollCount = sessionState.idlePollCount,
                    recoveryPollThreshold = renderedNoProgressRecoveryPolls,
                    hasRecentReaderWait = hasRecentReaderWait,
                )
            ) {
                RenderSessionHealth.LONG_IDLE
            } else {
                RenderSessionHealth.STALLED
            }
        }
        return RenderSessionHealth.STALLED
    }

    private fun retireLongIdleRenderLocked(designator: String, idlePollCount: Int): Pair<Long, Int>? {
        val activeSessionId = renderSessions[designator] ?: return null
        val activeSession = managedRenderSessions[activeSessionId] ?: return null
        if (activeSession.renderedFrameCount == 0L) return null
        if (idlePollCount < renderedNoProgressRecoveryPolls) return null
        renderSessions.remove(designator)
        lastFrameAtMs.remove(designator)
        managedRenderSessions[activeSessionId]?.let { session ->
            setSessionPhaseLocked(
                session = session,
                phase = RenderSessionPhase.RETIRING,
                nowMs = System.currentTimeMillis(),
            )
        }
        return activeSessionId to idlePollCount
    }

    private fun handleDecodedFrameEventLocked(
        sessionId: Long,
        now: Long,
        sourceTimestampUs: Long?,
    ) {
        val sessionState = managedRenderSessions[sessionId] ?: return
        recordDecodedFrameLocked(sessionState, now)
        if (sessionState.pendingRepublishMarker != null) {
            sessionState.sourceClockOffsetMs = null
            sessionState.lastSourceTimestampUs = null
        }
        updateSourceLagEstimateLocked(sessionState, sourceTimestampUs, now)
        sessionState.pendingRepublishMarker?.let { marker ->
            val republishToDecodedMs = now - marker.observedAtMs
            val upstreamBoundaryToRepublishMs = marker.upstreamBoundaryObservedAtMs?.let { marker.observedAtMs - it } ?: -1L
            val upstreamBoundaryToDecodedMs = marker.upstreamBoundaryObservedAtMs?.let { now - it } ?: -1L
            CTDebug(
                tag,
                "Upstream republish recovery designator=${sessionState.designator} sessionId=$sessionId " +
                    "republishToDecodedMs=$republishToDecodedMs prevPublisherConnId=${marker.previousPublisherConnId} " +
                    "publisherConnId=${marker.publisherConnId} upstreamBoundary=${marker.upstreamBoundary} " +
                    "upstreamBoundaryOutcome=${marker.upstreamBoundaryOutcome} " +
                    "upstreamBoundaryPublisherConnId=${marker.upstreamBoundaryPublisherConnId} " +
                    "upstreamBoundaryRotationSeq=${marker.upstreamBoundaryRotationSeq} " +
                    "upstreamBoundaryToRepublishMs=$upstreamBoundaryToRepublishMs " +
                    "upstreamBoundaryToDecodedMs=$upstreamBoundaryToDecodedMs"
            )
            sessionState.pendingRepublishMarker = null
        }
        val waitWindowStartedAt = sessionState.readerWaitWindowStartedAtMs
        if (waitWindowStartedAt != null) {
            val waitWindowDurationMs = now - waitWindowStartedAt
            val waitEvents = sessionState.readerWaitEventCountInWindow
            CTDebug(
                tag,
                "Reader wait resolved designator=${sessionState.designator} sessionId=$sessionId " +
                    "waitWindowMs=$waitWindowDurationMs waitEvents=$waitEvents " +
                    pipelineCorrelationSummary(sessionId, now)
            )
            sessionState.readerWaitWindowStartedAtMs = null
            sessionState.readerWaitEventCountInWindow = 0
            sessionState.lastStallDiagnosticBucket = -1
        }
    }

    private fun handleRenderedFrameEventLocked(
        designator: String,
        sessionId: Long,
        now: Long,
        sourceTimestampUs: Long?,
        renderLatencyMs: Long?,
    ) {
        val sessionState = managedRenderSessions[sessionId] ?: return
        recordRenderedFrameLocked(sessionState)
        setSessionPhaseLocked(
            session = sessionState,
            phase = RenderSessionPhase.LIVE,
            nowMs = now,
        )
        if (renderSessions[designator] == sessionId) {
            lastFrameAtMs[designator] = now
            val sourceLagMs = updateSourceLagEstimateLocked(sessionState, sourceTimestampUs, now).estimatedLagMs
            val effectiveLagMs = when {
                sourceLagMs != null && renderLatencyMs != null -> max(sourceLagMs, renderLatencyMs)
                else -> sourceLagMs ?: renderLatencyMs
            }
            updateRenderDelayLocked(designator, effectiveLagMs, now)
        }
    }

    private fun handleSessionStopped(designator: String, sessionId: Long) {
        var localPlaybackEnded = false
        synchronized(stateLock) {
            val isLocalFile = sourcePathByDesignator[designator]?.startsWith("file://", ignoreCase = true) == true
            val eofObserved = localPlaybackEofByDesignator.contains(designator)
            if (renderSessions[designator] == sessionId) {
                renderSessions.remove(designator)
                lastFrameAtMs.remove(designator)
                clearRenderDelayLocked(designator)
            }
            if (suspendedRenderSessions[designator] == sessionId) {
                suspendedRenderSessions.remove(designator)
            }
            managedRenderSessions.remove(sessionId)
            auxiliaryRenderSessions.remove(sessionId)
            if (telemetryProbeSessions[designator] == sessionId) {
                telemetryProbeSessions.remove(designator)
            }
            sourceInfoBySessionId.remove(sessionId)
            sourceInfoFetchPending.remove(sessionId)
            if (isLocalFile &&
                eofObserved &&
                renderSessions[designator] == null &&
                suspendedRenderSessions[designator] == null &&
                managedRenderSessions.values.none { it.designator == designator }
            ) {
                localPlaybackEofByDesignator.remove(designator)
                localPlaybackEnded = true
            }
        }
        if (localPlaybackEnded) {
            onLocalPlaybackEnded(designator)
        }
    }

    private fun updateRenderDelayLocked(
        designator: String,
        renderLatencyMs: Long?,
        observedAtMs: Long,
    ) {
        val latencyMs = renderLatencyMs ?: return
        if (latencyMs < 0L) return
        renderDelayBaseMsByDesignator[designator] = latencyMs
        renderDelayMeasuredAtMsByDesignator[designator] = observedAtMs
        publishRenderDelayLocked(designator, latencyMs, observedAtMs)
    }

    private fun publishRenderDelayLocked(
        designator: String,
        baseLagMs: Long,
        nowMs: Long,
    ) {
        val measuredAtMs = renderDelayMeasuredAtMsByDesignator[designator] ?: nowMs
        val agedLagMs = baseLagMs + maxOf(0L, nowMs - measuredAtMs)
        val quantizedLatencyMs = quantizeRenderDelayMs(agedLagMs)
        if (renderDelayMsByDesignator[designator] == quantizedLatencyMs) return
        renderDelayMsByDesignator[designator] = quantizedLatencyMs
        _renderDelayMsByDesignator.value = renderDelayMsByDesignator.toMap()
    }

    private fun refreshRenderDelayLocked(nowMs: Long) {
        renderDelayBaseMsByDesignator.forEach { (designator, baseLagMs) ->
            publishRenderDelayLocked(designator, baseLagMs, nowMs)
        }
    }

    private fun clearRenderDelayLocked(designator: String) {
        renderDelayBaseMsByDesignator.remove(designator)
        renderDelayMeasuredAtMsByDesignator.remove(designator)
        if (renderDelayMsByDesignator.remove(designator) != null) {
            _renderDelayMsByDesignator.value = renderDelayMsByDesignator.toMap()
        }
    }

    private fun updateSourceLagEstimateLocked(
        sessionState: ManagedRenderSession,
        sourceTimestampUs: Long?,
        observedAtMs: Long,
    ): SourceLagEstimate {
        val estimate = updateSourceLagEstimate(
            sourceClockOffsetMs = sessionState.sourceClockOffsetMs,
            lastSourceTimestampUs = sessionState.lastSourceTimestampUs,
            sourceTimestampUs = sourceTimestampUs,
            observedAtMs = observedAtMs,
            resetThresholdMs = sourceTimestampResetThresholdMs,
        )
        sessionState.sourceClockOffsetMs = estimate.sourceClockOffsetMs
        sessionState.lastSourceTimestampUs = estimate.lastSourceTimestampUs
        return estimate
    }

    private fun pollForNoFrameSessions() {
        val now = System.currentTimeMillis()
        logRuntimePerformance(now)
        val activeRecoveryPlan = mutableListOf<Triple<String, Long, Int>>()
        val startupRecoveryPlan = mutableListOf<Triple<String, Long, Long>>()
        synchronized(stateLock) {
            refreshRenderDelayLocked(now)
            val activeRenderDesignators = renderSessions.keys.toList()
            activeRenderDesignators.forEach { designator ->
                val activeSessionId = renderSessions[designator] ?: return@forEach
                val activeSession = managedRenderSessions[activeSessionId] ?: return@forEach
                if (activeSession.phase == RenderSessionPhase.RETIRING) {
                    return@forEach
                }
                if (activeSession.phase == RenderSessionPhase.STARTING ||
                    activeSession.phase == RenderSessionPhase.PRIMED
                ) {
                    val hasAnyFrames = activeSession.decodedFrameCount > 0L || activeSession.renderedFrameCount > 0L
                    val phaseAgeMs = now - activeSession.phaseChangedAtMs
                    val hasRecentReaderWait = activeSession.lastReaderWaitAtMs?.let { now - it <= recentReaderWaitPenaltyMs } == true
                    val upstreamBoundary = latestUpstreamBoundary(designator)
                    val upstreamTransientClose =
                        upstreamBoundary?.boundary == "stream_error" &&
                            upstreamBoundary.outcome == "deferred_transient_close"
                    // A recent reader stall is expected once playback is already flowing and the
                    // upstream bursts. It should not suppress startup recovery when we have never
                    // decoded or rendered a single frame, otherwise the render can sit black in
                    // PRIMED state indefinitely waiting on a stream that never becomes decodable.
                    val deferStartupRecovery = upstreamTransientClose || (hasAnyFrames && hasRecentReaderWait)
                    val allowForcedRecovery = phaseAgeMs >= startupNoFrameAbsoluteRecoveryMs
                    if (!hasAnyFrames &&
                        phaseAgeMs >= startupNoFrameRecoveryMs &&
                        !activeSession.startupRecoveryAttempted
                    ) {
                        if (deferStartupRecovery && !allowForcedRecovery) {
                            if (now - activeSession.lastStartupRecoveryDeferredLogAtMs >= startupNoFrameDeferralLogMs) {
                                activeSession.lastStartupRecoveryDeferredLogAtMs = now
                                CTDebug(
                                    tag,
                                    "Deferring startup no-frame recovery designator=$designator " +
                                        "sessionId=$activeSessionId phaseAgeMs=$phaseAgeMs " +
                                        "recentReaderWait=$hasRecentReaderWait " +
                                        "upstreamBoundary=${upstreamBoundary?.boundary} " +
                                        "upstreamOutcome=${upstreamBoundary?.outcome}"
                                )
                            }
                            return@forEach
                        }
                        activeSession.startupRecoveryAttempted = true
                        renderSessions.remove(designator)
                        setSessionPhaseLocked(
                            session = activeSession,
                            phase = RenderSessionPhase.RETIRING,
                            nowMs = now,
                        )
                        startupRecoveryPlan += Triple(designator, activeSessionId, phaseAgeMs)
                    }
                    return@forEach
                }
                val useRenderedProgress = activeRenderSurfaceByDesignator.containsKey(designator)
                updateSessionPollProgressLocked(
                    sessionState = activeSession,
                    useRenderedProgress = useRenderedProgress,
                )
                when (classifySessionHealthLocked(designator, activeSession, useRenderedProgress, now)) {
                    RenderSessionHealth.HEALTHY,
                    RenderSessionHealth.STALLED,
                    -> Unit
                    RenderSessionHealth.LONG_IDLE -> {
                        retireLongIdleRenderLocked(designator, activeSession.idlePollCount)?.let { (sessionId, idlePolls) ->
                            activeRecoveryPlan += Triple(designator, sessionId, idlePolls)
                        }
                    }
                }
            }
        }
        activeRecoveryPlan.forEach { (designator, sessionId, idlePolls) ->
            CTWarn(
                tag,
                "Rendered-session recovery designator=$designator sessionId=$sessionId idlePolls=$idlePolls " +
                    upstreamCorrelationSummary(designator, now)
            )
            stopSessionAsync(
                sessionId,
                "Stopped stalled FFmpeg render for $designator sessionId=$sessionId idlePolls=$idlePolls"
            )
            sessionExecutionLanes.executeControl {
                val upstreamBoundary = latestUpstreamBoundary(designator)
                val upstreamTransientClose =
                    upstreamBoundary?.boundary == "stream_error" &&
                        upstreamBoundary.outcome == "deferred_transient_close"
                if (upstreamTransientClose) {
                    CTDebug(
                        tag,
                        "Deferring FFmpeg rendered-session recovery restart for $designator sessionId=$sessionId " +
                            "while upstream is transiently closed " +
                            "boundary=${upstreamBoundary?.boundary} outcome=${upstreamBoundary?.outcome}"
                    )
                    return@executeControl
                }
                if (isRenderEnabled(designator) && hasBoundRenderSurface(designator)) {
                    ensureRenderSession(designator)
                }
            }
        }
        startupRecoveryPlan.forEach { (designator, sessionId, phaseAgeMs) ->
            CTWarn(
                tag,
                "Startup no-frame recovery designator=$designator sessionId=$sessionId phaseAgeMs=$phaseAgeMs " +
                    upstreamCorrelationSummary(designator, now)
            )
            stopSessionAsync(
                sessionId,
                "Stopped startup-stalled FFmpeg render for $designator sessionId=$sessionId phaseAgeMs=$phaseAgeMs"
            )
            sessionExecutionLanes.executeControl {
                val upstreamBoundary = latestUpstreamBoundary(designator)
                val upstreamTransientClose =
                    upstreamBoundary?.boundary == "stream_error" &&
                        upstreamBoundary.outcome == "deferred_transient_close"
                if (upstreamTransientClose) {
                    CTDebug(
                        tag,
                        "Deferring FFmpeg startup-recovery restart for $designator sessionId=$sessionId " +
                            "while upstream is transiently closed " +
                            "boundary=${upstreamBoundary?.boundary} outcome=${upstreamBoundary?.outcome}"
                    )
                    return@executeControl
                }
                if (isRenderEnabled(designator) && hasBoundRenderSurface(designator)) {
                    ensureRenderSession(designator)
                }
            }
        }
    }

    private fun logRuntimePerformance(nowMs: Long) {
        val activeSnapshot = synchronized(stateLock) {
            Pair(renderSessions.keys.toSet(), lastFrameAtMs.toMap())
        }
        val renderActive = activeSnapshot.first
        val hasActiveSessions = renderActive.isNotEmpty()

        val pollGapMs = if (lastPollAtMs == 0L) 0L else nowMs - lastPollAtMs
        lastPollAtMs = nowMs
        if (!hasActiveSessions) {
            lastPerfSampleAtMs = nowMs
            lastPerfProcessCpuMs = Process.getElapsedCpuTime()
            lastPerfMainThreadCpuNs = Debug.threadCpuTimeNanos()
            return
        }

        if (lastPerfSampleAtMs == 0L) {
            lastPerfSampleAtMs = nowMs
            lastPerfProcessCpuMs = Process.getElapsedCpuTime()
            lastPerfMainThreadCpuNs = Debug.threadCpuTimeNanos()
            return
        }

        val elapsedMs = nowMs - lastPerfSampleAtMs
        if (elapsedMs < 5_000L) return

        val processCpuMs = Process.getElapsedCpuTime()
        val mainThreadCpuNs = Debug.threadCpuTimeNanos()
        val processCpuDeltaMs = processCpuMs - lastPerfProcessCpuMs
        val mainThreadCpuDeltaMs = (mainThreadCpuNs - lastPerfMainThreadCpuNs) / 1_000_000L
        val maxFrameAgeMs = activeSnapshot.second
            .keys
            .filter { it in renderActive }
            .mapNotNull { designator -> activeSnapshot.second[designator]?.let { nowMs - it } }
            .maxOrNull() ?: -1L

        CTDebug(
            tag,
            "Perf sample active(render=${renderActive.size}) " +
                "elapsedMs=$elapsedMs pollGapMs=$pollGapMs processCpuDeltaMs=$processCpuDeltaMs " +
                "mainThreadCpuDeltaMs=$mainThreadCpuDeltaMs maxFrameAgeMs=$maxFrameAgeMs"
        )

        lastPerfSampleAtMs = nowMs
        lastPerfProcessCpuMs = processCpuMs
        lastPerfMainThreadCpuNs = mainThreadCpuNs
    }

    private fun stopSessionAsync(sessionId: Long, completionLog: String) {
        if (sessionId <= 0L) return
        synchronized(stateLock) {
            retiringSessionIds += sessionId
            managedRenderSessions[sessionId]?.let { session ->
                setSessionPhaseLocked(
                    session = session,
                    phase = RenderSessionPhase.RETIRING,
                    nowMs = System.currentTimeMillis(),
                )
            }
        }
        if (closing) return
        sessionExecutionLanes.executeStop {
            try {
                FfmpegBridge.stop(sessionId)
                CTDebug(tag, completionLog)
            } finally {
                synchronized(stateLock) {
                    retiringSessionIds.remove(sessionId)
                }
            }
        }
    }

    private fun handleDecoderOpenError(designator: String, sessionId: Long) {
        val wasActive = synchronized(stateLock) {
            if (sessionId == renderSessions[designator]) {
                renderSessions.remove(designator)
                lastFrameAtMs.remove(designator)
                managedRenderSessions[sessionId]?.let { session ->
                    setSessionPhaseLocked(
                        session = session,
                        phase = RenderSessionPhase.RETIRING,
                        nowMs = System.currentTimeMillis(),
                    )
                }
                true
            } else {
                false
            }
        }
        if (wasActive) {
            CTWarn(tag, "Decoder open failed for $designator sessionId=$sessionId; waiting for a fresh live signal before retrying.")
        }
    }

    private fun mergeTelemetry(designator: String, incoming: FfmpegTelemetry) {
        val result = synchronized(stateLock) {
            val mergedResult = FfmpegTelemetryReducer.merge(
                existing = telemetryByDesignator[designator],
                incoming = incoming,
                existingCandidates = remoteIdCandidatesByDesignator[designator].orEmpty(),
            )
            telemetryByDesignator[designator] = mergedResult.mergedTelemetry
            remoteIdCandidatesByDesignator[designator] = mergedResult.updatedCandidates
            mergedResult
        }
        result.addedRemoteId?.let { rid ->
            CTDebug(tag, "Telemetry remoteId candidate for $designator: $rid")
        }
    }

    private fun applyAnomalyConfig(designator: String) {
        val snapshot = synchronized(stateLock) {
            val config = anomalyConfigByDesignator[designator] ?: return
            val sessionIds = listOfNotNull(renderSessions[designator]).distinct()
            Triple(
                sessionIds,
                config,
                personRelevanceModeByDesignator[designator] ?: PersonRelevanceMode.Off,
            )
        }
        snapshot.first.forEach { sessionId ->
            if (snapshot.second.troubleshootingDebug) {
                CTDebug(
                    tag,
                    "Applying anomaly config to $designator sessionId=$sessionId " +
                        "enabled=${snapshot.second.enabled} mask=${snapshot.second.algorithmMask} " +
                        "reg=${snapshot.second.registrationMode} strideMode=${snapshot.second.strideMode} " +
                        "stride=${snapshot.second.frameStride} adaptiveMin=${snapshot.second.adaptiveMinStrideFrames} " +
                        "adaptiveMaxFrames=${snapshot.second.adaptiveMaxStrideFrames} " +
                        "adaptiveMaxSec=${"%.1f".format(snapshot.second.adaptiveMaxStrideSeconds)} " +
                        "pixelStep=${snapshot.second.pixelStep} threshold=${"%.2f".format(snapshot.second.scoreThreshold)} " +
                        "minHits=${snapshot.second.minHits} scanZone=${"%.2f".format(snapshot.second.scanZone)} " +
                        "colorFrontend=${snapshot.second.colorFrontendMode} " +
                        "targetColors=${snapshot.second.targetColorFamilyMask} " +
                        "personRelevance=${snapshot.third}"
                )
            }
            FfmpegBridge.updateAnomalyConfig(sessionId, snapshot.second)
            FfmpegBridge.setPersonRelevanceMode(sessionId, snapshot.third.toFfmpegBridgeMode())
        }
    }

    private fun applyAnomalyConfigToSession(designator: String, sessionId: Long) {
        val policy = synchronized(stateLock) {
            val config = anomalyConfigByDesignator[designator] ?: return
            Pair(
                config,
                personRelevanceModeByDesignator[designator] ?: PersonRelevanceMode.Off,
            )
        }
        val config = policy.first
        if (config.troubleshootingDebug) {
            CTDebug(
                tag,
                "Applying anomaly config to new session $designator sessionId=$sessionId " +
                    "enabled=${config.enabled} mask=${config.algorithmMask} " +
                    "reg=${config.registrationMode} strideMode=${config.strideMode} " +
                    "stride=${config.frameStride} adaptiveMin=${config.adaptiveMinStrideFrames} " +
                    "adaptiveMaxFrames=${config.adaptiveMaxStrideFrames} " +
                    "adaptiveMaxSec=${"%.1f".format(config.adaptiveMaxStrideSeconds)} " +
                    "pixelStep=${config.pixelStep} threshold=${"%.2f".format(config.scoreThreshold)} " +
                    "minHits=${config.minHits} scanZone=${"%.2f".format(config.scanZone)} " +
                    "colorFrontend=${config.colorFrontendMode} " +
                    "targetColors=${config.targetColorFamilyMask} " +
                    "personRelevance=${policy.second}"
            )
        }
        FfmpegBridge.updateAnomalyConfig(sessionId, config)
        FfmpegBridge.setPersonRelevanceMode(sessionId, policy.second.toFfmpegBridgeMode())
    }

    private fun applyLocalPlaybackControlsToSession(designator: String, sessionId: Long) {
        val paused = synchronized(stateLock) {
            localPlaybackPausedByDesignator[designator] == true
        }
        FfmpegBridge.setLocalPlaybackPaused(sessionId, paused)
    }
}

/**
 * Operational DJI telemetry has exactly one authority per live stream. The packet-only probe is
 * continuous across Streams UI and render-surface changes, while render sessions can start,
 * suspend, recover, and restart on their own source-timestamp timelines. Renderer telemetry may
 * still feed presentation diagnostics, but it must not reset RID-validated map position state.
 */
internal object OperationalTelemetryAuthorityPolicy {
    fun accepts(sessionId: Long, telemetryProbeSessionId: Long?): Boolean =
        telemetryProbeSessionId != null && sessionId == telemetryProbeSessionId
}

internal fun PersonRelevanceMode.toFfmpegBridgeMode(): FfmpegBridge.PersonRelevanceMode =
    when (this) {
        PersonRelevanceMode.Off -> FfmpegBridge.PersonRelevanceMode.OFF
        PersonRelevanceMode.Evaluate -> FfmpegBridge.PersonRelevanceMode.SHADOW
        PersonRelevanceMode.Assist -> FfmpegBridge.PersonRelevanceMode.POSITIVE_ONLY
    }

internal fun quantizeRenderDelayMs(renderLatencyMs: Long): Long {
    if (renderLatencyMs <= 0L) return 0L
    val bucketMs = when {
        renderLatencyMs < 1_000L -> 100L
        renderLatencyMs < 10_000L -> 250L
        else -> 500L
    }
    return max(bucketMs, ((renderLatencyMs + (bucketMs / 2)) / bucketMs) * bucketMs)
}

internal data class SourceLagEstimate(
    val sourceClockOffsetMs: Long?,
    val lastSourceTimestampUs: Long?,
    val estimatedLagMs: Long?,
)

internal fun updateSourceLagEstimate(
    sourceClockOffsetMs: Long?,
    lastSourceTimestampUs: Long?,
    sourceTimestampUs: Long?,
    observedAtMs: Long,
    resetThresholdMs: Long = 1_000L,
): SourceLagEstimate {
    if (sourceTimestampUs == null || sourceTimestampUs <= 0L) {
        return SourceLagEstimate(
            sourceClockOffsetMs = sourceClockOffsetMs,
            lastSourceTimestampUs = lastSourceTimestampUs,
            estimatedLagMs = null,
        )
    }

    val sourceTimestampMs = sourceTimestampUs / 1_000L
    val lastSourceTimestampMs = lastSourceTimestampUs?.takeIf { it > 0L }?.div(1_000L)
    val resetClock =
        lastSourceTimestampMs != null &&
            sourceTimestampMs + resetThresholdMs < lastSourceTimestampMs
    val anchoredOffsetMs = if (resetClock) null else sourceClockOffsetMs
    val observedOffsetMs = observedAtMs - sourceTimestampMs
    val nextOffsetMs = when {
        anchoredOffsetMs == null -> observedOffsetMs
        observedOffsetMs < anchoredOffsetMs -> observedOffsetMs
        else -> anchoredOffsetMs
    }
    val estimatedLagMs = max(0L, observedAtMs - (nextOffsetMs + sourceTimestampMs))
    return SourceLagEstimate(
        sourceClockOffsetMs = nextOffsetMs,
        lastSourceTimestampUs = sourceTimestampUs,
        estimatedLagMs = estimatedLagMs,
    )
}
