package org.ncssar.rid2caltopo.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.app.R2CApplication
import java.util.Locale
import kotlin.math.abs

/** Presentation only. Protocols, saved configuration and calculations retain their native units. */
enum class DisplayUnits {
    IMPERIAL, METRIC;

    val lengthUnit get() = if (this == METRIC) "m" else "ft"
    val distanceUnit get() = if (this == METRIC) "km" else "mi"
    val resolutionUnit get() = if (this == METRIC) "cm/px" else "in/px"
    val weightUnit get() = if (this == METRIC) "g" else "oz"
    fun lengthValue(meters: Double) = if (this == METRIC) meters else meters / 0.3048
    fun lengthMeters(value: Double) = if (this == METRIC) value else value * 0.3048
    fun milesValue(miles: Double) = if (this == METRIC) miles * 1.609344 else miles
    fun resolutionValue(cm: Double) = if (this == METRIC) cm else cm / 2.54
    fun resolutionCm(value: Double) = if (this == METRIC) value else value * 2.54
    fun gramsValue(grams: Double) = if (this == METRIC) grams else grams / 28.349523125
    fun grams(value: Double) = if (this == METRIC) value else value * 28.349523125
    fun number(value: Double, decimals: Int = 1) = String.format(Locale.US, "%.${decimals}f", value).let { if (it.startsWith("-") && it.toDoubleOrNull() == 0.0) it.drop(1) else it }
    fun length(meters: Double, decimals: Int = 0) = "${number(lengthValue(meters), decimals)} $lengthUnit"
    fun feet(feet: Double, decimals: Int = 0) = length(feet * 0.3048, decimals)
    fun miles(miles: Double, decimals: Int = 1) = "${number(milesValue(miles), decimals)} $distanceUnit"
    fun distance(meters: Double): String = if (abs(meters) >= if (this == METRIC) 1000.0 else 1609.344)
        miles(meters / 1609.344, 2) else length(meters)
    fun area(squareMiles: Double) = "${number(if (this == METRIC) squareMiles * 2.589988110336 else squareMiles, 2)} ${if (this == METRIC) "km²" else "sq mi"}"
    fun speed(metersPerSecond: Double) = "${number(if (this == METRIC) metersPerSecond else metersPerSecond * 2.2369362920544)} ${if (this == METRIC) "m/s" else "mph"}"
    fun climb(metersPerSecond: Double) = "${number(if (this == METRIC) metersPerSecond else metersPerSecond / 0.3048 * 60)} ${if (this == METRIC) "m/s" else "ft/min"}"
    fun resolution(cm: Double) = "${number(resolutionValue(cm), 2)} $resolutionUnit"
}

object Units {
    private val preferences get() = R2CApplication.getAppCtxt()?.getSharedPreferences("display_units", Context.MODE_PRIVATE)
    var current by mutableStateOf(if (preferences?.getString("system", null) == "metric") DisplayUnits.METRIC else DisplayUnits.IMPERIAL)
        private set
    fun save(units: DisplayUnits) {
        preferences?.edit()?.putString("system", if (units == DisplayUnits.METRIC) "metric" else "imperial")?.apply()
        current = units
    }
}
