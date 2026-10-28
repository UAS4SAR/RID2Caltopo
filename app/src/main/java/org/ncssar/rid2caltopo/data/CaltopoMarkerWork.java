package org.ncssar.rid2caltopo.data;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All marker-journal access is ordered here; UI callers only capture bounded state. */
final class CaltopoMarkerWork {
    private static volatile Thread journalThread;
    static final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "caltopo-marker-journal");
        thread.setDaemon(true);
        journalThread = thread;
        return thread;
    });
    static void requireWorker() {
        if (Thread.currentThread() != journalThread) throw new IllegalStateException("Marker journal must use its ordered worker");
    }
    static void execute(Runnable task) { worker.execute(task); }
    static void onMain(Runnable task) {
        Looper main = Looper.getMainLooper();
        if (main == null) task.run();
        else new Handler(main).post(task);
    }
    private CaltopoMarkerWork() { }
}
