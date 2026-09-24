package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.SystemClock
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

/** Check independently of Tracker authorization; never interrupt a flight to install. */
object PlayAppUpdateCheck {
    private var lastCheckMs: Long? = null
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun check(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastCheckMs?.let { now - it < CHECK_INTERVAL_MS } == true) return
        lastCheckMs = now
        try {
            AppUpdateManagerFactory.create(context.applicationContext).appUpdateInfo
                .addOnSuccessListener { info ->
                    if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                        AppUpdateAdvisory.onPlayRecommendation(info.availableVersionCode())
                    }
                }
                .addOnFailureListener {
                    CaltopoClient.CTDebug("AppUpdate", "Google Play update check unavailable; will retry later")
                }
        } catch (error: Exception) {
            CaltopoClient.CTDebug("AppUpdate", "Google Play update check unavailable; will retry later")
        }
    }
}
