/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data;

import java.util.concurrent.TimeUnit;

public final class ApplicationIdleTimeoutPolicy {
    public static final long DISABLED = -1L;
    /** Protected work (an offline map download, including AOL preparation) is running; no countdown. */
    public static final long SUSPENDED = -2L;

    private ApplicationIdleTimeoutPolicy() {}

    public static long sessionStartedAtMsec(
            long existingSessionStartedAtMsec,
            boolean activityRecreated,
            long nowMsec) {
        if (activityRecreated && existingSessionStartedAtMsec > 0L) {
            return existingSessionStartedAtMsec;
        }
        return nowMsec;
    }

    public static long remainingDelayMsec(
            long appStartedAtMsec,
            long lastRidMessageAtMsec,
            long maximumIdleMinutes,
            long nowMsec) {
        return remainingDelayMsec(
                appStartedAtMsec,
                lastRidMessageAtMsec,
                0L,
                maximumIdleMinutes,
                nowMsec);
    }

    public static long remainingDelayMsec(
            long appStartedAtMsec,
            long lastRidMessageAtMsec,
            long lastProtectedActivityAtMsec,
            long maximumIdleMinutes,
            long nowMsec) {
        return remainingDelayMsec(appStartedAtMsec, lastRidMessageAtMsec,
                lastProtectedActivityAtMsec, 0L, maximumIdleMinutes, nowMsec);
    }

    public static long remainingDelayMsec(
            long appStartedAtMsec, long lastRidMessageAtMsec,
            long lastProtectedActivityAtMsec, long lastUserInteractionAtMsec,
            long maximumIdleMinutes, long nowMsec) {
        return remainingDelayMsec(appStartedAtMsec, lastRidMessageAtMsec,
                lastProtectedActivityAtMsec, false, lastUserInteractionAtMsec,
                maximumIdleMinutes, nowMsec);
    }

    /**
     * While protected work is active the timeout is suspended. When it ends, the caller records the
     * end time as lastProtectedActivityAtMsec, so a full countdown starts from the end of the work.
     * Matches iOS ApplicationIdleTimeoutPolicy(protectedActivityActive:).
     */
    public static long remainingDelayMsec(
            long appStartedAtMsec, long lastRidMessageAtMsec,
            long lastProtectedActivityAtMsec, boolean protectedActivityActive,
            long lastUserInteractionAtMsec, long maximumIdleMinutes, long nowMsec) {
        if (maximumIdleMinutes <= 0) return DISABLED;
        if (protectedActivityActive) return SUSPENDED;

        long timeoutMsec = TimeUnit.MINUTES.toMillis(maximumIdleMinutes);
        long baselineMsec = Math.max(
                Math.max(appStartedAtMsec, lastUserInteractionAtMsec),
                Math.max(lastRidMessageAtMsec, lastProtectedActivityAtMsec));
        long elapsedMsec = Math.max(0L, nowMsec - baselineMsec);
        return Math.max(0L, timeoutMsec - elapsedMsec);
    }
}
