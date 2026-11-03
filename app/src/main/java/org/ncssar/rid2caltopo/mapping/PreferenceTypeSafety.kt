package org.ncssar.rid2caltopo.mapping

import android.content.SharedPreferences

/** ClassCast-safe SharedPreferences reads for upgrades / corrupted XML. */
internal object PreferenceTypeSafety {
    fun string(preferences: SharedPreferences, key: String, default: String? = null): String? =
        runCatching { preferences.getString(key, default) }.getOrElse {
            preferences.edit().remove(key).apply()
            default
        }

    fun stringSet(preferences: SharedPreferences, key: String): Set<String> =
        runCatching { preferences.getStringSet(key, emptySet()).orEmpty() }.getOrElse {
            preferences.edit().remove(key).apply()
            emptySet()
        }
}
