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

/**
 * Footprint outline in ground coordinates.
 * Closed order is near-left, near-right, far-right, far-left.
 * Open (unbounded far edge): far-left → near-left → near-right → far-right, with no far edge.
 */
internal data class CameraFootprintOutline(
    val vertices: List<CameraFootprintVertex>,
    val closed: Boolean,
    val nearGroundMeters: Double = 0.0,
    val farGroundMeters: Double = 0.0,
    val clamped: Boolean = false,
) {
    val size: Int get() = vertices.size
    fun isEmpty(): Boolean = vertices.isEmpty()
    fun isNotEmpty(): Boolean = vertices.isNotEmpty()
    operator fun get(index: Int): CameraFootprintVertex = vertices[index]

    companion object {
        val EMPTY = CameraFootprintOutline(emptyList(), closed = true)
    }
}

internal object CameraFootprintGeometry {
    const val MAX_RANGE = 3000.0
    private const val EARTH_RADIUS = 6378137.0

    /**
     * Pinhole rays, not independent yaw/pitch offsets (which distort wide-angle corners).
     * Corner order: near-left, near-right, far-right, far-left.
     * When either far corner misses the ground within [MAX_RANGE], the outline is open:
     * near edge + both sides to max range, with no far edge.
     */
    fun project(input: CameraFootprintInput, elevation: ((Double, Double) -> Double?)? = null,
                cancelled: () -> Boolean = { false },
                edgeSubdivisions: Int = if (elevation == null) 1 else 8): CameraFootprintOutline {
        with(input) {
            if (!listOf(latitude, longitude, launchLatitude, launchLongitude, height, azimuth,
                    tilt, horizontalFov, verticalFov).all { it.isFinite() } || height <= 0 ||
                horizontalFov !in 0.01..179.0 || verticalFov !in 0.01..179.0 ||
                latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return CameraFootprintOutline.EMPTY
            val launchGround = elevation?.invoke(launchLatitude, launchLongitude)
            if (elevation != null && launchGround == null) return CameraFootprintOutline.EMPTY
            val aircraftZ = (launchGround ?: 0.0) + height
            val yaw = Math.toRadians(azimuth); val pitch = Math.toRadians(tilt)
            val fx = sin(yaw) * cos(pitch); val fy = cos(yaw) * cos(pitch); val fz = sin(pitch)
            val rx = cos(yaw); val ry = -sin(yaw)
            val ux = -sin(yaw) * sin(pitch); val uy = -cos(yaw) * sin(pitch); val uz = cos(pitch)
            val h = tan(Math.toRadians(horizontalFov / 2)); val v = tan(Math.toRadians(verticalFov / 2))
            // Near-left, near-right, far-right, far-left (image y=+1 is toward the far FOV edge).
            val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)
            val subdivisions = edgeSubdivisions.coerceIn(1, 8)

            data class Hit(
                val latitude: Double, val longitude: Double,
                val clipped: Boolean, val atMaxRange: Boolean, val groundMeters: Double,
            )

            fun rayDir(sx: Double, sy: Double): Triple<Double, Double, Double> {
                val x = sx * h; val y = sy * v
                val dx = fx + rx*x + ux*y; val dy = fy + ry*x + uy*y; val dz = fz + uz*y
                val norm = sqrt(dx*dx + dy*dy + dz*dz)
                return Triple(dx / norm, dy / norm, dz / norm)
            }

            fun coordinate(eastNorthUnit: Pair<Double, Double>, groundMeters: Double): Pair<Double, Double> {
                val (ex, ny) = eastNorthUnit
                return latitude + Math.toDegrees(ny * groundMeters / EARTH_RADIUS) to
                    longitude + Math.toDegrees(ex * groundMeters / (EARTH_RADIUS * cos(Math.toRadians(latitude))))
            }

            /** Place a tip at horizontal [MAX_RANGE] along the ray's ground azimuth. */
            fun horizontalTip(dir: Triple<Double, Double, Double>): Hit {
                val horiz = hypot(dir.first, dir.second)
                val (ex, ny) = if (horiz > 1e-8) (dir.first / horiz) to (dir.second / horiz) else {
                    // Degenerate vertical ray: fall back to aircraft forward on the ground.
                    sin(yaw) to cos(yaw)
                }
                val p = coordinate(ex to ny, MAX_RANGE)
                return Hit(p.first, p.second, clipped = true, atMaxRange = true, groundMeters = MAX_RANGE)
            }

            fun hit(sx: Double, sy: Double): Hit? {
                if (cancelled()) return null
                val dir = rayDir(sx, sy)
                val (ndx, ndy, ndz) = dir
                fun slantCoordinate(distance: Double): Pair<Double, Double> =
                    latitude + Math.toDegrees(ndy * distance / EARTH_RADIUS) to
                        longitude + Math.toDegrees(ndx * distance / (EARTH_RADIUS * cos(Math.toRadians(latitude))))
                fun clearance(distance: Double): Double? {
                    val p = slantCoordinate(distance)
                    val ground = elevation?.invoke(p.first, p.second) ?: if (elevation == null) 0.0 else return null
                    return aircraftZ + ndz * distance - ground
                }
                val limit = MAX_RANGE
                if (elevation == null) {
                    val planeHit = if (ndz < -1e-8) -height / ndz else Double.POSITIVE_INFINITY
                    if (planeHit > limit) return horizontalTip(dir)
                    val p = slantCoordinate(planeHit)
                    val groundMeters = planeHit * hypot(ndx, ndy)
                    return Hit(p.first, p.second, clipped = false, atMaxRange = false, groundMeters = groundMeters)
                }
                val initial = clearance(0.0) ?: return null
                if (initial <= 0) return null
                var previous = 0.0
                var step = 5.0
                while (step <= limit) {
                    if (cancelled()) return null
                    val c = clearance(step)
                    if (c == null || c <= 0) {
                        var lo = previous; var hi = step
                        repeat(10) {
                            val mid = (lo + hi) / 2
                            val value = clearance(mid)
                            if (value == null || value <= 0) hi = mid else lo = mid
                        }
                        val distance = (lo + hi) / 2
                        val p = slantCoordinate(distance)
                        val groundMeters = distance * hypot(ndx, ndy)
                        // Coverage hole vs ground hit: only max-range misses open the far edge.
                        return Hit(p.first, p.second, clipped = c == null, atMaxRange = false, groundMeters = groundMeters)
                    }
                    previous = step; step += 5.0
                }
                return horizontalTip(dir)
            }

            fun vertex(hit: Hit, corner: Boolean) =
                CameraFootprintVertex(hit.latitude, hit.longitude, hit.clipped, corner)

            val cornerHits = Array(4) { i ->
                hit(corners[i].first, corners[i].second) ?: return CameraFootprintOutline.EMPTY
            }
            // Unbounded when a far corner is above the horizon or beyond max range (not DEM holes).
            val closed = !cornerHits[2].atMaxRange && !cornerHits[3].atMaxRange
            val nearGround = (cornerHits[0].groundMeters + cornerHits[1].groundMeters) / 2.0
            val clamped = cornerHits[2].atMaxRange || cornerHits[3].atMaxRange
            // Open far tips sit at horizontal max range along each far-corner azimuth.
            val farLeftTip = if (cornerHits[3].atMaxRange) horizontalTip(rayDir(corners[3].first, corners[3].second)) else cornerHits[3]
            val farRightTip = if (cornerHits[2].atMaxRange) horizontalTip(rayDir(corners[2].first, corners[2].second)) else cornerHits[2]
            val farGround = (farLeftTip.groundMeters + farRightTip.groundMeters) / 2.0

            val vertices = buildList {
                if (closed) {
                    // NL → NR → FR → FL, reusing precomputed corners; subdivide edges for terrain.
                    for (edge in 0..3) {
                        val a = corners[edge]; val b = corners[(edge + 1) % 4]
                        for (j in 0 until subdivisions) {
                            if (cancelled()) return CameraFootprintOutline.EMPTY
                            if (j == 0) {
                                add(vertex(cornerHits[edge], corner = true))
                            } else {
                                val t = j.toDouble() / subdivisions
                                val sample = hit(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
                                    ?: return CameraFootprintOutline.EMPTY
                                add(vertex(sample, corner = false))
                            }
                        }
                    }
                } else {
                    // Open U: far-left tip → near-left → near-right → far-right tip. No far edge.
                    // Sides follow ground chords (not sky FOV rays) so the outline cannot fold.
                    fun appendGroundChord(start: Hit, end: Hit, startCorner: Boolean, endCorner: Boolean) {
                        for (j in 0 until subdivisions) {
                            val t = j.toDouble() / subdivisions
                            val lat = start.latitude + (end.latitude - start.latitude) * t
                            val lon = start.longitude + (end.longitude - start.longitude) * t
                            val clipped = start.clipped || end.clipped
                            add(CameraFootprintVertex(lat, lon, clipped, j == 0 && startCorner))
                        }
                        if (endCorner) add(vertex(end, corner = true))
                    }
                    appendGroundChord(farLeftTip, cornerHits[0], startCorner = true, endCorner = true)
                    // Near edge: NL already added; walk toward NR without duplicating NL.
                    for (j in 1 until subdivisions) {
                        val t = j.toDouble() / subdivisions
                        val a = corners[0]; val b = corners[1]
                        val sample = hit(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
                            ?: return CameraFootprintOutline.EMPTY
                        add(vertex(sample, corner = false))
                    }
                    add(vertex(cornerHits[1], corner = true))
                    // Right side NR → far-right tip (NR already present).
                    for (j in 1 until subdivisions) {
                        val t = j.toDouble() / subdivisions
                        val lat = cornerHits[1].latitude + (farRightTip.latitude - cornerHits[1].latitude) * t
                        val lon = cornerHits[1].longitude + (farRightTip.longitude - cornerHits[1].longitude) * t
                        add(CameraFootprintVertex(lat, lon, true, false))
                    }
                    add(vertex(farRightTip, corner = true))
                }
            }
            if (vertices.size < 3) return CameraFootprintOutline.EMPTY
            return CameraFootprintOutline(
                vertices = vertices,
                closed = closed,
                nearGroundMeters = nearGround,
                farGroundMeters = farGround,
                clamped = clamped,
            )
        }
    }
}

/** Non-adjacent edge crossings in a local ENU frame. Open outlines do not wrap. */
internal fun cameraFootprintOutlineSelfIntersects(outline: CameraFootprintOutline): Boolean {
    val vertices = outline.vertices
    if (vertices.size < 4) return false
    val originLat = vertices[0].latitude
    val originLon = vertices[0].longitude
    if (!originLat.isFinite() || !originLon.isFinite()) return false
    val meanLat = Math.toRadians(originLat)
    val cosLat = cos(meanLat)
    fun xy(v: CameraFootprintVertex): Pair<Double, Double> {
        val east = Math.toRadians(v.longitude - originLon) * 6378137.0 * cosLat
        val north = Math.toRadians(v.latitude - originLat) * 6378137.0
        return east to north
    }
    fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx
    fun segmentsIntersect(
        a: Pair<Double, Double>, b: Pair<Double, Double>,
        c: Pair<Double, Double>, d: Pair<Double, Double>,
    ): Boolean {
        val abx = b.first - a.first; val aby = b.second - a.second
        val d1 = cross(abx, aby, c.first - a.first, c.second - a.second)
        val d2 = cross(abx, aby, d.first - a.first, d.second - a.second)
        val cdx = d.first - c.first; val cdy = d.second - c.second
        val d3 = cross(cdx, cdy, a.first - c.first, a.second - c.second)
        val d4 = cross(cdx, cdy, b.first - c.first, b.second - c.second)
        return d1 * d2 < 0.0 && d3 * d4 < 0.0
    }
    val points = vertices.map(::xy)
    val n = points.size
    val segmentCount = if (outline.closed) n else n - 1
    for (i in 0 until segmentCount) {
        val a = points[i]; val b = points[if (outline.closed) (i + 1) % n else i + 1]
        val lastJ = if (outline.closed && i == 0) segmentCount - 1 else segmentCount
        for (j in i + 2 until lastJ) {
            if (outline.closed && i == 0 && j == segmentCount - 1) continue
            val c = points[j]; val d = points[if (outline.closed) (j + 1) % n else j + 1]
            if (segmentsIntersect(a, b, c, d)) return true
        }
    }
    return false
}

/**
 * Along-look ground depth in meters: distance between near-edge and far-edge midpoints
 * (or near midpoint to the open far-tip chord midpoint).
 */
internal fun cameraFootprintDepthMeters(outline: CameraFootprintOutline): Double {
    val corners = outline.vertices.filter { it.corner }
    if (corners.size < 4) {
        return (outline.farGroundMeters - outline.nearGroundMeters).coerceAtLeast(0.0)
    }
    val (nearA, nearB, farA, farB) = if (outline.closed) {
        // NL, NR, FR, FL
        listOf(corners[0], corners[1], corners[2], corners[3])
    } else {
        // FL, NL, NR, FR
        listOf(corners[1], corners[2], corners[0], corners[3])
    }
    val nearLat = (nearA.latitude + nearB.latitude) / 2.0
    val nearLon = (nearA.longitude + nearB.longitude) / 2.0
    val farLat = (farA.latitude + farB.latitude) / 2.0
    val farLon = (farA.longitude + farB.longitude) / 2.0
    return cameraFootprintDistanceMeters(nearLat, nearLon, farLat, farLon)
}

/** Corner display never waits for (or disappears with) the terrain boundary. */
internal data class CameraFootprintDrawing(
    val corners: CameraFootprintOutline, val boundary: CameraFootprintOutline,
)
internal fun cameraFootprintDrawing(input: CameraFootprintInput, terrain: CameraFootprintOutline?): CameraFootprintDrawing {
    val boundary = terrain?.takeIf { it.isNotEmpty() } ?: CameraFootprintOutline.EMPTY
    val terrainCorners = boundary.vertices.filter { it.corner }
    val corners = if (boundary.closed && terrainCorners.size == 4) {
        boundary.copy(vertices = terrainCorners)
    } else {
        CameraFootprintGeometry.project(input)
    }
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
/** Tilts above this (less negative) amplify small pose deltas at the far edge. */
internal const val CAMERA_FOOTPRINT_SHALLOW_TILT_DEGREES = -25.0
internal const val CAMERA_FOOTPRINT_SHALLOW_TERRAIN_POSITION_METERS = 8.0
internal const val CAMERA_FOOTPRINT_SHALLOW_TERRAIN_AZIMUTH_DEGREES = 4.0
internal const val CAMERA_FOOTPRINT_SHALLOW_TERRAIN_TILT_DEGREES = 2.0
internal const val CAMERA_FOOTPRINT_DEBUG_INTERVAL_MS = 5_000L

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
    val shallow = cached.tilt > CAMERA_FOOTPRINT_SHALLOW_TILT_DEGREES || current.tilt > CAMERA_FOOTPRINT_SHALLOW_TILT_DEGREES
    val positionLimit = if (shallow) CAMERA_FOOTPRINT_SHALLOW_TERRAIN_POSITION_METERS else CAMERA_FOOTPRINT_TERRAIN_POSITION_METERS
    val azimuthLimit = if (shallow) CAMERA_FOOTPRINT_SHALLOW_TERRAIN_AZIMUTH_DEGREES else CAMERA_FOOTPRINT_TERRAIN_AZIMUTH_DEGREES
    val tiltLimit = if (shallow) CAMERA_FOOTPRINT_SHALLOW_TERRAIN_TILT_DEGREES else CAMERA_FOOTPRINT_TERRAIN_TILT_DEGREES
    if (cameraFootprintDistanceMeters(cached.latitude, cached.longitude, current.latitude, current.longitude) > positionLimit) return false
    if (abs(cached.height - current.height) > CAMERA_FOOTPRINT_TERRAIN_HEIGHT_METERS) return false
    if (cameraFootprintAngleDeltaDegrees(cached.azimuth, current.azimuth) > azimuthLimit) return false
    if (abs(cached.tilt - current.tilt) > tiltLimit) return false
    if (abs(cached.horizontalFov - current.horizontalFov) > CAMERA_FOOTPRINT_TERRAIN_FOV_DEGREES) return false
    if (abs(cached.verticalFov - current.verticalFov) > CAMERA_FOOTPRINT_TERRAIN_FOV_DEGREES) return false
    return true
}

internal fun cameraFootprintTerrainForDisplay(
    cached: Pair<CameraFootprintInput, CameraFootprintOutline>?,
    current: CameraFootprintInput,
): CameraFootprintOutline? {
    val (pose, outline) = cached ?: return null
    if (outline.isEmpty() || !cameraFootprintTerrainReusable(pose, current)) return null
    return outline
}

/** Keep the last finished outline when a pass is cancelled or still in budget. */
internal fun mergeCameraFootprintTerrain(
    previous: Map<String, Pair<CameraFootprintInput, CameraFootprintOutline>>,
    computed: Map<String, Pair<CameraFootprintInput, CameraFootprintOutline>>,
    current: Map<String, CameraFootprintInput>,
): Map<String, Pair<CameraFootprintInput, CameraFootprintOutline>> {
    if (current.isEmpty()) return emptyMap()
    val merged = LinkedHashMap<String, Pair<CameraFootprintInput, CameraFootprintOutline>>()
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

/** The closed/open corner outline follows [input]. [terrain] is the terrain polygon, so a slightly older pass cannot freeze the preview. */
internal fun cameraFootprintLiveDrawing(input: CameraFootprintInput, terrain: CameraFootprintOutline?): CameraFootprintDrawing {
    val boundary = terrain?.takeIf { it.isNotEmpty() } ?: CameraFootprintOutline.EMPTY
    val flat = CameraFootprintGeometry.project(input)
    val corners = if (flat.isNotEmpty()) flat else {
        val terrainCorners = boundary.vertices.filter { it.corner }
        if (boundary.closed && terrainCorners.size == 4) boundary.copy(vertices = terrainCorners) else CameraFootprintOutline.EMPTY
    }
    return CameraFootprintDrawing(corners, boundary)
}

/**
 * Exactly one footprint per aircraft. Terrain replaces the live outline in place when it does
 * not self-intersect; otherwise the flat-plane outline is used. Callers must never stroke both
 * [CameraFootprintDrawing.corners] and [CameraFootprintDrawing.boundary].
 */
internal fun cameraFootprintDisplayOutline(drawing: CameraFootprintDrawing): CameraFootprintOutline {
    val boundary = drawing.boundary
    if (boundary.size >= 3 && !cameraFootprintOutlineSelfIntersects(boundary)) return boundary
    return if (drawing.corners.size >= 3) drawing.corners else CameraFootprintOutline.EMPTY
}

/**
 * In-place footprint overlays keyed by aircraft. Toggle-off / stream-end clears an id;
 * toggle-on and pose updates replace the single outline for that id.
 */
internal class CameraFootprintOverlayBook {
    private val byDrone = LinkedHashMap<String, CameraFootprintOutline>()

    fun sync(
        inputs: Map<String, CameraFootprintInput>,
        terrain: Map<String, Pair<CameraFootprintInput, CameraFootprintOutline>> = emptyMap(),
    ): Map<String, CameraFootprintOutline> {
        val next = LinkedHashMap<String, CameraFootprintOutline>()
        for ((id, input) in inputs) {
            val outline = cameraFootprintDisplayOutline(
                cameraFootprintLiveDrawing(input, cameraFootprintTerrainForDisplay(terrain[id], input)),
            )
            if (outline.size >= 3) next[id] = outline
        }
        byDrone.clear()
        byDrone.putAll(next)
        return LinkedHashMap(byDrone)
    }

    fun outlineCount(id: String): Int = if (byDrone.containsKey(id)) 1 else 0
    fun size(): Int = byDrone.size
    fun clear() { byDrone.clear() }
}

internal fun cameraFootprintDebugLine(
    source: String,
    gimbalTiltDeg: Double,
    headingDeg: Double,
    aglMeters: Double,
    nearGroundMeters: Double,
    farGroundMeters: Double,
    clamped: Boolean,
): String = java.lang.String.format(
    java.util.Locale.US,
    "CameraFootprint source=%s gimbalTiltDeg=%.1f headingDeg=%.1f aglM=%.1f nearM=%.0f farM=%.0f clamped=%s",
    source, gimbalTiltDeg, headingDeg, aglMeters, nearGroundMeters, farGroundMeters, clamped,
)

/** At most one debug line per drone per [CAMERA_FOOTPRINT_DEBUG_INTERVAL_MS]. */
internal class CameraFootprintDebugThrottle(
    private val intervalMs: Long = CAMERA_FOOTPRINT_DEBUG_INTERVAL_MS,
) {
    private val lastByDrone = HashMap<String, Long>()
    fun shouldLog(droneKey: String, nowMs: Long): Boolean {
        val last = lastByDrone[droneKey] ?: 0L
        if (nowMs - last < intervalMs) return false
        lastByDrone[droneKey] = nowMs
        return true
    }
    fun retain(activeKeys: Set<String>) {
        lastByDrone.keys.retainAll(activeKeys)
    }
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
 * camera x is right and y is up. Order matches [CameraFootprintGeometry.project] image corners
 * for a top-left origin rect: left-top, right-top, right-bottom, left-bottom.
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
