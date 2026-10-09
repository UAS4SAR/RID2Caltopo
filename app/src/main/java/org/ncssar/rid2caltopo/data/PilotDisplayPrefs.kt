package org.ncssar.rid2caltopo.data

import android.content.Context
import java.util.Locale

const val DEFAULT_ACTIVE_TRACK_COLOR = "#1E88E5"
const val DEFAULT_ARCHIVE_TRACK_COLOR = "#FF00FF"

data class PilotDisplayPreference(
    val activeTrackColor: String = DEFAULT_ACTIVE_TRACK_COLOR,
    val archiveTrackColor: String = DEFAULT_ARCHIVE_TRACK_COLOR,
    val bearingEnabled: Boolean = false,
    val bearingColor: String? = null,
) {
    val resolvedBearingColor: String get() = sanitizeTrackColor(bearingColor, activeTrackColor)
}

fun normalizePilotCallsign(raw: String?): String? {
    val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
    return normalized.ifBlank { null }
}

fun sanitizeTrackColor(raw: String?, fallback: String): String {
    val value = raw?.trim().orEmpty()
    return when {
        Regex("^#[0-9a-fA-F]{6}$").matches(value) -> value.uppercase(Locale.US)
        Regex("^[0-9a-fA-F]{6}$").matches(value) -> "#${value.uppercase(Locale.US)}"
        else -> fallback
    }
}

object PilotDisplayPrefs {
    private const val PREFS_NAME = "pilot_display_prefs"
    private const val KEY_ACTIVE_SUFFIX = ".active"
    private const val KEY_ARCHIVE_SUFFIX = ".archive"
    private const val KEY_BEARING_SUFFIX = ".bearing"

    fun load(context: Context, pilotCallsign: String?): PilotDisplayPreference {
        val key = normalizePilotCallsign(pilotCallsign) ?: return PilotDisplayPreference()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PilotDisplayPreference(
            activeTrackColor = sanitizeTrackColor(
                prefs.getString(key + KEY_ACTIVE_SUFFIX, null),
                DEFAULT_ACTIVE_TRACK_COLOR
            ),
            archiveTrackColor = sanitizeTrackColor(
                prefs.getString(key + KEY_ARCHIVE_SUFFIX, null),
                DEFAULT_ARCHIVE_TRACK_COLOR
            ),
            bearingEnabled = prefs.getBoolean(key + KEY_BEARING_SUFFIX, false),
            bearingColor = prefs.getString(key + ".bearingColor", null)
        )
    }

    fun save(context: Context, pilotCallsign: String?, preference: PilotDisplayPreference): Boolean {
        val key = normalizePilotCallsign(pilotCallsign) ?: return false
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                key + KEY_ACTIVE_SUFFIX,
                sanitizeTrackColor(preference.activeTrackColor, DEFAULT_ACTIVE_TRACK_COLOR)
            )
            .putString(
                key + KEY_ARCHIVE_SUFFIX,
                sanitizeTrackColor(preference.archiveTrackColor, DEFAULT_ARCHIVE_TRACK_COLOR)
            )
            .putString(key + ".bearingColor", preference.bearingColor?.let { sanitizeTrackColor(it, preference.activeTrackColor) })
            .putBoolean(key + KEY_BEARING_SUFFIX, preference.bearingEnabled)
            .apply()
        return true
    }

    fun reset(context: Context, pilotCallsign: String?): Boolean {
        val key = normalizePilotCallsign(pilotCallsign) ?: return false
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key + KEY_ACTIVE_SUFFIX)
            .remove(key + KEY_ARCHIVE_SUFFIX)
            .remove(key + KEY_BEARING_SUFFIX)
            .remove(key + ".bearingColor")
            .apply()
        return true
    }
}

object TabletPilotCallsignPrefs {
    private const val PREFS_NAME = "operator_identity_prefs"
    private const val KEY_PILOT_CALLSIGN = "pilot_callsign"

    fun load(context: Context): String = normalizePilotCallsign(
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PILOT_CALLSIGN, null)
    ).orEmpty()

    fun save(context: Context, pilotCallsign: String) {
        val normalized = normalizePilotCallsign(pilotCallsign).orEmpty()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PILOT_CALLSIGN, normalized)
            .apply()
    }
}
