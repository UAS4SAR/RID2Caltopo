package org.ncssar.rid2caltopo.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Android equivalent of Apple `RidGeometry.relativePosition` (same radius and formulas). */
object RidGeometry {
    data class RelativePosition(val distanceMeters: Double, val bearingDegrees: Double)

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    @JvmStatic
    fun relativePosition(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): RelativePosition? {
        if (!fromLatitude.isFinite() || !fromLongitude.isFinite() || !toLatitude.isFinite() || !toLongitude.isFinite()) return null
        if (fromLatitude !in -90.0..90.0 || toLatitude !in -90.0..90.0 ||
            fromLongitude !in -180.0..180.0 || toLongitude !in -180.0..180.0) return null
        val originLatitude = Math.toRadians(fromLatitude)
        val destinationLatitude = Math.toRadians(toLatitude)
        val deltaLatitude = Math.toRadians(toLatitude - fromLatitude)
        val deltaLongitude = Math.toRadians(toLongitude - fromLongitude)
        val haversine = sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(originLatitude) * cos(destinationLatitude) * sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        val distance = EARTH_RADIUS_METERS * 2 * atan2(sqrt(haversine), sqrt(1 - haversine))
        val y = sin(deltaLongitude) * cos(destinationLatitude)
        val x = cos(originLatitude) * sin(destinationLatitude) -
            sin(originLatitude) * cos(destinationLatitude) * cos(deltaLongitude)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return RelativePosition(distance, bearing)
    }

    private val SIXTEEN_POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** 16-point compass name; separate from any 8-point helpers used elsewhere. */
    @JvmStatic
    fun cardinalDirection16(bearing: Double): String {
        if (!bearing.isFinite()) return "N"
        val normalized = ((bearing % 360) + 360) % 360
        return SIXTEEN_POINTS[((normalized + 11.25) / 22.5).toInt() % SIXTEEN_POINTS.size]
    }
}
