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
        UnifiedMapCache.maintain(context)
    }
}
