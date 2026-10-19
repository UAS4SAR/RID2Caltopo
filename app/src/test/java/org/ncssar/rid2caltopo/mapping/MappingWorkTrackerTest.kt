package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.data.ApplicationIdleTimeoutPolicy

class MappingWorkTrackerTest {
    private fun idleRemaining(work:MappingWorkState,now:Long)=ApplicationIdleTimeoutPolicy.remainingDelayMsec(
        1_000L,0L,work.lastActivityAtMsec,work.active,0L,2L,now)

    @Test fun untouchedFlightAndIntakeSuspendIdleThenClosureStartsFullCountdown() {
        var now=30_000L;var policyChanges=0
        val tracker=MappingWorkTracker(now={now},changed={policyChanges++})
        val connection=tracker.begin{}
        now=600_000L
        assertEquals(ApplicationIdleTimeoutPolicy.SUSPENDED,idleRemaining(tracker.state.value,now))
        tracker.finish(connection)
        assertEquals(120_000L,idleRemaining(tracker.state.value,now))
        assertEquals(1L,idleRemaining(tracker.state.value,now+119_999L))
        assertEquals(0L,idleRemaining(tracker.state.value,now+120_000L))
        assertEquals(2,policyChanges)
    }
    @Test fun connectionClosureKeepsConcurrentReconstructionProtected() {
        var now=10_000L;val tracker=MappingWorkTracker(now={now})
        val connection=tracker.begin{};val reconstruction=tracker.begin{}
        now=600_000L;tracker.finish(connection)
        assertEquals(ApplicationIdleTimeoutPolicy.SUSPENDED,idleRemaining(tracker.state.value,now))
        now=900_000L;tracker.finish(reconstruction)
        assertEquals(120_000L,idleRemaining(tracker.state.value,now))
    }
    @Test fun staleConnectionCleanupCannotReleaseItsReplacement() {
        var now=10_000L;val tracker=MappingWorkTracker(now={now})
        val old=tracker.begin{};tracker.finish(old)
        now=20_000L;val replacement=tracker.begin{}
        tracker.finish(old)
        assertTrue(tracker.state.value.active)
        assertEquals(ApplicationIdleTimeoutPolicy.SUSPENDED,idleRemaining(tracker.state.value,900_000L))
        tracker.finish(replacement);assertFalse(tracker.state.value.active)
    }
    @Test fun explicitCancellationClosesAllResourcesWithoutRepeatedCancellation() {
        val tracker=MappingWorkTracker(now={600_000L})
        var calls=0;lateinit var one:String;lateinit var two:String
        one=tracker.begin{calls++;tracker.finish(one)}
        two=tracker.begin{calls++;tracker.finish(two)}
        tracker.cancelActive();tracker.cancelActive()
        assertEquals(2,calls);assertFalse(tracker.state.value.active)
        assertEquals(120_000L,idleRemaining(tracker.state.value,600_000L))
    }
    @Test fun incidentMapIsRetainedDuringWorkAndForNormalQuietPeriodAfterward() {
        var now=10_000L;val tracker=MappingWorkTracker(now={now})
        val run=tracker.begin{}
        assertTrue(tracker.state.value.preventsMapDisconnect(900_000L,300_000L))
        now=900_000L;tracker.finish(run)
        assertTrue(tracker.state.value.preventsMapDisconnect(now+299_999L,300_000L))
        assertFalse(tracker.state.value.preventsMapDisconnect(now+300_000L,300_000L))
    }
    @Test fun taskRemovalDoesNotCancelReconstructionButExplicitExitDoes() {
        val tracker=MappingWorkTracker()
        var transferCancelled=0;var processingCancelled=0
        tracker.begin({transferCancelled++})
        tracker.begin({processingCancelled++},survivesTaskRemoval=true)
        tracker.cancelActive(explicitExit=false)
        assertEquals(1,transferCancelled);assertEquals(0,processingCancelled)
        assertTrue(tracker.state.value.active)
        tracker.cancelActive(explicitExit=true)
        assertEquals(1,processingCancelled)
    }

}
