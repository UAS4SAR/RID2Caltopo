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
