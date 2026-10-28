package org.ncssar.rid2caltopo.data;

import java.util.function.LongSupplier;

/** A bounded idle grace period. Queued/running finalization must not consume it. */
final class RecordingLinkRetryWindow {
    private final LongSupplier monotonicMilliseconds;
    private final long graceMs;
    private long deadlineMs;

    RecordingLinkRetryWindow(long graceMs, LongSupplier monotonicMilliseconds) {
        this.graceMs = graceMs;
        this.monotonicMilliseconds = monotonicMilliseconds;
        deadlineMs = monotonicMilliseconds.getAsLong() + graceMs;
    }

    synchronized boolean shouldRetry(boolean finalizing) {
        long now = monotonicMilliseconds.getAsLong();
        if (finalizing) deadlineMs = now + graceMs;
        return now < deadlineMs;
    }
}
