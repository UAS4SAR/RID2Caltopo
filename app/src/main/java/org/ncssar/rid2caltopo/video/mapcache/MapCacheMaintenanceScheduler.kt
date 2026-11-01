package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Live-priority tile fetches set this so cache purge does not run across them. */
internal object MapCacheLiveTileGate {
    private val active = AtomicInteger(0)

    fun enter() {
        active.incrementAndGet()
    }

    fun exit() {
        active.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun isActive(): Boolean = active.get() > 0
}

/**
 * Serializes tile-cache maintenance away from tile download workers.
 *
 * Removable-storage document providers may take an unbounded amount of time to
 * enumerate or delete a file. A stuck provider must never hold a map download
 * worker or its progress counter. Requests made during offline preparation or
 * a live-priority tile pass are retained and started after that work finishes.
 * Retention purge also runs on a fixed period so restricted tiles do not sit
 * past their window for the life of a long-lived process.
 */
internal object MapCacheMaintenanceScheduler {
    private const val PERIOD_MS = 6L * 60L * 60L * 1000L
    private const val LIVE_DEFER_MS = 2_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "map-cache-maint").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    private val periodicArmed = AtomicBoolean(false)
    private val deferArmed = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun request(context: Context) {
        appContext = context.applicationContext
        requested.set(true)
        armPeriodic()
        startIfEligible()
    }

    fun resumeAfterOfflinePrep() {
        startIfEligible()
    }

    private fun armPeriodic() {
        val context = appContext ?: return
        if (!periodicArmed.compareAndSet(false, true)) return
        executor.schedule({
            periodicArmed.set(false)
            request(context)
        }, PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    private fun deferForLivePass() {
        if (!deferArmed.compareAndSet(false, true)) return
        executor.schedule({
            deferArmed.set(false)
            startIfEligible()
        }, LIVE_DEFER_MS, TimeUnit.MILLISECONDS)
    }

    private fun startIfEligible() {
        val context = appContext ?: return
        if (MapOfflinePrepRuntime.isActive()) return
        if (MapCacheLiveTileGate.isActive()) {
            deferForLivePass()
            return
        }
        if (!requested.get()) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (requested.getAndSet(false)) {
                    if (MapOfflinePrepRuntime.isActive()) {
                        requested.set(true)
                        break
                    }
                    if (MapCacheLiveTileGate.isActive()) {
                        requested.set(true)
                        deferForLivePass()
                        break
                    }
                    val finished = maintain(context)
                    if (!finished) requested.set(true)
                }
            } catch (t: Throwable) {
                MapCacheDebug.log("background maintenance failed err=${t.javaClass.simpleName}:${t.message}")
            } finally {
                running.set(false)
                if (requested.get() && !MapOfflinePrepRuntime.isActive()) startIfEligible()
            }
        }
    }

    private fun maintain(context: Context): Boolean =
        UnifiedMapCache.maintain(context) {
            !MapOfflinePrepRuntime.isActive() && !MapCacheLiveTileGate.isActive()
        }
}
