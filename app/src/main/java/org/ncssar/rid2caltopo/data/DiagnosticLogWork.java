package org.ncssar.rid2caltopo.data;

import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Producers never acquire a lock held by file/SAF I/O. Log memory is bounded under a slow provider. */
final class DiagnosticLogWork {
    private static final int MAX_PENDING_CHARACTERS = 512 * 1024;
    private static final AtomicInteger pending = new AtomicInteger();
    private static volatile Thread writerThread;
    static final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "r2c-diagnostic-log");
        thread.setDaemon(true); writerThread = thread; return thread;
    });
    static void append(String message, Runnable task) {
        int size = Math.max(64, message == null ? 0 : message.length());
        while (true) {
            int current = pending.get();
            if (size > MAX_PENDING_CHARACTERS - current) return;
            if (pending.compareAndSet(current, current + size)) break;
        }
        writer.execute(() -> { try { task.run(); } finally { pending.addAndGet(-size); } });
    }
    static void finish(Runnable task) {
        if (Thread.currentThread() == writerThread) { task.run(); return; }
        java.util.concurrent.Future<?> completion = writer.submit(task);
        Looper main = Looper.getMainLooper();
        if (main != null && Looper.myLooper() == main) return;
        try { completion.get(5, TimeUnit.SECONDS); }
        catch (Exception error) { Log.w("DiagnosticLogWork", "Log completion remains queued: " + error.getClass().getSimpleName()); }
    }
    static void drainForTests() throws Exception { writer.submit(() -> {}).get(3, TimeUnit.SECONDS); }
    private DiagnosticLogWork() { }
}
