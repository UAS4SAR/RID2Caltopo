package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Looper
import java.util.concurrent.Executors

/** Survives inspector/view-model closure; existing preference keys and values are retained. */
internal object CameraFootprintSettings {
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "r2c-camera-footprint-settings").apply { isDaemon = true }
    }
    @Volatile private var store: OrderedBooleanSettings? = null
    fun initialize(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val prefs = context.applicationContext.getSharedPreferences("camera_footprint_drone_prefs", Context.MODE_PRIVATE)
        val loaded = prefs.all.mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }.toMap()
        store = OrderedBooleanSettings(loaded, dispatch = { worker.execute(it) },
            persist = { key, enabled -> prefs.edit().putBoolean(key, enabled).commit() },
            failed = { _, error -> CaltopoClient.CTError("CameraFootprint", "Could not save camera footprint setting", error as? Exception ?: Exception("Preference commit failed", error)) })
    }
    private fun ready() = checkNotNull(store) { "Camera footprint settings must be loaded before UI startup" }
    val changes get() = ready().changes
    fun get(key: String) = ready().get(key)
    fun set(key: String, enabled: Boolean) = ready().set(key, enabled)
}
