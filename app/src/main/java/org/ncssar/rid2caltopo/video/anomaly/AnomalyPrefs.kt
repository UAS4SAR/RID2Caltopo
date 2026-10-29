package org.ncssar.rid2caltopo.video.anomaly

import android.content.Context

/**
 * Removes AD settings written by older releases and supplies fresh process-session defaults.
 * Anomaly detector configuration is intentionally never persisted.
 */
object AnomalyPrefs {
    private const val LEGACY_PREFS_NAME = "anomaly_prefs"

    internal fun sessionStartConfig(): AnomalyConfig =
        AnomalyConfig().resetToRealtimeDefaults()

    /** Worker-only migration; creating a ViewModel must never open a preference file. */
    @JvmStatic
    fun clearLegacySettings(context: Context) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        context.applicationContext
            .getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
