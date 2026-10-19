package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

internal data class MapScaleBarState(val label: String, val widthPx: Double)

/** The rendered length and label describe exactly the same distance. */
internal fun mapScaleBarState(metersPerPixel: Double, maxWidthPx: Double, units: DisplayUnits = Units.current): MapScaleBarState? {
    if (!metersPerPixel.isFinite() || metersPerPixel <= 0 || !maxWidthPx.isFinite() || maxWidthPx <= 0) return null
    val maxFeet = metersPerPixel * maxWidthPx / 0.3048
    val metric = units == DisplayUnits.METRIC
    val useMiles = if (metric) metersPerPixel * maxWidthPx >= 1000 else maxFeet >= 5280
    val metersPerUnit = if (metric) (if (useMiles) 1000.0 else 1.0) else if (useMiles) 1609.344 else 0.3048
    val maxUnits = metersPerPixel * maxWidthPx / metersPerUnit
    if (!maxUnits.isFinite() || maxUnits <= 0) return null
    val magnitude = 10.0.pow(floor(log10(maxUnits)))
    val value = listOf(1.0, 2.0, 5.0).last { it * magnitude <= maxUnits } * magnitude
    val number = if (value >= 1) String.format(Locale.US, "%.0f", value) else value.toBigDecimal().stripTrailingZeros().toPlainString()
    return MapScaleBarState("$number ${if (metric) (if (useMiles) "km" else "m") else if (useMiles) "mi" else "ft"}", value * metersPerUnit / metersPerPixel)
}
