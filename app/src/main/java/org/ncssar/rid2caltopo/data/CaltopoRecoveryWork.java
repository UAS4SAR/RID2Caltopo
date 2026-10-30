package org.ncssar.rid2caltopo.data;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flight publication work lanes.
 * LiveTrack start / point forwarding must never wait behind another flight's archive,
 * catalog scan, or interrupted-track recovery.
 */
final class CaltopoRecoveryWork {
    private static final ConcurrentHashMap<Object, Runnable> pendingArchive = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, Runnable> pendingStart = new ConcurrentHashMap<>();

    static final ExecutorService startWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "caltopo-flight-start");
        thread.setDaemon(true);
        return thread;
    });

    static final ExecutorService archiveWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "caltopo-flight-archive");
        thread.setDaemon(true);
        return thread;
    });

    /** @deprecated Prefer {@link #archiveWorker}; retained for tests that drain the archive lane. */
    static final ExecutorService worker = archiveWorker;

    static void execute(Runnable task) { archiveWorker.execute(task); }

    static void executeStart(Runnable task) { startWorker.execute(task); }

    static void executeArchive(Runnable task) { archiveWorker.execute(task); }

    /** At most one waiting checkpoint per flight on the archive lane. */
    static void checkpoint(Object key, Runnable task) {
        checkpointOn(pendingArchive, archiveWorker, key, task);
    }

    /** At most one waiting checkpoint per flight on the start/publication-intent lane. */
    static void checkpointStart(Object key, Runnable task) {
        checkpointOn(pendingStart, startWorker, key, task);
    }

    private static void checkpointOn(
            ConcurrentHashMap<Object, Runnable> pending,
            ExecutorService executor,
            Object key,
            Runnable task) {
        if (pending.put(key, task) == null) {
            executor.execute(() -> {
                Runnable latest = pending.remove(key);
                if (latest != null) latest.run();
            });
        }
    }

    static void requireOffMain() {
        Looper main = Looper.getMainLooper();
        if (main != null && Looper.myLooper() == main) {
            throw new IllegalStateException("Flight journal work must run on a worker");
        }
    }

    static void offMain(Runnable task) {
        offMainArchive(task);
    }

    static void offMainStart(Runnable task) {
        Looper main = Looper.getMainLooper();
        if ((main != null && Looper.myLooper() == main) || isArchiveThread()) executeStart(task);
        else task.run();
    }

    static void offMainArchive(Runnable task) {
        Looper main = Looper.getMainLooper();
        if ((main != null && Looper.myLooper() == main) || isStartThread()) executeArchive(task);
        else task.run();
    }

    private static boolean isArchiveThread() {
        String name = Thread.currentThread().getName();
        return name != null && name.startsWith("caltopo-flight-archive");
    }

    private static boolean isStartThread() {
        String name = Thread.currentThread().getName();
        return name != null && name.startsWith("caltopo-flight-start");
    }

    static void onMain(Runnable task) {
        Looper main = Looper.getMainLooper();
        if (main != null) new Handler(main).post(task);
        else task.run();
    }

    private CaltopoRecoveryWork() { }
}
