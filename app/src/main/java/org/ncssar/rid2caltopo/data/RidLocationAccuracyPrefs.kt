package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import android.content.Context

/** Persisted NACp threshold used when deciding whether a RID position is trackable. */
object RidLocationAccuracyPrefs {
    const val DEFAULT_CODE = 9 // 30 m (F3411 NACp)
    private const val PREFS = "rid_location_accuracy"
    private const val KEY_MINIMUM_CODE = "minimum_horizontal_accuracy_code"

    @JvmStatic
    fun getMinimumCode(context: Context?): Int {
        val code = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getInt(KEY_MINIMUM_CODE, DEFAULT_CODE)
            ?: DEFAULT_CODE
        return code.coerceIn(9, 12)
    }

    fun setMinimumCode(context: Context?, code: Int) {
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_MINIMUM_CODE, code.coerceIn(9, 12))?.apply()
    }

    fun labelForCode(code: Int, units: DisplayUnits = Units.current): String = when (code) {
        9 -> units.length(30.0, 1)
        10 -> units.length(10.0, 1)
        11 -> units.length(3.0, 1)
        12 -> units.length(1.0, 1)
        else -> units.length(30.0, 1)
    }
}
