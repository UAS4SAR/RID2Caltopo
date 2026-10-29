package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.ncssar.rid2caltopo.data.WaypointTrack

internal data class LocalTrackSeedRequest(
    val designator: String,
    val segmentLabel: String,
    val recent: List<LocalTrackPoint>,
    val flight: List<LocalTrackPoint>,
    val lastSeededTime: Long?,
    val nowMs: Long,
    val sourceRevision: Any? = null,
)

internal data class PreparedLocalTrackSeed(
    val recent: List<LocalTrackPoint>,
    val flight: List<LocalTrackPoint>,
    val lastSeededTime: Long,
    val snapshotSize: Int,
)

/** One coalesced caller; no JSON scans or history merging on the UI dispatcher. */
internal suspend fun prepareLocalTrackSeed(
    request: LocalTrackSeedRequest,
    readSnapshot: (String) -> List<WaypointTrack.TrackPoint> = WaypointTrack::GetTrackPointsSnapshotForLabel,
): PreparedLocalTrackSeed? = withContext(Dispatchers.Default) {
    val snapshot = readSnapshot(request.segmentLabel)
    val timestamps = mutableMapOf<String, Long>()
    request.lastSeededTime?.let { timestamps[request.designator] = it }
    if (!shouldSeedLocalTrackSnapshotForDesignator(request.designator, snapshot, timestamps)) return@withContext null
    val recent = request.recent.toMutableList()
    val flight = request.flight.toMutableList()
    seedLocalTrackPointsFromSnapshot(request.designator, snapshot, request.nowMs, recent, flight)
    PreparedLocalTrackSeed(recent, flight, timestamps.getValue(request.designator), snapshot.size)
}

/** Compare snapshot identities, never walk a growing list on Main. */
internal fun localTrackSeedStillCurrent(
    request: LocalTrackSeedRequest,
    segmentLabel: String?,
    recent: List<LocalTrackPoint>,
    flight: List<LocalTrackPoint>,
): Boolean = request.segmentLabel == segmentLabel && request.recent === recent && request.flight === flight

/** Render objects are prepared once, then geometry is immutable while displayed. */
internal class PreparedTrackRender<T>(val points: List<LocalTrackPoint>, val render: T)

internal suspend fun <T> prepareTrackRenders(
    inputs: Map<String, List<LocalTrackPoint>>,
    previous: Map<String, PreparedTrackRender<T>>,
    create: (List<LocalTrackPoint>) -> T,
): Map<String, PreparedTrackRender<T>> = withContext(Dispatchers.Default) {
    buildMap {
        for ((key, points) in inputs) {
            currentCoroutineContext().ensureActive()
            if (points.size < 2) continue
            val cached = previous[key]
            put(key, if (cached?.points === points) cached else PreparedTrackRender(points, create(points)))
        }
    }
}
