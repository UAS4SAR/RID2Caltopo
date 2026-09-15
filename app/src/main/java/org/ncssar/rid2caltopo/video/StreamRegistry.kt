package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoClient.ShowToast

data class StreamAdmissionState(
    val active: Map<String, StreamInfo>,
    val stateChangedAtMs: Map<String, Long>,
    val rejectedPaths: Set<String>,
)

data class StreamAdmissionResult(
    val state: StreamAdmissionState,
    val admitted: Boolean,
    val shouldNotifyRejection: Boolean,
    val evictedPaths: Set<String>,
    val rejectionReason: String? = null,
    val rejectionToast: String? = null,
)

data class StreamTransitionResult(
    val state: StreamAdmissionState,
    val changed: Boolean,
    val existed: Boolean,
)

data class StreamAdmissionGuardResult(
    val allow: Boolean,
    val reason: String? = null,
    val toastMessage: String? = null,
)

private data class PublisherConnEvent(
    val connId: String?,
    val observedAtMs: Long,
)

private data class ParsedStreamPath(
    val designator: String,
    val sourcePath: String,
    val controllerProfile: StreamControllerProfile,
)

private fun parseStreamPath(path: String): ParsedStreamPath {
    val normalizedPath = path.trim().trim('/')
    if (normalizedPath.isEmpty()) {
        return ParsedStreamPath(
            designator = path,
            sourcePath = path,
            controllerProfile = StreamControllerProfile.GENERIC,
        )
    }

    val segments = normalizedPath.split('/').filter { it.isNotBlank() }
    val controllerProfile = when (segments.firstOrNull()?.uppercase(Locale.US)) {
        "RC2" -> StreamControllerProfile.RC2
        "RCPRO2" -> StreamControllerProfile.RCPRO2
        "RCPRO1" -> StreamControllerProfile.RCPRO1
        "ENTERPRISE2" -> StreamControllerProfile.ENTERPRISE2
        "AUTEL" -> StreamControllerProfile.AUTEL
        else -> StreamControllerProfile.GENERIC
    }

    val designator = if (controllerProfile == StreamControllerProfile.GENERIC) {
        normalizedPath
    } else {
        segments.drop(1).joinToString("/").ifBlank { normalizedPath }
    }

    return ParsedStreamPath(
        designator = designator,
        sourcePath = normalizedPath,
        controllerProfile = controllerProfile,
    )
}

object StreamAdmissionPolicy {
    private fun nextRevision(existing: StreamInfo?): Long {
        return (existing?.revision ?: 0L) + 1L
    }

    private fun shouldDeferTransientPublisherError(
        state: StreamAdmissionState,
        path: String,
        reason: String?,
    ): Boolean {
        if (reason.isNullOrBlank()) return false
        if (!reason.startsWith("RTMP closed:", ignoreCase = true)) return false
        val info = state.active[path] ?: return false
        return info.state == StreamState.LIVE || info.state == StreamState.CONNECTING
    }

    fun admit(
        state: StreamAdmissionState,
        designator: String,
        sourcePath: String,
        controllerProfile: StreamControllerProfile,
        publisherConnId: String? = null,
        targetState: StreamState,
        nowMs: Long,
        maxSimultaneousStreams: Int,
        staleConnectingMs: Long,
        staleErrorMs: Long,
        admissionGuard: ((StreamAdmissionState, String, StreamState) -> StreamAdmissionGuardResult)? = null,
    ): StreamAdmissionResult {
        val active = state.active.toMutableMap()
        val changedAt = state.stateChangedAtMs.toMutableMap()
        val rejected = state.rejectedPaths.toMutableSet()

        val evicted = active.values
            .filter { info ->
                val ageMs = nowMs - (changedAt[info.designator] ?: nowMs)
                when (info.state) {
                    StreamState.CONNECTING -> ageMs > staleConnectingMs
                    StreamState.ERROR -> ageMs > staleErrorMs
                    else -> false
                }
            }
            .map { it.designator }
            .toSet()

        evicted.forEach { stalePath ->
            active.remove(stalePath)
            changedAt.remove(stalePath)
            rejected.remove(stalePath)
        }

        val canAdmit = active.containsKey(designator) || active.size < maxSimultaneousStreams
        if (!canAdmit) {
            val firstReject = rejected.add(designator)
            return StreamAdmissionResult(
                state = StreamAdmissionState(active, changedAt, rejected),
                admitted = false,
                shouldNotifyRejection = firstReject,
                evictedPaths = evicted,
                rejectionReason = "capacity",
                rejectionToast = "Rejected stream $designator (max $maxSimultaneousStreams streams).",
            )
        }

        if (!active.containsKey(designator)) {
            val guardDecision = admissionGuard?.invoke(
                StreamAdmissionState(active, changedAt, rejected),
                designator,
                targetState,
            )
            if (guardDecision != null && !guardDecision.allow) {
                val firstReject = rejected.add(designator)
                return StreamAdmissionResult(
                    state = StreamAdmissionState(active, changedAt, rejected),
                    admitted = false,
                    shouldNotifyRejection = firstReject,
                    evictedPaths = evicted,
                    rejectionReason = guardDecision.reason ?: "guard",
                    rejectionToast = guardDecision.toastMessage,
                )
            }
        }

        val existing = active[designator]
        val resolvedPublisherConnId = publisherConnId ?: existing?.publisherConnId
        active[designator] = StreamInfo(
            designator = designator,
            sourcePath = sourcePath,
            controllerProfile = controllerProfile,
            publisherConnId = resolvedPublisherConnId,
            state = targetState,
            revision = nextRevision(existing),
        )
        changedAt[designator] = nowMs
        rejected.remove(designator)
        return StreamAdmissionResult(
            state = StreamAdmissionState(active, changedAt, rejected),
            admitted = true,
            shouldNotifyRejection = false,
            evictedPaths = evicted,
        )
    }

    fun markError(
        state: StreamAdmissionState,
        path: String,
        nowMs: Long,
        reason: String? = null,
    ): StreamTransitionResult {
        if (shouldDeferTransientPublisherError(state, path, reason)) {
            return StreamTransitionResult(state = state, changed = false, existed = true)
        }
        val active = state.active.toMutableMap()
        val existing = active[path]
        if (existing == null) {
            return StreamTransitionResult(state = state, changed = false, existed = false)
        }
        val changedAt = state.stateChangedAtMs.toMutableMap()
        active[path] = StreamInfo(
            designator = existing.designator,
            sourcePath = existing.sourcePath,
            controllerProfile = existing.controllerProfile,
            publisherConnId = existing.publisherConnId,
            state = StreamState.ERROR,
            errorDetail = reason,
            revision = nextRevision(existing),
        )
        changedAt[path] = nowMs
        return StreamTransitionResult(
            state = StreamAdmissionState(active, changedAt, state.rejectedPaths),
            changed = true,
            existed = true,
        )
    }

    fun markStopped(state: StreamAdmissionState, path: String): StreamTransitionResult {
        val existed = state.active.containsKey(path)
        val active = state.active.toMutableMap().apply { remove(path) }
        val changedAt = state.stateChangedAtMs.toMutableMap().apply { remove(path) }
        val rejected = state.rejectedPaths.toMutableSet().apply { remove(path) }
        return StreamTransitionResult(
            state = StreamAdmissionState(active, changedAt, rejected),
            changed = existed,
            existed = existed,
        )
    }
}

object StreamRegistry {
    private val TAG = "StreamRegistry"
    private const val MAX_SIMULTANEOUS_STREAMS = 4
    private const val STALE_CONNECTING_MS = 30_000L
    private const val STALE_ERROR_MS = 120_000L
    private const val RECENT_PUBLISHER_EVENT_MS = 15_000L
    private val _streams = MutableStateFlow<Map<String, StreamInfo>>(emptyMap())
    val streams: StateFlow<Map<String, StreamInfo>> = _streams
    private val rejectedPaths = mutableSetOf<String>()
    private val stateChangedAtMs = mutableMapOf<String, Long>()
    private val recentPublisherHandoffs = mutableMapOf<String, PublisherConnEvent>()
    private val recentPublisherClosures = mutableMapOf<String, PublisherConnEvent>()
    private var admissionGuard: ((StreamAdmissionState, String, StreamState) -> StreamAdmissionGuardResult)? = null
    private val lock = Any()
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    private fun snapshotLocked(): StreamAdmissionState {
        return StreamAdmissionState(
            active = _streams.value,
            stateChangedAtMs = stateChangedAtMs,
            rejectedPaths = rejectedPaths,
        )
    }

    private fun applyStateLocked(state: StreamAdmissionState) {
        _streams.value = state.active
        stateChangedAtMs.clear()
        stateChangedAtMs.putAll(state.stateChangedAtMs)
        rejectedPaths.clear()
        rejectedPaths.addAll(state.rejectedPaths)
        StreamFlightActivityRegistry.replaceLivePublishers(
            state.active.values
                .filter { it.state == StreamState.LIVE && !it.isLocalPlayback }
                .map { it.designator },
            nowMsProvider(),
        )
    }

    private fun hasMatchingSourcePathLocked(parsed: ParsedStreamPath): Boolean {
        val existing = _streams.value[parsed.designator] ?: return false
        return existing.sourcePath == parsed.sourcePath
    }

    private fun activeStreamLocked(parsed: ParsedStreamPath): StreamInfo? {
        val existing = _streams.value[parsed.designator] ?: return null
        return existing.takeIf { it.sourcePath == parsed.sourcePath }
    }

    private fun prunePublisherConnEventsLocked(nowMs: Long) {
        recentPublisherHandoffs.entries.removeAll { nowMs - it.value.observedAtMs > RECENT_PUBLISHER_EVENT_MS }
        recentPublisherClosures.entries.removeAll { nowMs - it.value.observedAtMs > RECENT_PUBLISHER_EVENT_MS }
    }

    private fun rememberPublisherHandoffLocked(sourcePath: String, publisherConnId: String?, nowMs: Long) {
        if (publisherConnId.isNullOrBlank()) {
            recentPublisherHandoffs.remove(sourcePath)
            return
        }
        recentPublisherHandoffs[sourcePath] = PublisherConnEvent(
            connId = publisherConnId,
            observedAtMs = nowMs,
        )
    }

    private fun rememberPublisherClosureLocked(sourcePath: String, publisherConnId: String?, nowMs: Long) {
        if (publisherConnId.isNullOrBlank()) {
            recentPublisherClosures.remove(sourcePath)
            return
        }
        recentPublisherClosures[sourcePath] = PublisherConnEvent(
            connId = publisherConnId,
            observedAtMs = nowMs,
        )
    }

    private fun updateActiveStreamLocked(updated: StreamInfo) {
        val active = _streams.value.toMutableMap()
        active[updated.designator] = updated
        _streams.value = active
    }

    fun setAdmissionGuard(guard: ((StreamAdmissionState, String, StreamState) -> StreamAdmissionGuardResult)?) {
        synchronized(lock) {
            admissionGuard = guard
        }
    }

    fun onStreamConnecting(path: String) {
        val parsed = parseStreamPath(path)
        var observedAtMs = 0L
        val admitted = synchronized(lock) {
            observedAtMs = nowMsProvider()
            val result = StreamAdmissionPolicy.admit(
                state = snapshotLocked(),
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                controllerProfile = parsed.controllerProfile,
                publisherConnId = null,
                targetState = StreamState.CONNECTING,
                nowMs = observedAtMs,
                maxSimultaneousStreams = MAX_SIMULTANEOUS_STREAMS,
                staleConnectingMs = STALE_CONNECTING_MS,
                staleErrorMs = STALE_ERROR_MS,
                admissionGuard = admissionGuard,
            )
            applyStateLocked(result.state)
            result.evictedPaths.forEach { stalePath ->
                CTWarn(TAG, "Evicting stale stream '$stalePath' to prevent capacity lockout.")
            }
            if (!result.admitted && result.shouldNotifyRejection) {
                val msg = when (result.rejectionReason) {
                    "capacity" -> "Rejecting stream '${parsed.designator}': max $MAX_SIMULTANEOUS_STREAMS active streams."
                    else -> "Rejecting stream '${parsed.designator}': ${result.rejectionReason ?: "admission guard"}."
                }
                CTWarn(TAG, msg)
                ShowToast(result.rejectionToast ?: "Rejected stream ${parsed.designator}.")
            }
            result.admitted
        }
        UpstreamTimingRegistry.recordBoundary(
            designator = parsed.designator,
            sourcePath = parsed.sourcePath,
            boundary = "stream_connecting",
            outcome = if (admitted) "admitted" else "rejected",
            observedAtMs = observedAtMs,
        )
        if (admitted) {
            CTDebug(TAG, "onStreamConnecting(${parsed.sourcePath} -> ${parsed.designator}): state:${StreamState.CONNECTING.name}")
        }
    }

    fun onStreamError(path: String, reason: String? = null, publisherConnId: String? = null) {
        val parsed = parseStreamPath(path)
        var observedAtMs = 0L
        var previousPublisherConnId: String? = null
        val ignoreStaleConn = synchronized(lock) {
            val now = nowMsProvider()
            observedAtMs = now
            prunePublisherConnEventsLocked(now)
            val existing = activeStreamLocked(parsed) ?: return@synchronized false
            previousPublisherConnId = existing.publisherConnId
            if (!publisherConnId.isNullOrBlank() &&
                !existing.publisherConnId.isNullOrBlank() &&
                existing.publisherConnId != publisherConnId
            ) {
                rememberPublisherClosureLocked(parsed.sourcePath, publisherConnId, now)
                return@synchronized true
            }
            rememberPublisherClosureLocked(parsed.sourcePath, publisherConnId ?: existing.publisherConnId, now)
            false
        }
        val publisherRotated =
            !publisherConnId.isNullOrBlank() &&
                !previousPublisherConnId.isNullOrBlank() &&
                previousPublisherConnId != publisherConnId
        val effectivePublisherConnId = publisherConnId ?: previousPublisherConnId
        if (ignoreStaleConn) {
            UpstreamTimingRegistry.recordBoundary(
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                boundary = "stream_error",
                outcome = "ignored_stale_publisher_conn",
                publisherConnId = effectivePublisherConnId,
                previousPublisherConnId = previousPublisherConnId,
                publisherRotated = publisherRotated,
                reason = reason,
                observedAtMs = observedAtMs,
            )
            CTDebug(
                TAG,
                "onStreamError(${parsed.sourcePath} -> ${parsed.designator}): ignoring stale publisher conn $publisherConnId"
            )
            return
        }
        val matchesActiveSource = synchronized(lock) {
            hasMatchingSourcePathLocked(parsed)
        }
        if (!matchesActiveSource) {
            UpstreamTimingRegistry.recordBoundary(
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                boundary = "stream_error",
                outcome = "ignored_mismatched_source",
                publisherConnId = effectivePublisherConnId,
                previousPublisherConnId = previousPublisherConnId,
                publisherRotated = publisherRotated,
                reason = reason,
                observedAtMs = observedAtMs,
            )
            CTDebug(TAG, "onStreamError(${parsed.sourcePath} -> ${parsed.designator}): ignoring mismatched source path.")
            return
        }
        val (updated, deferred) = synchronized(lock) {
            val result = StreamAdmissionPolicy.markError(
                snapshotLocked(),
                parsed.designator,
                nowMsProvider(),
                reason = reason,
            )
            applyStateLocked(result.state)
            result.changed to (result.existed && !result.changed)
        }
        if (deferred) {
            UpstreamTimingRegistry.recordBoundary(
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                boundary = "stream_error",
                outcome = "deferred_transient_close",
                publisherConnId = effectivePublisherConnId,
                previousPublisherConnId = previousPublisherConnId,
                publisherRotated = publisherRotated,
                reason = reason,
                observedAtMs = observedAtMs,
            )
            reason?.let {
                CTWarn(TAG, "onStreamError(${parsed.sourcePath} -> ${parsed.designator}): deferring transient publisher close while stream remains active: $it")
            }
            return
        }
        if (!updated) {
            UpstreamTimingRegistry.recordBoundary(
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                boundary = "stream_error",
                outcome = "ignored_orphan_transition",
                publisherConnId = effectivePublisherConnId,
                previousPublisherConnId = previousPublisherConnId,
                publisherRotated = publisherRotated,
                reason = reason,
                observedAtMs = observedAtMs,
            )
            CTDebug(TAG, "onStreamError(${parsed.sourcePath} -> ${parsed.designator}): ignoring orphan error transition.")
            return
        }
        UpstreamTimingRegistry.recordBoundary(
            designator = parsed.designator,
            sourcePath = parsed.sourcePath,
            boundary = "stream_error",
            outcome = "state_error",
            publisherConnId = effectivePublisherConnId,
            previousPublisherConnId = previousPublisherConnId,
            publisherRotated = publisherRotated,
            reason = reason,
            observedAtMs = observedAtMs,
        )
        reason?.let {
            CTWarn(TAG, "onStreamError(${parsed.sourcePath} -> ${parsed.designator}): $it")
        }
        CTDebug(TAG, "onStreamError(${parsed.sourcePath} -> ${parsed.designator}): state:${StreamState.ERROR.name}")
    }

    fun onStreamStarted(path: String, publisherConnId: String? = null) {
        val parsed = parseStreamPath(path)
        var rotationSummary: String? = null
        var observedAtMs = 0L
        var previousPublisherConnId: String? = null
        var publisherRotated = false
        val outcome = synchronized(lock) {
            val now = nowMsProvider()
            observedAtMs = now
            prunePublisherConnEventsLocked(now)
            val existing = activeStreamLocked(parsed)
            previousPublisherConnId = existing?.publisherConnId
            if (!publisherConnId.isNullOrBlank() &&
                !previousPublisherConnId.isNullOrBlank() &&
                previousPublisherConnId != publisherConnId
            ) {
                publisherRotated = true
            }
            if (existing != null && existing.state == StreamState.LIVE) {
                if (!publisherConnId.isNullOrBlank() && existing.publisherConnId == publisherConnId) {
                    return@synchronized "duplicate"
                }
                updateActiveStreamLocked(
                    existing.copy(
                        sourcePath = parsed.sourcePath,
                        controllerProfile = parsed.controllerProfile,
                        publisherConnId = publisherConnId ?: existing.publisherConnId,
                    )
                )
                recentPublisherHandoffs.remove(parsed.sourcePath)
                recentPublisherClosures.remove(parsed.sourcePath)
                if (!publisherConnId.isNullOrBlank() &&
                    !existing.publisherConnId.isNullOrBlank() &&
                    existing.publisherConnId != publisherConnId
                ) {
                    rotationSummary = "${existing.publisherConnId} -> $publisherConnId"
                }
                return@synchronized "preserved"
            }
            val result = StreamAdmissionPolicy.admit(
                state = snapshotLocked(),
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                controllerProfile = parsed.controllerProfile,
                publisherConnId = publisherConnId,
                targetState = StreamState.LIVE,
                nowMs = now,
                maxSimultaneousStreams = MAX_SIMULTANEOUS_STREAMS,
                staleConnectingMs = STALE_CONNECTING_MS,
                staleErrorMs = STALE_ERROR_MS,
                admissionGuard = admissionGuard,
            )
            applyStateLocked(result.state)
            recentPublisherHandoffs.remove(parsed.sourcePath)
            recentPublisherClosures.remove(parsed.sourcePath)
            result.evictedPaths.forEach { stalePath ->
                CTWarn(TAG, "Evicting stale stream '$stalePath' to prevent capacity lockout.")
            }
            if (!result.admitted && result.shouldNotifyRejection) {
                val msg = when (result.rejectionReason) {
                    "capacity" -> "Rejecting stream '${parsed.designator}': max $MAX_SIMULTANEOUS_STREAMS active streams."
                    else -> "Rejecting stream '${parsed.designator}': ${result.rejectionReason ?: "admission guard"}."
                }
                CTWarn(TAG, msg)
                ShowToast(result.rejectionToast ?: "Rejected stream ${parsed.designator}.")
            }
            if (result.admitted) "admitted" else "rejected"
        }
        UpstreamTimingRegistry.recordBoundary(
            designator = parsed.designator,
            sourcePath = parsed.sourcePath,
            boundary = "stream_started",
            outcome = outcome,
            publisherConnId = publisherConnId ?: previousPublisherConnId,
            previousPublisherConnId = previousPublisherConnId,
            publisherRotated = publisherRotated,
            observedAtMs = observedAtMs,
        )
        if (outcome == "admitted") {
            CTDebug(TAG, "onStreamStarted(${parsed.sourcePath} -> ${parsed.designator}): state:${StreamState.LIVE.name}")
        } else if (outcome == "preserved") {
            val connDetail = rotationSummary?.let { " publisherRotated=$it" }
                ?: publisherConnId?.let { " publisherConnId=$it" }
                ?: ""
            CTDebug(TAG, "onStreamStarted(${parsed.sourcePath} -> ${parsed.designator}): preserving LIVE state across same-path reconnect$connDetail")
        } else if (outcome == "duplicate") {
            CTDebug(
                TAG,
                "onStreamStarted(${parsed.sourcePath} -> ${parsed.designator}): ignoring duplicate publisher conn $publisherConnId"
            )
        }
    }

    fun onStreamPublisherHandoff(path: String, publisherConnId: String? = null) {
        val parsed = parseStreamPath(path)
        var observedAtMs = 0L
        var existingPublisherConnId: String? = null
        val existed = synchronized(lock) {
            val now = nowMsProvider()
            observedAtMs = now
            prunePublisherConnEventsLocked(now)
            existingPublisherConnId = activeStreamLocked(parsed)?.publisherConnId
            rememberPublisherHandoffLocked(parsed.sourcePath, publisherConnId, now)
            hasMatchingSourcePathLocked(parsed)
        }
        val publisherRotated =
            !publisherConnId.isNullOrBlank() &&
                !existingPublisherConnId.isNullOrBlank() &&
                existingPublisherConnId != publisherConnId
        UpstreamTimingRegistry.recordBoundary(
            designator = parsed.designator,
            sourcePath = parsed.sourcePath,
            boundary = "stream_publisher_handoff",
            outcome = if (existed) "preserved" else "no_active_stream",
            publisherConnId = publisherConnId ?: existingPublisherConnId,
            previousPublisherConnId = existingPublisherConnId,
            publisherRotated = publisherRotated,
            observedAtMs = observedAtMs,
        )
        if (!existed) {
            CTDebug(
                TAG,
                "onStreamPublisherHandoff(${parsed.sourcePath} -> ${parsed.designator}): no active stream to preserve publisherConnId=$publisherConnId."
            )
            return
        }
        CTDebug(
            TAG,
            "onStreamPublisherHandoff(${parsed.sourcePath} -> ${parsed.designator}): preserving current state during MediaMTX publisher rotation publisherConnId=$publisherConnId."
        )
    }

    fun onStreamStopped(path: String, publisherConnId: String? = null) {
        val parsed = parseStreamPath(path)
        var observedAtMs = 0L
        var previousPublisherConnId: String? = null
        val ignoreStaleConn = synchronized(lock) {
            val now = nowMsProvider()
            observedAtMs = now
            prunePublisherConnEventsLocked(now)
            val existing = activeStreamLocked(parsed) ?: return@synchronized false
            previousPublisherConnId = existing.publisherConnId
            if (!publisherConnId.isNullOrBlank() &&
                !existing.publisherConnId.isNullOrBlank() &&
                existing.publisherConnId != publisherConnId
            ) {
                rememberPublisherClosureLocked(parsed.sourcePath, publisherConnId, now)
                return@synchronized true
            }
            rememberPublisherClosureLocked(parsed.sourcePath, publisherConnId ?: existing.publisherConnId, now)
            false
        }
        val publisherRotated =
            !publisherConnId.isNullOrBlank() &&
                !previousPublisherConnId.isNullOrBlank() &&
                previousPublisherConnId != publisherConnId
        val effectivePublisherConnId = publisherConnId ?: previousPublisherConnId
        if (ignoreStaleConn) {
            UpstreamTimingRegistry.recordBoundary(
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                boundary = "stream_stopped",
                outcome = "ignored_stale_publisher_conn",
                publisherConnId = effectivePublisherConnId,
                previousPublisherConnId = previousPublisherConnId,
                publisherRotated = publisherRotated,
                observedAtMs = observedAtMs,
            )
            CTDebug(
                TAG,
                "onStreamStopped(${parsed.sourcePath} -> ${parsed.designator}): ignoring stale publisher conn $publisherConnId."
            )
            return
        }
        val matchesActiveSource = synchronized(lock) {
            hasMatchingSourcePathLocked(parsed)
        }
        if (!matchesActiveSource) {
            UpstreamTimingRegistry.recordBoundary(
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                boundary = "stream_stopped",
                outcome = "ignored_mismatched_source",
                publisherConnId = effectivePublisherConnId,
                previousPublisherConnId = previousPublisherConnId,
                publisherRotated = publisherRotated,
                observedAtMs = observedAtMs,
            )
            CTDebug(TAG, "onStreamStopped(${parsed.sourcePath} -> ${parsed.designator}): ignoring mismatched source path.")
            return
        }
        val existed = synchronized(lock) {
            val result = StreamAdmissionPolicy.markStopped(snapshotLocked(), parsed.designator)
            applyStateLocked(result.state)
            result.existed
        }
        if (!existed) {
            UpstreamTimingRegistry.recordBoundary(
                designator = parsed.designator,
                sourcePath = parsed.sourcePath,
                boundary = "stream_stopped",
                outcome = "already_absent",
                publisherConnId = effectivePublisherConnId,
                previousPublisherConnId = previousPublisherConnId,
                publisherRotated = publisherRotated,
                observedAtMs = observedAtMs,
            )
            CTDebug(TAG, "onStreamStopped(${parsed.sourcePath} -> ${parsed.designator}): already absent.")
            return
        }
        UpstreamTimingRegistry.recordBoundary(
            designator = parsed.designator,
            sourcePath = parsed.sourcePath,
            boundary = "stream_stopped",
            outcome = "stopped",
            publisherConnId = effectivePublisherConnId,
            previousPublisherConnId = previousPublisherConnId,
            publisherRotated = publisherRotated,
            observedAtMs = observedAtMs,
        )
        CTDebug(TAG, "onStreamStopped(${parsed.sourcePath} -> ${parsed.designator}): state:${StreamState.STOPPED.name}")
    }

    @JvmStatic
    fun onServerStopped() {
        synchronized(lock) {
            var state = snapshotLocked()
            state.active.values.filter { !it.isLocalPlayback }.forEach {
                state = StreamAdmissionPolicy.markStopped(state, it.designator).state
            }
            applyStateLocked(state)
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            _streams.value = emptyMap()
            rejectedPaths.clear()
            stateChangedAtMs.clear()
            recentPublisherHandoffs.clear()
            recentPublisherClosures.clear()
            nowMsProvider = { System.currentTimeMillis() }
        }
        UpstreamTimingRegistry.resetForTests()
        StreamFlightActivityRegistry.resetForTests()
    }
}
