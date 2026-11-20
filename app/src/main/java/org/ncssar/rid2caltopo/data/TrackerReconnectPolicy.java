/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data;

/**
 * Reconnect delay and acknowledgement budget for the tracker websocket.
 *
 * <p>The ladder is 2, 4, 8, then 10 seconds. An acknowledgement timeout uses
 * that same ladder. The delay returns to 2 seconds only when {@code hello_ack}
 * arrives. Opening the socket is not acknowledgement: a slow server can accept
 * the TCP connection and still be working on the hello.
 *
 * <p>A socket that is still open waits 30 seconds for {@code hello_ack}. The
 * load test delayed that ack by 12 seconds; closing at 10 seconds discarded
 * the in-flight ack and every tablet redialed. Thirty seconds covers that
 * delay with margin and still bounds a socket that never gets an ack.
 * Heartbeat acks stay at 10 seconds because they are sent only after hello
 * succeeded, and the measured heartbeat tail is well under a second. The
 * budget does not grow on later attempts; the ladder is what spaces those
 * dials. The websocket messages themselves are unchanged.
 *
 * <p>Legacy mode restores the 2.5.8 Android retry: close a missing hello at
 * 10 seconds, redial immediately, and clear the ladder when the socket opens.
 * It is off unless Developer Tools turns it on.
 */
public final class TrackerReconnectPolicy {
    public static final long BASE_DELAY_MS = 2_000L;
    public static final long MAX_DELAY_MS = 10_000L;
    public static final long HELLO_ACK_OPEN_SOCKET_TIMEOUT_MS = 30_000L;
    public static final long HELLO_ACK_LEGACY_TIMEOUT_MS = 10_000L;
    public static final long HEARTBEAT_ACK_TIMEOUT_MS = 10_000L;

    public enum RetryKind {
        CONNECT_FAILURE,
        ACK_TIMEOUT
    }

    private long nextDelayMs = BASE_DELAY_MS;

    public synchronized long peekDelayMs(RetryKind kind, boolean legacyImmediateAckRetry) {
        return selectDelay(kind, legacyImmediateAckRetry, false);
    }

    public synchronized long takeRetryDelay(RetryKind kind, boolean legacyImmediateAckRetry) {
        return takeRetryDelay(kind, legacyImmediateAckRetry, false, 0d);
    }

    /**
     * Same ladder as {@link #takeRetryDelay(RetryKind, boolean)}. When jitter
     * is on, the returned wait is equal jitter of that step: half the step
     * plus a uniform draw of the other half. The stored next step stays the
     * unjittered ladder value, and the result is never above 10 seconds.
     * A legacy immediate ack retry stays at 0.
     */
    public synchronized long takeRetryDelay(
            RetryKind kind,
            boolean legacyImmediateAckRetry,
            boolean jitterEnabled,
            double randomUnit) {
        long delayMs = selectDelay(kind, legacyImmediateAckRetry, true);
        if (delayMs <= 0L || !jitterEnabled) return delayMs;
        return equalJitterMs(delayMs, randomUnit);
    }

    /**
     * Equal jitter of one ladder step. {@code randomUnit} is clamped to
     * 0..1. The result is from half the step through the full step, inclusive,
     * and is capped at {@link #MAX_DELAY_MS}.
     */
    public static long equalJitterMs(long delayMs, double randomUnit) {
        long cappedMs = Math.min(Math.max(delayMs, 0L), MAX_DELAY_MS);
        long halfMs = cappedMs / 2L;
        long spanMs = cappedMs - halfMs;
        double unit = randomUnit;
        if (unit < 0d) unit = 0d;
        if (unit > 1d) unit = 1d;
        long jitterMs = (long) Math.floor(unit * (spanMs + 1L));
        if (jitterMs > spanMs) jitterMs = spanMs;
        if (jitterMs < 0L) jitterMs = 0L;
        return halfMs + jitterMs;
    }

    public synchronized void onTransportOpened(boolean legacyImmediateAckRetry) {
        if (legacyImmediateAckRetry) {
            nextDelayMs = BASE_DELAY_MS;
        }
    }

    public synchronized void onHelloAcknowledged() {
        nextDelayMs = BASE_DELAY_MS;
    }

    public synchronized void reset() {
        nextDelayMs = BASE_DELAY_MS;
    }

    public static long helloAckTimeoutMs(boolean socketOpen, boolean legacyImmediateAckRetry) {
        if (legacyImmediateAckRetry || !socketOpen) {
            return HELLO_ACK_LEGACY_TIMEOUT_MS;
        }
        return HELLO_ACK_OPEN_SOCKET_TIMEOUT_MS;
    }

    public static boolean helloAckTimedOut(
            long sentAtMs,
            long nowMs,
            boolean socketOpen,
            boolean legacyImmediateAckRetry) {
        if (sentAtMs <= 0L || nowMs < sentAtMs) return false;
        return nowMs - sentAtMs > helloAckTimeoutMs(socketOpen, legacyImmediateAckRetry);
    }

    private long selectDelay(RetryKind kind, boolean legacyImmediateAckRetry, boolean commit) {
        if (kind == RetryKind.ACK_TIMEOUT && legacyImmediateAckRetry) {
            return 0L;
        }
        long delayMs = nextDelayMs;
        if (commit) {
            nextDelayMs = Math.min(nextDelayMs * 2L, MAX_DELAY_MS);
        }
        return delayMs;
    }
}
