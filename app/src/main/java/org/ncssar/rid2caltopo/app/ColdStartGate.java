package org.ncssar.rid2caltopo.app;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One process-wide load; observers never wait on its worker or disk locks. */
public final class ColdStartGate {
    private final Executor worker;
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    public ColdStartGate(Executor worker) { this.worker = worker; }
    public void start(Runnable load) {
        if (!started.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { load.run(); completion.complete(null); }
            catch (Throwable error) { completion.completeExceptionally(error); }
        });
    }
    public boolean isReady() { return completion.isDone() && !completion.isCompletedExceptionally(); }
    public void whenComplete(Executor delivery, Consumer<Throwable> observer) {
        completion.whenCompleteAsync((ignored, error) -> observer.accept(error), delivery);
    }
}
