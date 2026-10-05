package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProximitySpeechScheduleTest {
    private val start = 1_800_000_000_000L

    @After
    fun tearDown() {
        SpokenWarningCenter.resetForTests()
    }

    @Test
    fun repeatIntervalMatchesIos() {
        assertEquals(30_000L, ProximitySpeechSchedule.REPEAT_INTERVAL_MS)
    }

    @Test
    fun repeatsEveryThirtySecondsWhileActive() {
        val schedule = ProximitySpeechSchedule()
        assertEquals(
            ProximitySpeechSchedule.Announcement.NewInstance,
            schedule.next(7L, suspended = false, enabled = true, nowMs = start)
        )
        val repeats = mutableListOf<Long>()
        for (second in 1..95) {
            val now = start + second * 1_000L
            val announcement = schedule.next(7L, suspended = false, enabled = true, nowMs = now)
            if (announcement != null) {
                assertEquals(ProximitySpeechSchedule.Announcement.Repeat, announcement)
                repeats += second.toLong()
            }
        }
        assertEquals(listOf(30L, 60L, 90L), repeats)
    }

    @Test
    fun noRepeatWhileSuspendedAndResumeIsTreatedAsNew() {
        val schedule = ProximitySpeechSchedule()
        schedule.next(7L, suspended = false, enabled = true, nowMs = start)
        assertNull(schedule.next(null, suspended = true, enabled = true, nowMs = start + 5_000L))
        assertNull(schedule.next(null, suspended = true, enabled = true, nowMs = start + 40_000L))
        assertEquals(
            ProximitySpeechSchedule.Announcement.NewInstance,
            schedule.next(7L, suspended = false, enabled = true, nowMs = start + 41_000L)
        )
        assertNull(schedule.next(7L, suspended = false, enabled = true, nowMs = start + 70_000L))
        assertEquals(
            ProximitySpeechSchedule.Announcement.Repeat,
            schedule.next(7L, suspended = false, enabled = true, nowMs = start + 71_000L)
        )
    }

    @Test
    fun clearedOrDisabledAlertStopsRepeats() {
        val schedule = ProximitySpeechSchedule()
        schedule.next(1L, suspended = false, enabled = true, nowMs = start)
        assertNull(schedule.next(null, suspended = false, enabled = true, nowMs = start + 40_000L))
        assertNull(schedule.next(2L, suspended = false, enabled = false, nowMs = start + 41_000L))
        assertEquals(
            ProximitySpeechSchedule.Announcement.NewInstance,
            schedule.next(2L, suspended = false, enabled = true, nowMs = start + 42_000L)
        )
    }

    @Test
    fun scheduledRepeatPassesThePerPairSpeechCooldown() {
        val schedule = ProximitySpeechSchedule()
        val cooldown = ProximitySpeechSchedule.REPEAT_INTERVAL_MS
        schedule.next(7L, suspended = false, enabled = true, nowMs = start)
        SpokenWarningCenter.requestWarning(SpokenWarningKind.Proximity, "A|B", start, cooldown)
        val first = SpokenWarningCenter.requests.value
        SpokenWarningCenter.consume(first!!.requestId)

        val repeatAt = start + cooldown
        assertEquals(
            ProximitySpeechSchedule.Announcement.Repeat,
            schedule.next(7L, suspended = false, enabled = true, nowMs = repeatAt)
        )
        SpokenWarningCenter.requestWarning(SpokenWarningKind.Proximity, "A|B", repeatAt, cooldown)
        assertEquals("Proximity", SpokenWarningCenter.requests.value?.phrase)
    }
}
