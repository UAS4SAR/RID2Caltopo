package org.ncssar.rid2caltopo.app;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clean shutdown when the operator swipes R2C away in Recents.
 *
 * <p>Android finishes the activity, then kills the process as soon as nothing keeps it in the
 * foreground. Before this, R2CActivity.onDestroy() started the shutdown on a thread and Android
 * killed the process about 20 ms later (Nov 12, 2026, 19:11:37 PST), so CalTopo marker cleanup,
 * track archiving, state save and the session log close never ran.
 *
 * <p>ScanningService now stays running past the swipe (stopWithTask=false). Its foreground state
 * keeps the process alive while this class runs the shutdown on a worker thread, waits for it up
 * to {@link #CAP_MSEC}, stops the stream server, and only then releases the service (drops the
 * foreground notification and stops it), after which Android removes the process. Nothing here
 * runs on the main thread, so the UI teardown is not blocked.
 */
final class TaskRemovalShutdown {
    static final long CAP_MSEC = 5_000L;

    interface Steps {
        /** CalTopo marker cleanup and map close, archive tracks, save state, close the session log. */
        void shutdown();
        /** Stops the RTMP/RTSP server. */
        void stopMediaServer();
        /** Drops the foreground notification and stops the service. Called exactly once. */
        void release();
        void log(@NonNull String message);
    }

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean active;
    private volatile boolean finishedInTime;

    boolean isActive() { return active; }

    boolean wasStarted() { return started.get(); }

    boolean finishedInTime() { return finishedInTime; }

    /** Starts the shutdown once; later calls return false and do nothing. */
    boolean begin(@NonNull String reason, @NonNull Steps steps, long capMsec) {
        if (!started.compareAndSet(false, true)) return false;
        active = true;
        Thread supervisor = new Thread(() -> run(reason, steps, capMsec), "R2C-TaskRemoval");
        supervisor.setDaemon(true);
        supervisor.start();
        return true;
    }

    private void run(@NonNull String reason, @NonNull Steps steps, long capMsec) {
        long startedAt = System.nanoTime();
        steps.log("Task removal shutdown started (" + reason + "), cap " + capMsec + " ms");
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                steps.shutdown();
            } catch (Throwable error) {
                steps.log("Task removal shutdown raised " + error);
            } finally {
                done.countDown();
            }
        }, "R2C-Shutdown");
        worker.setDaemon(true);
        worker.start();
        boolean inTime;
        try {
            inTime = done.await(capMsec, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            inTime = false;
        }
        finishedInTime = inTime;
        try {
            steps.stopMediaServer();
        } catch (Throwable error) {
            steps.log("Task removal: stream server stop raised " + error);
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        steps.log(inTime
                ? "Task removal shutdown finished in " + elapsed + " ms; releasing service"
                : "Task removal shutdown still running after " + elapsed + " ms; releasing service anyway");
        active = false;
        steps.release();
    }
}
