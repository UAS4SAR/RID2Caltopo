package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes tile-cache maintenance away from tile download workers.
 *
 * Removable-storage document providers may take an unbounded amount of time to
 * enumerate or delete a file. A stuck provider must never hold a map download
 * worker or its progress counter. Requests made during offline preparation are
 * retained and started after the preparation job finishes.
 */
internal object MapCacheMaintenanceScheduler {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "map-cache-maint").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun request(context: Context) {
        appContext = context.applicationContext
        requested.set(true)
        startIfEligible()
    }

    fun resumeAfterOfflinePrep() {
        startIfEligible()
    }

    private fun startIfEligible() {
        val context = appContext ?: return
        if (MapOfflinePrepRuntime.isActive()) return
        if (!requested.get()) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (requested.getAndSet(false)) {
                    if (MapOfflinePrepRuntime.isActive()) {
                        requested.set(true)
                        break
                    }
                    maintain(context)
                }
            } catch (t: Throwable) {
                MapCacheDebug.log("background maintenance failed err=${t.javaClass.simpleName}:${t.message}")
            } finally {
                running.set(false)
                if (requested.get() && !MapOfflinePrepRuntime.isActive()) startIfEligible()
            }
        }
    }

    private fun maintain(context: Context) {
        val maxBytes = MapCachePolicy.tileCacheMaxBytes(context)
        val trimBytes = MapCachePolicy.tileCacheTrimBytes(context)
        val maxAgeMs = MapCachePolicy.tileCacheMaxAgeMs(context)
        val cutoffMs = System.currentTimeMillis() - maxAgeMs
        val store = BlobCacheStoreFactory.create(
            context = context,
            namespace = "tile_cache_v${MapCachePolicy.TILE_CACHE_VERSION}",
            dbName = MapCachePolicy.TILE_CACHE_DB,
            maxBytes = maxBytes,
            defaultTtlMs = MapCachePolicy.TILE_TTL_MS
        )
        store.prewarm()
        val result = store.runMaintenance(
            maxEntryAgeCutoffMs = cutoffMs,
            trimToBytes = trimBytes,
            shouldContinue = { !MapOfflinePrepRuntime.isActive() }
        )
        if (MapOfflinePrepRuntime.isActive()) requested.set(true)
        MapCacheDebug.log(
            "background-maint tile agedOut=${result.agedOutEntries} trimEvicted=${result.trimEvictedEntries} " +
                "bytesFreed=${result.bytesFreed} bytesRemaining=${result.bytesRemaining} " +
                "maxBytes=$maxBytes trimBytes=$trimBytes maxAgeMs=$maxAgeMs"
        )
    }
}
