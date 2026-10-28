package org.ncssar.rid2caltopo.data;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/** Ordered flight-journal work; completion callbacks must not perform storage on Main. */
final class CaltopoRecoveryWork {
    private static final ConcurrentHashMap<Object, Runnable> pending = new ConcurrentHashMap<>();
    static final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "caltopo-flight-recovery");
        thread.setDaemon(true);
        return thread;
    });

    static void execute(Runnable task) { worker.execute(task); }

    /** At most one waiting checkpoint per flight; a slow archive must not queue every packet. */
    static void checkpoint(Object key, Runnable task) {
        if (pending.put(key, task) == null) {
            execute(() -> {
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
        Looper main = Looper.getMainLooper();
        if (main != null && Looper.myLooper() == main) execute(task);
        else task.run();
    }

    static void onMain(Runnable task) {
        Looper main = Looper.getMainLooper();
        if (main != null) new Handler(main).post(task);
        else task.run();
    }

    private CaltopoRecoveryWork() { }
}
