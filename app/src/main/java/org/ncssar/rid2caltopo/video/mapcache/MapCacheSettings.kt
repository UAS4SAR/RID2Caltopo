package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import org.ncssar.rid2caltopo.video.BaseLayerOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal object MapCacheSettings {
    const val PREFS_NAME = "map_cache"
    const val PREWARM_SIGNATURE_KEY = "prewarm_signature_v1"
    private const val MAX_CACHE_BYTES_KEY = "max_cache_bytes_v1"
    private const val MAX_TILE_AGE_DAYS_KEY = "max_tile_age_days_v1"
    private const val BASE_LAYER_KEY = "base_layer_v1"
    private const val CONTOUR_OVERLAY_ENABLED_KEY = "contour_overlay_enabled_v1"

    private const val DECIMAL_GB_BYTES = 1_000_000_000L
    private const val MIN_CACHE_BYTES = 100_000_000L
    // Large removable volumes are common on field tablets. Keep a generous
    // guardrail while allowing operators to reserve more than the old 64 GB cap.
    internal const val MAX_CACHE_BYTES = 1_000_000_000_000L
    private const val DEFAULT_MAX_CACHE_BYTES = 10 * DECIMAL_GB_BYTES
    private const val DEFAULT_MAX_TILE_AGE_DAYS = 365L

    private val initialLimitQueued = AtomicBoolean(false)
    private val initialLimitLock = Any()
    private val initialLimitWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "map-cache-settings").apply { isDaemon = true }
    }

    fun maxCacheBytes(context: Context): Long {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(MAX_CACHE_BYTES_KEY)) {
            // A UI getter must never resolve SAF roots or query a storage provider.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                if (initialLimitQueued.compareAndSet(false, true)) initialLimitWorker.execute { runCatching { maxCacheBytes(app) } }
                return DEFAULT_MAX_CACHE_BYTES
            }
            val free = org.ncssar.rid2caltopo.video.queryAvailableCacheBytes(app) ?: app.filesDir.usableSpace
            synchronized(initialLimitLock) {
                if (!prefs.contains(MAX_CACHE_BYTES_KEY)) prefs.edit().putLong(MAX_CACHE_BYTES_KEY, MapCacheBudgetPolicy.defaultLimit(free)).apply()
            }
        }
        return prefs.getLong(MAX_CACHE_BYTES_KEY, DEFAULT_MAX_CACHE_BYTES).coerceIn(0L, MAX_CACHE_BYTES)
    }

    /** A single durable write; callers must run on a worker. */
    fun saveLimits(context: Context, bytes: Long, days: Long): Boolean = synchronized(initialLimitLock) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Cache settings must be saved on a worker" }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(MAX_CACHE_BYTES_KEY, bytes.coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES))
            .putLong(MAX_TILE_AGE_DAYS_KEY, MapTileUsagePolicy.cacheAgeDays(days)).commit()
    }

    fun maxTileAgeDays(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(MAX_TILE_AGE_DAYS_KEY, DEFAULT_MAX_TILE_AGE_DAYS)
            .let(MapTileUsagePolicy::cacheAgeDays)
    }

    fun baseLayer(context: Context): BaseLayerOption {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(BASE_LAYER_KEY, null)
        return BaseLayerOption.entries.firstOrNull { it.name == stored }
            ?: BaseLayerOption.OpenStreetMap
    }

    fun setBaseLayer(context: Context, baseLayer: BaseLayerOption) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(BASE_LAYER_KEY, baseLayer.name)
            .apply()
    }

    fun contourOverlayEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(CONTOUR_OVERLAY_ENABLED_KEY, false)
    }

    fun setContourOverlayEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CONTOUR_OVERLAY_ENABLED_KEY, enabled)
            .apply()
    }

    fun maxTileAgeMs(context: Context): Long =
        maxTileAgeDays(context) * 24L * 60L * 60L * 1000L

    fun formatDecimalGb(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes.toDouble() / DECIMAL_GB_BYTES.toDouble())

    fun formatTileAge(days: Long): String = when {
        days % 365L == 0L -> {
            val years = days / 365L
            if (years == 1L) "1 year" else "$years years"
        }
        days % 30L == 0L -> {
            val months = days / 30L
            if (months == 1L) "1 month" else "$months months"
        }
        days == 1L -> "1 day"
        else -> "$days days"
    }
}

internal object MapCacheStartupMaintenance {
    private val started = AtomicBoolean(false)

    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        MapCacheMaintenanceScheduler.request(context.applicationContext)
    }
}
