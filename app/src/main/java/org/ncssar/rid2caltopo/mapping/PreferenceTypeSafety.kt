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

    fun boolean(preferences: SharedPreferences, key: String, default: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, default) }.getOrElse {
            preferences.edit().remove(key).apply()
            default
        }

    fun int(preferences: SharedPreferences, key: String, default: Int): Int =
        runCatching { preferences.getInt(key, default) }.getOrElse {
            preferences.edit().remove(key).apply()
            default
        }
}
