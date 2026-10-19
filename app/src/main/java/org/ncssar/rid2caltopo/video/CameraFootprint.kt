package org.ncssar.rid2caltopo.video

import kotlin.math.*

/** Immutable launch-relative snapshot; no raw SEI altitude is treated as MSL. */
internal data class CameraFootprintInput(
    val latitude: Double, val longitude: Double,
    val launchLatitude: Double, val launchLongitude: Double, val height: Double,
    val azimuth: Double, val tilt: Double, val horizontalFov: Double, val verticalFov: Double,
)
internal data class CameraFootprintVertex(
    val latitude: Double, val longitude: Double, val clipped: Boolean, val corner: Boolean,
)

internal object CameraFootprintGeometry {
    const val MAX_RANGE = 3000.0
    private const val EARTH_RADIUS = 6378137.0

    /** Pinhole rays, not independent yaw/pitch offsets (which distort wide-angle corners). */
    fun project(input: CameraFootprintInput, elevation: ((Double, Double) -> Double?)? = null,
                cancelled: () -> Boolean = { false },
                edgeSubdivisions: Int = if (elevation == null) 1 else 8): List<CameraFootprintVertex> {
        with(input) {
            if (!listOf(latitude, longitude, launchLatitude, launchLongitude, height, azimuth,
                    tilt, horizontalFov, verticalFov).all { it.isFinite() } || height <= 0 ||
                horizontalFov !in 0.01..179.0 || verticalFov !in 0.01..179.0 ||
                latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return emptyList()
            val launchGround = elevation?.invoke(launchLatitude, launchLongitude)
            if (elevation != null && launchGround == null) return emptyList()
            val aircraftZ = (launchGround ?: 0.0) + height
            val yaw = Math.toRadians(azimuth); val pitch = Math.toRadians(tilt)
            val fx = sin(yaw) * cos(pitch); val fy = cos(yaw) * cos(pitch); val fz = sin(pitch)
            val rx = cos(yaw); val ry = -sin(yaw)
            val ux = -sin(yaw) * sin(pitch); val uy = -cos(yaw) * sin(pitch); val uz = cos(pitch)
            val h = tan(Math.toRadians(horizontalFov / 2)); val v = tan(Math.toRadians(verticalFov / 2))
            val corners = listOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0)
            val subdivisions = edgeSubdivisions.coerceIn(1, 8)
            return buildList {
                for (edge in 0..3) for (j in 0 until subdivisions) {
                    if (cancelled()) return emptyList()
                    val a = corners[edge]; val b = corners[(edge + 1) % 4]; val t = j.toDouble() / subdivisions
                    val x = (a.first + (b.first - a.first) * t) * h
                    val y = (a.second + (b.second - a.second) * t) * v
                    val dx = fx + rx*x + ux*y; val dy = fy + ry*x + uy*y; val dz = fz + uz*y
                    val norm = sqrt(dx*dx + dy*dy + dz*dz)
                    fun coordinate(distance: Double): Pair<Double, Double> =
                        latitude + Math.toDegrees(dy/norm*distance/EARTH_RADIUS) to
                            longitude + Math.toDegrees(dx/norm*distance/(EARTH_RADIUS*cos(Math.toRadians(latitude))))
                    fun clearance(distance: Double): Double? {
                        val p = coordinate(distance)
                        val ground = elevation?.invoke(p.first, p.second) ?: if (elevation == null) 0.0 else return null
                        return aircraftZ + dz/norm*distance - ground
                    }
                    var distance: Double
                    var clipped: Boolean
                    if (elevation == null) {
                        val hit = if (dz < -1e-8) -height*norm/dz else Double.POSITIVE_INFINITY
                        distance = min(hit, MAX_RANGE); clipped = hit > MAX_RANGE
                    } else {
                        val initial = clearance(0.0) ?: return emptyList()
                        if (initial <= 0) return emptyList()
                        var previous = 0.0
                        distance = MAX_RANGE; clipped = true
                        var step = 5.0
                        while (step <= MAX_RANGE) {
                            if (cancelled()) return emptyList()
                            val c = clearance(step)
                            if (c == null || c <= 0) {
                                var lo = previous; var hi = step
                                repeat(10) {
                                    val mid = (lo+hi)/2
                                    val value = clearance(mid)
                                    if (value == null || value <= 0) hi = mid else lo = mid
                                }
                                distance = (lo+hi)/2; clipped = c == null
                                break
                            }
                            previous = step; step += 5.0
                        }
                    }
                    val p = coordinate(distance)
                    add(CameraFootprintVertex(p.first, p.second, clipped, j == 0))
                }
            }
        }
    }
}

/** Corner display never waits for (or disappears with) the terrain boundary. */
internal data class CameraFootprintDrawing(
    val corners: List<CameraFootprintVertex>, val boundary: List<CameraFootprintVertex>,
)
internal fun cameraFootprintDrawing(input: CameraFootprintInput, terrain: List<CameraFootprintVertex>?): CameraFootprintDrawing {
    val boundary = terrain.orEmpty()
    val corners = boundary.filter { it.corner }.takeIf { it.size == 4 }
        ?: CameraFootprintGeometry.project(input)
    return CameraFootprintDrawing(corners, boundary)
}

internal data class CameraFootprintScreenPoint(val x: Double, val y: Double)
internal data class CameraFootprintCornerStroke(val start: CameraFootprintScreenPoint, val end: CameraFootprintScreenPoint)

/**
 * A terrain pass is keyed by the pose it sampled. Exact [CameraFootprintInput] equality
 * never holds while SEI is moving, so the outline stayed hidden. These limits cover one
 * 500 ms pass during a slow orbit and reject a pose that has clearly moved on.
 */
internal const val CAMERA_FOOTPRINT_TERRAIN_POSITION_METERS = 30.0
internal const val CAMERA_FOOTPRINT_TERRAIN_HEIGHT_METERS = 10.0
internal const val CAMERA_FOOTPRINT_TERRAIN_AZIMUTH_DEGREES = 12.0
internal const val CAMERA_FOOTPRINT_TERRAIN_TILT_DEGREES = 5.0
internal const val CAMERA_FOOTPRINT_TERRAIN_FOV_DEGREES = 1.5
internal const val CAMERA_FOOTPRINT_TERRAIN_LAUNCH_METERS = 1.0

internal fun cameraFootprintDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    if (!listOf(lat1, lon1, lat2, lon2).all { it.isFinite() }) return Double.POSITIVE_INFINITY
    val earth = 6378137.0
    val meanLat = Math.toRadians((lat1 + lat2) / 2.0)
    var dLon = Math.toRadians(lon2 - lon1)
    if (dLon > PI) dLon -= 2.0 * PI
    if (dLon < -PI) dLon += 2.0 * PI
    return earth * hypot(dLon * cos(meanLat), Math.toRadians(lat2 - lat1))
}

internal fun cameraFootprintAngleDeltaDegrees(a: Double, b: Double): Double {
    if (!a.isFinite() || !b.isFinite()) return Double.POSITIVE_INFINITY
    val delta = abs(a - b) % 360.0
    return min(delta, 360.0 - delta)
}

internal fun cameraFootprintTerrainReusable(cached: CameraFootprintInput, current: CameraFootprintInput): Boolean {
    if (cameraFootprintDistanceMeters(cached.launchLatitude, cached.launchLongitude, current.launchLatitude, current.launchLongitude) > CAMERA_FOOTPRINT_TERRAIN_LAUNCH_METERS) return false
    if (cameraFootprintDistanceMeters(cached.latitude, cached.longitude, current.latitude, current.longitude) > CAMERA_FOOTPRINT_TERRAIN_POSITION_METERS) return false
    if (abs(cached.height - current.height) > CAMERA_FOOTPRINT_TERRAIN_HEIGHT_METERS) return false
    if (cameraFootprintAngleDeltaDegrees(cached.azimuth, current.azimuth) > CAMERA_FOOTPRINT_TERRAIN_AZIMUTH_DEGREES) return false
    if (abs(cached.tilt - current.tilt) > CAMERA_FOOTPRINT_TERRAIN_TILT_DEGREES) return false
    if (abs(cached.horizontalFov - current.horizontalFov) > CAMERA_FOOTPRINT_TERRAIN_FOV_DEGREES) return false
    if (abs(cached.verticalFov - current.verticalFov) > CAMERA_FOOTPRINT_TERRAIN_FOV_DEGREES) return false
    return true
}

internal fun cameraFootprintTerrainForDisplay(
    cached: Pair<CameraFootprintInput, List<CameraFootprintVertex>>?,
    current: CameraFootprintInput,
): List<CameraFootprintVertex>? {
    val (pose, vertices) = cached ?: return null
    if (vertices.isEmpty() || !cameraFootprintTerrainReusable(pose, current)) return null
    return vertices
}

/** Keep the last finished outline when a pass is cancelled or still in budget. */
internal fun mergeCameraFootprintTerrain(
    previous: Map<String, Pair<CameraFootprintInput, List<CameraFootprintVertex>>>,
    computed: Map<String, Pair<CameraFootprintInput, List<CameraFootprintVertex>>>,
    current: Map<String, CameraFootprintInput>,
): Map<String, Pair<CameraFootprintInput, List<CameraFootprintVertex>>> {
    if (current.isEmpty()) return emptyMap()
    val merged = LinkedHashMap<String, Pair<CameraFootprintInput, List<CameraFootprintVertex>>>()
    for ((id, input) in current) {
        val fresh = computed[id]
        if (fresh != null && fresh.second.isNotEmpty()) {
            merged[id] = fresh
        } else {
            val prior = previous[id]
            if (prior != null && prior.second.isNotEmpty() && cameraFootprintTerrainReusable(prior.first, input)) {
                merged[id] = prior
            }
        }
    }
    return merged
}

/** The closed corner outline follows [input]. [terrain] is the terrain polygon, so a slightly older pass cannot freeze the preview. */
internal fun cameraFootprintLiveDrawing(input: CameraFootprintInput, terrain: List<CameraFootprintVertex>?): CameraFootprintDrawing {
    val boundary = terrain.orEmpty()
    val corners = CameraFootprintGeometry.project(input).ifEmpty {
        boundary.filter { it.corner }.takeIf { it.size == 4 }.orEmpty()
    }
    return CameraFootprintDrawing(corners, boundary)
}

/**
 * AD's scan zone is a centered fraction of the video frame (the same rectangle as the on-video
 * guide), clamped to 0.5–1 like the detector. Null means AD is off and the full frame is used.
 * The rectangle is mapped through the pinhole: a centered crop scales tan(FOV/2), which keeps
 * the frame aspect. Scaling the angle itself would squash a wide FOV.
 */
internal fun cameraFootprintAdScanRect(zoneFraction: Double): CameraFootprintFrameRect? {
    if (!zoneFraction.isFinite()) return null
    val zone = zoneFraction.coerceIn(0.5, 1.0)
    val margin = (1.0 - zone) / 2.0
    return CameraFootprintFrameRect(margin, margin, 1.0 - margin, 1.0 - margin)
}

internal data class CameraFootprintFrameRect(
    val left: Double, val top: Double, val right: Double, val bottom: Double,
)

/**
 * Camera-plane offsets of a video rectangle inside the full FOV. Origin is top-left, y down;
 * camera x is right and y is up. Order matches [CameraFootprintGeometry.project]:
 * left-top, right-top, right-bottom, left-bottom.
 */
internal fun cameraFootprintSubframeRayOffsets(
    horizontalFovDeg: Double,
    verticalFovDeg: Double,
    rect: CameraFootprintFrameRect,
): List<Pair<Double, Double>>? {
    if (!listOf(horizontalFovDeg, verticalFovDeg, rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() }) return null
    if (horizontalFovDeg !in 0.01..179.0 || verticalFovDeg !in 0.01..179.0) return null
    if (rect.left < 0.0 || rect.top < 0.0 || rect.right > 1.0 || rect.bottom > 1.0) return null
    if (rect.right - rect.left < 1e-6 || rect.bottom - rect.top < 1e-6) return null
    val h = tan(Math.toRadians(horizontalFovDeg / 2.0))
    val v = tan(Math.toRadians(verticalFovDeg / 2.0))
    fun offset(u: Double, imageV: Double) = ((u - 0.5) * 2.0 * h) to ((0.5 - imageV) * 2.0 * v)
    return listOf(
        offset(rect.left, rect.top),
        offset(rect.right, rect.top),
        offset(rect.right, rect.bottom),
        offset(rect.left, rect.bottom),
    )
}

internal fun cameraFootprintApplyingAdScanZone(
    input: CameraFootprintInput,
    scanZoneFraction: Double?,
): CameraFootprintInput {
    val rect = scanZoneFraction?.let { cameraFootprintAdScanRect(it) } ?: return input
    if (rect.right - rect.left >= 1.0 - 1e-9) return input
    val offsets = cameraFootprintSubframeRayOffsets(input.horizontalFov, input.verticalFov, rect) ?: return input
    val horizontal = Math.toDegrees(2.0 * atan(abs(offsets[1].first)))
    val vertical = Math.toDegrees(2.0 * atan(abs(offsets[0].second)))
    if (horizontal !in 0.01..179.0 || vertical !in 0.01..179.0) return input
    return input.copy(horizontalFov = horizontal, verticalFov = vertical)
}

/** Stable per-aircraft key. A blank id is not stored, and a missing key stays off. */
internal fun cameraFootprintPreferenceKey(remoteId: String): String? {
    val normalized = remoteId.trim().uppercase(java.util.Locale.US)
    return normalized.ifBlank { null }
}

/** Two short arms point along the actual adjacent edges, including oblique views. */
internal fun cameraFootprintCornerStrokes(points: List<CameraFootprintScreenPoint>, length: Double): List<CameraFootprintCornerStroke> {
    if (points.size != 4 || !length.isFinite() || length <= 0) return emptyList()
    return buildList {
        points.indices.forEach { index ->
            val start = points[index]
            listOf((index + 3) % 4, (index + 1) % 4).forEach { adjacent ->
                val dx = points[adjacent].x - start.x
                val dy = points[adjacent].y - start.y
                val distance = hypot(dx, dy)
                if (distance.isFinite() && distance > 1e-6) {
                    val scale = min(length, distance * 0.4) / distance
                    add(CameraFootprintCornerStroke(start, CameraFootprintScreenPoint(start.x + dx * scale, start.y + dy * scale)))
                }
            }
        }
    }
}
