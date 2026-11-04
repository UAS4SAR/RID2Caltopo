package org.ncssar.rid2caltopo.data;

import java.util.function.LongSupplier;

/**
 * A bounded idle grace period. Queued/running finalization must not consume it, but an
 * absolute maximum (default 10 minutes) bounds total retry time.
 */
final class RecordingLinkRetryWindow {
    static final long DEFAULT_ABSOLUTE_LIMIT_MS = 10L * 60L * 1000L;

    private final LongSupplier monotonicMilliseconds;
    private final long graceMs;
    private final long absoluteLimitMs;
    private final long startedAtMs;
    private long deadlineMs;

    RecordingLinkRetryWindow(long graceMs, LongSupplier monotonicMilliseconds) {
        this(graceMs, DEFAULT_ABSOLUTE_LIMIT_MS, monotonicMilliseconds);
    }

    RecordingLinkRetryWindow(long graceMs, long absoluteLimitMs, LongSupplier monotonicMilliseconds) {
        this.graceMs = graceMs;
        this.absoluteLimitMs = absoluteLimitMs;
        this.monotonicMilliseconds = monotonicMilliseconds;
        startedAtMs = monotonicMilliseconds.getAsLong();
        deadlineMs = startedAtMs + graceMs;
    }

    synchronized boolean shouldRetry(boolean finalizing) {
        long now = monotonicMilliseconds.getAsLong();
        if (now - startedAtMs >= absoluteLimitMs) return false;
        if (finalizing) deadlineMs = now + graceMs;
        return now < deadlineMs;
    }
}
