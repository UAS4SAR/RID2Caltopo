package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdleTimeoutPolicyTest {
    @Test
    fun activityRecreationPreservesSessionStart() {
        assertEquals(
            1_000L,
            ApplicationIdleTimeoutPolicy.sessionStartedAtMsec(1_000L, true, 5_000L),
        )
    }

    @Test
    fun newActivitySessionRefreshesSessionStart() {
        assertEquals(
            5_000L,
            ApplicationIdleTimeoutPolicy.sessionStartedAtMsec(1_000L, false, 5_000L),
        )
    }

    @Test
    fun disabledTimeoutDoesNotSchedule() {
        assertEquals(
            ApplicationIdleTimeoutPolicy.DISABLED,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(1_000L, 0L, 0L, 2_000L),
        )
    }

    @Test
    fun timerStartsAtApplicationLaunch() {
        assertEquals(
            7_200_000L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(1_000L, 0L, 120L, 1_000L),
        )
    }

    @Test
    fun timerUsesRemainingTimeSinceLatestRidMessage() {
        val lastRidMessageAt = 3_600_000L
        val now = lastRidMessageAt + 1_800_000L

        assertEquals(
            5_400_000L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(1_000L, lastRidMessageAt, 120L, now),
        )
    }

    @Test
    fun timerExpiresAtRidMessageDeadline() {
        val lastRidMessageAt = 10_000L

        assertEquals(
            0L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(
                1_000L,
                lastRidMessageAt,
                120L,
                lastRidMessageAt + 7_200_000L,
            ),
        )
    }

    @Test
    fun completedProtectedActivityRestartsIdleCountdown() {
        val protectedActivityAt = 7_000_000L
        val now = protectedActivityAt + 30_000L

        assertEquals(
            90_000L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(
                1_000L,
                0L,
                protectedActivityAt,
                2L,
                now,
            ),
        )
    }
}
