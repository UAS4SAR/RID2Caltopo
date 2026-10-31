package org.ncssar.rid2caltopo.video

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts clue terrain jobs (including losers still winding down after timeout) and main-thread hitches.
 * Emits a single log line every 30 s once any clue terrain work has started.
 */
internal object ClueTerrainDiagnostics {
    private val activeClueTerrainTasks = AtomicInteger(0)
    private val mainHitchCount = AtomicLong(0)
    private val mapOverlayAttachCount = AtomicLong(0)
    private val lastMapOverlayAttachMs = AtomicLong(0)
    private val lastHitchSourceLogAtMs = AtomicLong(0)
    private val monitorStarted = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun beginClueTerrainTask() {
        activeClueTerrainTasks.incrementAndGet()
        startMonitorIfNeeded()
    }

    fun endClueTerrainTask() {
        activeClueTerrainTasks.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun noteMapOverlayAttach(elapsedMs: Long) {
        mapOverlayAttachCount.incrementAndGet()
        lastMapOverlayAttachMs.set(elapsedMs)
    }

    private fun startMonitorIfNeeded() {
        if (!monitorStarted.compareAndSet(false, true)) return
        mainHandler.post {
            var lastFrameAtMs = SystemClock.uptimeMillis()
            val hitchProbe = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrameAtMs > 50L) {
                        val hitches = mainHitchCount.incrementAndGet()
                        val previousLog = lastHitchSourceLogAtMs.get()
                        if (now - previousLog >= 1_000L &&
                            lastHitchSourceLogAtMs.compareAndSet(previousLog, now)
                        ) {
                            android.util.Log.i(
                                "ClueTerrain",
                                "hitch sources mainHitchCount=$hitches " +
                                    "activeClueTerrainTasks=${activeClueTerrainTasks.get()} " +
                                    "mapOverlayAttaches=${mapOverlayAttachCount.get()} " +
                                    "lastMapOverlayMs=${lastMapOverlayAttachMs.get()}",
                            )
                        }
                    }
                    lastFrameAtMs = now
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(hitchProbe)
            val report = object : Runnable {
                override fun run() {
                    android.util.Log.i(
                        "ClueTerrain",
                        "diag activeClueTerrainTasks=${activeClueTerrainTasks.get()} " +
                            "mainHitchCount=${mainHitchCount.get()} " +
                            "mapOverlayAttaches=${mapOverlayAttachCount.get()}",
                    )
                    mainHandler.postDelayed(this, 30_000L)
                }
            }
            report.run()
        }
    }
}
