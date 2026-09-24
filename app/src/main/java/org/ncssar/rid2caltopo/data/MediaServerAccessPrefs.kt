package org.ncssar.rid2caltopo.data

import android.content.Context

object MediaServerAccessPrefs {
    private const val PREFS = "media_server_access"
    private const val RESTRICTED = "restricted"

    @JvmStatic
    fun isRestricted(context: Context?): Boolean =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(RESTRICTED, true) ?: true

    @JvmStatic
    fun setRestricted(context: Context?, restricted: Boolean) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(RESTRICTED, restricted)?.apply()
    }
}
