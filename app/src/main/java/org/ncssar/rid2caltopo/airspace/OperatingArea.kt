package org.ncssar.rid2caltopo.airspace

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import kotlin.math.ceil

object OperatingArea {
    const val radiusStatuteMiles: Double = 1.0
    const val radiusNm: Double = 0.868976
    val displayLabel: String get() = "${Units.current.miles(1.0, 0)} operating area"

    fun statuteMilesToNauticalMiles(statuteMiles: Double): Double =
        statuteMiles * radiusNm

    /**
     * FAA NMS accepts an integer nautical-mile query radius. Round up so the
     * server response fully covers the configured statute-mile radius, then
     * apply the exact statute-mile cutoff locally.
     */
    fun faaNotamQueryRadiusNm(radiusStatuteMiles: Int): Int =
        ceil(statuteMilesToNauticalMiles(radiusStatuteMiles.toDouble()))
            .toInt()
            .coerceAtLeast(1)
}
