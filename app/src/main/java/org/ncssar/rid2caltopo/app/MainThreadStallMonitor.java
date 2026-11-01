/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.app;

import android.app.ActivityManager;
import android.content.Context;
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
    /** elapsedRealtime advances during deep sleep; uptimeMillis does not. */
    private static final long SLEEP_GAP_THRESHOLD_MS = 1500L;

    private static final Handler MainHandler = new Handler(Looper.getMainLooper());
    private static volatile boolean started = false;
    private static volatile long nextExpectedAtUptimeMs = 0L;
    private static volatile long lastHeartbeatElapsedRealtimeMs = 0L;
    private static volatile Context appContext;
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
        appContext = R2CApplication.getAppCtxt();
        long nowUptime = SystemClock.uptimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        nextExpectedAtUptimeMs = nowUptime + HEARTBEAT_INTERVAL_MS;
        lastHeartbeatElapsedRealtimeMs = nowElapsed;
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

    /**
     * True when a long gap should be treated as a real main-thread stall.
     * Suppresses device-sleep gaps (elapsedRealtime advanced while uptime did not)
     * and process freeze/background periods where the main looper was not scheduled.
     */
    static boolean shouldReportStall(
            long delayUptimeMs,
            long elapsedSinceLastHeartbeatMs,
            int processImportance,
            boolean processImportanceKnown
    ) {
        if (delayUptimeMs < STALL_THRESHOLD_MS) return false;
        // Deep sleep: Handler.postDelayed is uptime-based, so delayUptime is usually small
        // after wake. When a sample still looks large, a much larger elapsedRealtime gap
        // means the device was asleep rather than the main thread doing work.
        long sleepSkew = elapsedSinceLastHeartbeatMs - delayUptimeMs;
        if (sleepSkew >= SLEEP_GAP_THRESHOLD_MS) return false;
        if (processImportanceKnown && processImportance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return false;
        }
        return true;
    }

    static int currentProcessImportance(Context context) {
        if (context == null) return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            return info.importance;
        } catch (RuntimeException ignored) {
            return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
    }

    private static String mainStackTrace(int limit) {
        Thread main = Looper.getMainLooper().getThread();
        StackTraceElement[] frames = main.getStackTrace();
        StringBuilder trace = new StringBuilder(" state=").append(main.getState());
        for (int index = 0; index < Math.min(limit, frames.length); index++) {
            trace.append("\n    at ").append(frames[index]);
        }
        return trace.toString();
    }

    private static void captureBlockedMainStack() {
        long now = SystemClock.uptimeMillis();
        long overdue = now - nextExpectedAtUptimeMs;
        long elapsedGap = SystemClock.elapsedRealtime() - lastHeartbeatElapsedRealtimeMs;
        int importance = currentProcessImportance(appContext);
        if (!started || !shouldReportStall(overdue, elapsedGap, importance, true)) return;
        if (now - lastStackAtUptimeMs < 1_000L) return;
        lastStackAtUptimeMs = now;
        // Capture from the watchdog while Main is blocked, rather than from its recovered heartbeat.
        CaltopoClient.CTWarn(TAG, "main thread blocked delayMs=" + overdue + mainStackTrace(32));
    }

    private static void heartbeat() {
        long nowUptimeMs = SystemClock.uptimeMillis();
        long nowElapsedMs = SystemClock.elapsedRealtime();
        long delayMs = nowUptimeMs - nextExpectedAtUptimeMs;
        long elapsedGap = nowElapsedMs - lastHeartbeatElapsedRealtimeMs;
        int importance = currentProcessImportance(appContext);
        if (shouldReportStall(delayMs, elapsedGap, importance, true)) {
            String stack = mainStackTrace(32);
            if (!SettingsStartup.isReady()) {
                CaltopoClient.CTWarn(TAG, "Main thread stall during saved-settings startup delayMs=" + delayMs + stack);
            } else {
                CaltopoClient.CTWarn(TAG, String.format(Locale.US,
                        "main thread stall delayMs=%d intervalMs=%d thread=%s scanningRunning=%b scannerUptime=%s " +
                                "ridIngestQueueDepth=%d ridIngestDropped=%d activeFlights=%d mapStatus=%s%s",
                        delayMs,
                        HEARTBEAT_INTERVAL_MS,
                        Thread.currentThread().getName(),
                        ScanningService.IsRunning(),
                        ScanningService.UpTime(),
                        ScanningService.GetRidIngestQueueDepth(),
                        ScanningService.GetDroppedRidIngestPacketCount(),
                        CaltopoClient.GetActiveFlightCountSnapshot(),
                        CaltopoMap.GetMapStatus(),
                        stack));
            }
        }
        synchronized (MainThreadStallMonitor.class) {
            if (!started) return;
            nextExpectedAtUptimeMs = nowUptimeMs + HEARTBEAT_INTERVAL_MS;
            lastHeartbeatElapsedRealtimeMs = nowElapsedMs;
            MainHandler.postDelayed(MainThreadStallMonitor::heartbeat, HEARTBEAT_INTERVAL_MS);
        }
    }
}
