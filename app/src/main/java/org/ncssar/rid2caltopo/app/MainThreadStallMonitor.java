/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.CaltopoMap;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MainThreadStallMonitor {
    private static final String TAG = "MainThreadStallMonitor";
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;
    private static final long STALL_THRESHOLD_MS = 1000L;

    private static final Handler MainHandler = new Handler(Looper.getMainLooper());
    private static volatile boolean started = false;
    private static volatile long nextExpectedAtUptimeMs = 0L;
    private static final ScheduledExecutorService Watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "r2c-main-stall-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private static ScheduledFuture<?> watchdogTask;
    private static long lastStackAtUptimeMs;

    private MainThreadStallMonitor() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        nextExpectedAtUptimeMs = SystemClock.uptimeMillis() + HEARTBEAT_INTERVAL_MS;
        MainHandler.postDelayed(MainThreadStallMonitor::heartbeat, HEARTBEAT_INTERVAL_MS);
        lastStackAtUptimeMs = 0L;
        watchdogTask = Watchdog.scheduleWithFixedDelay(
                MainThreadStallMonitor::captureBlockedMainStack, 1, 1, TimeUnit.SECONDS);
        CaltopoClient.CTDebug(TAG, "Started main-thread stall monitor.");
    }

    public static synchronized void stop() {
        if (!started) return;
        started = false;
        MainHandler.removeCallbacksAndMessages(null);
        if (watchdogTask != null) watchdogTask.cancel(false);
        watchdogTask = null;
        CaltopoClient.CTDebug(TAG, "Stopped main-thread stall monitor.");
    }

    private static void captureBlockedMainStack() {
        long now = SystemClock.uptimeMillis();
        long overdue = now - nextExpectedAtUptimeMs;
        if (!started || overdue < STALL_THRESHOLD_MS || now - lastStackAtUptimeMs < 5_000L) return;
        lastStackAtUptimeMs = now;
        Thread main = Looper.getMainLooper().getThread();
        StackTraceElement[] frames = main.getStackTrace();
        StringBuilder trace = new StringBuilder("main thread blocked delayMs=")
                .append(overdue).append(" state=").append(main.getState());
        for (int index = 0; index < Math.min(32, frames.length); index++) {
            trace.append("\n    at ").append(frames[index]);
        }
        // Capture from the watchdog while Main is blocked, rather than from its recovered heartbeat.
        CaltopoClient.CTWarn(TAG, trace.toString());
    }

    private static void heartbeat() {
        long nowUptimeMs = SystemClock.uptimeMillis();
        long delayMs = nowUptimeMs - nextExpectedAtUptimeMs;
        if (delayMs >= STALL_THRESHOLD_MS && !SettingsStartup.isReady()) {
            CaltopoClient.CTWarn(TAG, "Main thread stall during saved-settings startup delayMs=" + delayMs);
        } else if (delayMs >= STALL_THRESHOLD_MS) {
            CaltopoClient.CTWarn(TAG, String.format(Locale.US,
                    "main thread stall delayMs=%d intervalMs=%d thread=%s scanningRunning=%b scannerUptime=%s " +
                            "ridIngestQueueDepth=%d ridIngestDropped=%d activeFlights=%d mapStatus=%s",
                    delayMs,
                    HEARTBEAT_INTERVAL_MS,
                    Thread.currentThread().getName(),
                    ScanningService.IsRunning(),
                    ScanningService.UpTime(),
                    ScanningService.GetRidIngestQueueDepth(),
                    ScanningService.GetDroppedRidIngestPacketCount(),
                    CaltopoClient.GetActiveFlightCountSnapshot(),
                    CaltopoMap.GetMapStatus()));
        }
        synchronized (MainThreadStallMonitor.class) {
            if (!started) return;
            nextExpectedAtUptimeMs = nowUptimeMs + HEARTBEAT_INTERVAL_MS;
            MainHandler.postDelayed(MainThreadStallMonitor::heartbeat, HEARTBEAT_INTERVAL_MS);
        }
    }
}
