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

/**
 * Render objects are prepared on a worker. When [append] is provided and [points] only grows
 * as a prefix extension of the cached list, [append] mutates the existing render instead of
 * allocating a new one (osmdroid Polyline.addPoint on Main after attach, or worker rebuild).
 */
internal class PreparedTrackRender<T>(val points: List<LocalTrackPoint>, val render: T)

internal suspend fun <T> prepareTrackRenders(
    inputs: Map<String, List<LocalTrackPoint>>,
    previous: Map<String, PreparedTrackRender<T>>,
    create: (List<LocalTrackPoint>) -> T,
    append: ((existing: T, newPoints: List<LocalTrackPoint>) -> Unit)? = null,
): Map<String, PreparedTrackRender<T>> = withContext(Dispatchers.Default) {
    buildMap {
        for ((key, points) in inputs) {
            currentCoroutineContext().ensureActive()
            if (points.size < 2) continue
            val cached = previous[key]
            when {
                cached == null -> put(key, PreparedTrackRender(points, create(points)))
                cached.points === points -> put(key, cached)
                append != null &&
                    cached.points.size < points.size &&
                    cached.points.indices.all { idx -> cached.points[idx] == points[idx] } -> {
                    val delta = points.subList(cached.points.size, points.size)
                    append(cached.render, delta)
                    put(key, PreparedTrackRender(points, cached.render))
                }
                else -> put(key, PreparedTrackRender(points, create(points)))
            }
        }
    }
}
