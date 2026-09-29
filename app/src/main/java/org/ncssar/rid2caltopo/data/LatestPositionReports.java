package org.ncssar.rid2caltopo.data;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One replaceable pending report per aircraft; cooldown starts after the actual send completes. */
final class LatestPositionReports<T> {
    static final long INTERVAL_MS = 5_000L;
    private final class Slot {
        T pending;
        boolean scheduled;
        Long completedAt;
    }
    private final Map<String, Slot> slots = new HashMap<>();
    private final Executor worker;
    private final BiConsumer<Runnable, Long> timer;
    private final LongSupplier clock;
    private final LongSupplier intervalMs;
    private final Consumer<T> send;
    private final Consumer<T> discard;

    LatestPositionReports(Executor worker, BiConsumer<Runnable, Long> timer,
                          LongSupplier clock, Consumer<T> send, Consumer<T> discard) {
        this(worker, timer, clock, send, discard, () -> INTERVAL_MS);
    }

    LatestPositionReports(Executor worker, BiConsumer<Runnable, Long> timer,
                          LongSupplier clock, Consumer<T> send, Consumer<T> discard,
                          LongSupplier intervalMs) {
        this.intervalMs = intervalMs;
        this.worker = worker;
        this.timer = timer;
        this.clock = clock;
        this.send = send;
        this.discard = discard;
    }

    synchronized void submit(String key, T report) {
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot());
        if (slot.pending != null) discard.accept(slot.pending);
        slot.pending = report;
        if (!slot.scheduled) schedule(slot);
    }

    synchronized void cancel(String key) {
        Slot slot = slots.get(key);
        if (slot != null && slot.pending != null) {
            discard.accept(slot.pending);
            slot.pending = null;
        }
        // Retain the cooldown across flight boundaries and reconnects.
    }

    private void schedule(Slot slot) {
        slot.scheduled = true;
        timer.accept(() -> checkReady(slot), Math.min(500L, remaining(slot)));
    }

    private long remaining(Slot slot) {
        return slot.completedAt == null ? 0L : Math.max(0L,
                slot.completedAt + intervalMs.getAsLong() - clock.getAsLong());
    }

    private synchronized void checkReady(Slot slot) {
        if (slot.pending == null) { slot.scheduled = false; return; }
        if (remaining(slot) > 0) { schedule(slot); return; }
        worker.execute(() -> drain(slot));
    }

    private void drain(Slot slot) {
        T report;
        synchronized (this) {
            // Recheck if the setting increased while waiting for the network worker.
            if (remaining(slot) > 0) { schedule(slot); return; }
            // Choose at execution time, not when this work was enqueued.
            report = slot.pending;
            slot.pending = null;
            if (report == null) { slot.scheduled = false; return; }
        }
        try {
            send.accept(report);
        } finally {
            synchronized (this) {
                slot.completedAt = clock.getAsLong();
                slot.scheduled = false;
                if (slot.pending != null) schedule(slot);
            }
        }
    }
}
