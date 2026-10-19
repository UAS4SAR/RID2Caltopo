package org.ncssar.rid2caltopo.mapping

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class MappingJobLifecycleTest {
    private class Queued : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    @Test fun immediateOperatorCancellationReportsTerminalStateWithoutRunningWork() {
        val dispatch = Queued(); val scope = CoroutineScope(SupervisorJob() + dispatch)
        var ran = false; var active = true; var cause: Throwable? = null; var owner: Job? = null
        val work = mappingJob(scope, { job, failure -> if (job === owner) { active = false; cause = failure } }) { ran = true }
        owner = work; work.start()
        work.cancel(CancellationException("Processing cancelled; originals retained"))
        dispatch.drain()
        assertFalse(ran); assertFalse(active)
        assertEquals("Processing cancelled; originals retained", cause?.message)
        scope.cancel()
    }
    @Test fun PreparationFailureStillReportsStoppedJobAndAllowsAnotherJob() {
        val dispatch = Queued(); val scope = CoroutineScope(SupervisorJob() + dispatch + CoroutineExceptionHandler { _, _ -> })
        var cause: Throwable? = null
        val first = mappingJob(scope, { _, failure -> cause = failure }) { throw IllegalStateException("Storage unavailable") }
        first.start(); dispatch.drain()
        assertEquals("Storage unavailable", cause?.message); assertFalse(first.isActive)
        var completed = false
        val second = mappingJob(scope, { _, failure -> completed = failure == null }) { }
        second.start(); dispatch.drain(); assertTrue(completed)
        scope.cancel()
    }
}
