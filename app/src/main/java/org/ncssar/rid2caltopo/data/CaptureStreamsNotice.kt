package org.ncssar.rid2caltopo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A notice that a restore or import tried to turn Capture Streams off and it was kept ON.
 * Saved in preferences because Android restores settings before the app runs.
 */
object CaptureStreamsNotice {
    private const val PREFS = "capture_streams_notice"
    private const val KEY = "message"
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun message(source: String): String =
        "Capture Streams stayed ON. The settings $source would have turned stream capture off. " +
            "You can still turn it off in Settings."

    @JvmStatic
    fun record(context: Context, source: String) {
        val message = message(source)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, message).apply()
        _pending.value = message
    }

    @JvmStatic
    fun load(context: Context) {
        if (_pending.value == null) {
            _pending.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }
    }

    @JvmStatic
    fun dismiss(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        _pending.value = null
    }
}
