package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdleTimeoutPolicyTest {
    @Test
    fun userInputJustBeforeOldDeadlineGetsFullIdleInterval() {
        val input = 119_000L
        assertEquals(119_000L, ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 0L, 0L, input, 2L, 120_000L))
        assertEquals(1L, ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 0L, 0L, input, 2L, 238_999L))
        assertEquals(0L, ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 0L, 0L, input, 2L, 239_000L))
        assertEquals(ApplicationIdleTimeoutPolicy.DISABLED, ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 0L, 0L, input, 0L, 999_000L))
    }

    @Test
    fun latestRidProtectedOrUserActivityWins() {
        for (times in listOf(listOf(50_000L, 70_000L, 90_000L), listOf(90_000L, 50_000L, 70_000L), listOf(70_000L, 90_000L, 50_000L))) {
            assertEquals(110_000L, ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, times[0], times[1], times[2], 2L, 100_000L))
        }
    }

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

    @Test
    fun activeOfflineDownloadSuspendsTimeoutEvenPastTheDeadline() {
        // Two-minute timeout, nothing since t=0; a download running at t=10 min must not expire.
        assertEquals(
            ApplicationIdleTimeoutPolicy.SUSPENDED,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 0L, 30_000L, true, 0L, 2L, 600_000L),
        )
        // Disabled stays disabled.
        assertEquals(
            ApplicationIdleTimeoutPolicy.DISABLED,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 0L, 30_000L, true, 0L, 0L, 600_000L),
        )
    }

    @Test
    fun countdownRestartsFromOfflineDownloadEnd() {
        val downloadEndedAt = 600_000L
        // Older user input and RID do not shorten the countdown after the download ends.
        assertEquals(
            120_000L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 50_000L, downloadEndedAt, false, 90_000L, 2L, downloadEndedAt),
        )
        assertEquals(
            60_000L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 50_000L, downloadEndedAt, false, 90_000L, 2L, downloadEndedAt + 60_000L),
        )
        assertEquals(
            0L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(0L, 50_000L, downloadEndedAt, false, 90_000L, 2L, downloadEndedAt + 120_000L),
        )
    }
}
