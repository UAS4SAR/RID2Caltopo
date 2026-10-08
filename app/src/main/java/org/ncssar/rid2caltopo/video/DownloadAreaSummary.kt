package org.ncssar.rid2caltopo.video

import kotlin.math.*

/** Measurements of the download bounding rectangle, not the enclosed boundary polygon. */
internal data class DownloadAreaSummary(val north: Double, val south: Double, val west: Double, val east: Double) {
    private val radiusMiles = 3958.7613
    private val latitudeSpan = Math.toRadians(abs(north - south))
    private val longitudeSpan = Math.toRadians(if (east >= west) east - west else east - west + 360)
    val latitude = (north + south) / 2
    val longitude = ((west + Math.toDegrees(longitudeSpan) / 2 + 540) % 360) - 180
    val widthMiles = radiusMiles * longitudeSpan * cos(Math.toRadians(latitude))
    val heightMiles = radiusMiles * latitudeSpan
    val squareMiles = radiusMiles * radiusMiles * longitudeSpan * abs(sin(Math.toRadians(north)) - sin(Math.toRadians(south)))
}
