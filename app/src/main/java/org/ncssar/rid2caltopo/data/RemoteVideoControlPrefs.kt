package org.ncssar.rid2caltopo.data

import android.content.Context

object RemoteVideoControlPrefs {
    @get:JvmStatic val consent = RemoteMediaConsent()

    @JvmStatic
    fun refresh(context: Context?): RemoteMediaConsent {
        consent.update(isEnabled(context))
        return consent
    }

    private const val PREFS = "remote_video_control"
    private const val ENABLED = "enabled"

    @JvmStatic
    fun isEnabled(context: Context?): Boolean =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(ENABLED, false) == true

    @JvmStatic
    fun setEnabled(context: Context?, enabled: Boolean) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(ENABLED, enabled)
            ?.apply()
        consent.update(enabled)
        TrackerPeerCoordinator.getInstance().remoteVideoControlChanged()
    }
}
