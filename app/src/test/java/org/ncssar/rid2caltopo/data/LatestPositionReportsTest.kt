package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class LatestPositionReportsTest {
    private var now = 0L
    private val worker = ArrayDeque<Runnable>()
    private val timers = mutableListOf<Pair<Long, Runnable>>()
    private val sent = mutableListOf<Pair<Long, Int>>()
    private val dropped = mutableListOf<Int>()
    private val reports = LatestPositionReports<Int>(
        Executor { worker.add(it) },
        { task, delay -> timers.add(now + delay to task) },
        { now }, { sent.add(now to it) }, { dropped.add(it) })

    private fun tick(time: Long) {
        now = time
        val ready = timers.filter { it.first <= now }
        timers.removeAll(ready.toSet())
        ready.forEach { it.second.run() }
    }

    @Test fun blockedWorkerSendsOnlyNewestThenWaitsFiveSeconds() {
        reports.submit("drone", 1)
        tick(0) // Worker blocked by another API call.
        reports.submit("drone", 2)
        reports.submit("drone", 3)
        now = 10_000
        worker.remove().run()
        assertEquals(listOf(10_000L to 3), sent)
        reports.submit("drone", 4)
        tick(14_999)
        assertTrue(worker.isEmpty())
        reports.submit("drone", 5)
        tick(15_000)
        worker.remove().run()
        assertEquals(listOf(10_000L to 3, 15_000L to 5), sent)
        assertEquals(listOf(1, 2, 4), dropped)
    }

    @Test fun cancelRemovesPendingReportWithoutResettingCooldown() {
        reports.submit("drone", 1)
        tick(0); worker.remove().run()
        reports.submit("drone", 2)
        reports.cancel("drone")
        reports.submit("drone", 3)
        tick(4_999); assertTrue(worker.isEmpty())
        tick(5_000); worker.remove().run()
        assertEquals(listOf(0L to 1, 5_000L to 3), sent)
    }

    @Test fun independentAircraftDoNotBlockEachOther() {
        reports.submit("a", 1); reports.submit("b", 2)
        tick(0)
        while (worker.isNotEmpty()) worker.remove().run()
        assertEquals(listOf(0L to 1, 0L to 2), sent)
    }
    @Test fun failedSendDoesNotRetryAndStillWaitsFiveSeconds() {
        val attempts = mutableListOf<Int>()
        lateinit var queue: LatestPositionReports<Int>
        queue = LatestPositionReports(Executor { worker.add(it) },
            { task, delay -> timers.add(now + delay to task) }, { now }, { value ->
                attempts.add(value)
                if (value == 1) {
                    queue.submit("a", 2)
                    queue.submit("a", 3)
                    now = 10_000
                    throw IllegalStateException("lost connection")
                }
            }, {})
        queue.submit("a", 1)
        tick(0)
        try { worker.remove().run(); fail("expected simulated failure") } catch (_: IllegalStateException) { }
        tick(14_999); assertTrue(worker.isEmpty())
        tick(15_000); worker.remove().run()
        assertEquals(listOf(1, 3), attempts)
    }

}
