package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtrxGattTest {
    private val started = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private var refuse: String? = null
    private val q = BtrxGattQueue<String>(start = { started += it; it != refuse }, onStartFailed = { failed += it })

    @Test
    fun onlyOneOperationIsInFlightAndOrderIsKept() {
        q.enqueue("mtu"); q.enqueue("discover"); q.enqueue("notify")
        assertEquals(listOf("mtu"), started)
        assertEquals("mtu", q.inFlight)
        assertEquals(3, q.size)
        assertEquals("mtu", q.complete())
        assertEquals(listOf("mtu", "discover"), started)
        q.complete(); q.complete()
        assertEquals(listOf("mtu", "discover", "notify"), started)
        assertNull(q.inFlight)
        assertEquals(0, q.size)
    }

    @Test
    fun heartbeatQueuedDuringACommandWaitsForIt() {
        q.enqueue("write:PAIR_CONFIRM")
        q.enqueue("write:heartbeat") // the bench failure: two overlapping writes
        assertEquals(listOf("write:PAIR_CONFIRM"), started)
        q.complete()
        assertEquals(listOf("write:PAIR_CONFIRM", "write:heartbeat"), started)
    }

    @Test
    fun lateCallbackWithNothingInFlightIsHarmless() {
        assertNull(q.complete())
        q.enqueue("read")
        q.complete()
        assertNull(q.complete())
        assertEquals(listOf("read"), started)
    }

    @Test
    fun refusedStartIsReportedAndDoesNotRunTheNextOp() {
        refuse = "write"
        q.enqueue("write"); q.enqueue("read")
        assertEquals(listOf("write"), failed)
        assertEquals(listOf("write"), started)
        assertNull(q.inFlight)
    }

    @Test
    fun clearDropsPendingAndIgnoresLaterOps() {
        q.enqueue("a"); q.enqueue("b")
        q.clear()
        q.enqueue("c")
        assertNull(q.complete())
        assertEquals(listOf("a"), started)
    }

    // ---- FromRadio drain ----

    private val reads = mutableListOf<Int>()
    private val drain = BtrxFromRadioDrain { reads += reads.size }

    @Test
    fun drainReadsUntilEmpty() {
        drain.kick()
        assertEquals(1, reads.size)
        assertTrue(drain.onRead(byteArrayOf(1)))
        assertTrue(drain.onRead(byteArrayOf(2)))
        assertEquals(3, reads.size)
        assertFalse(drain.onRead(ByteArray(0)))
        assertEquals(3, reads.size)
        assertFalse(drain.draining)
    }

    @Test
    fun notifyDuringADrainDoesNotStartASecondReadButAddsOnePass() {
        drain.kick()
        drain.kick() // FromNum notify while the first read is outstanding
        assertEquals(1, reads.size)
        assertTrue(drain.onRead(byteArrayOf(1)))
        assertEquals(2, reads.size)
        assertFalse(drain.onRead(ByteArray(0))) // empty, but a notify came in: read once more
        assertEquals(3, reads.size)
        assertTrue(drain.draining)
        assertFalse(drain.onRead(ByteArray(0)))
        assertFalse(drain.draining)
        assertEquals(3, reads.size)
    }

    @Test
    fun drainAndQueueTogetherNeverOverlapAReadWithAWrite() {
        val ops = mutableListOf<String>()
        val queue = BtrxGattQueue<String>(start = { ops += "start $it"; true }, onStartFailed = {})
        val d = BtrxFromRadioDrain { queue.enqueue("read") }
        queue.enqueue("write want_config")
        d.kick()
        queue.enqueue("write heartbeat")
        assertEquals(listOf("start write want_config"), ops)
        queue.complete() // want_config written
        assertEquals("read", queue.inFlight)
        queue.complete(); d.onRead(byteArrayOf(9)) // got one message -> another read is queued after the heartbeat
        assertEquals("write heartbeat", queue.inFlight)
        queue.complete()
        assertEquals("read", queue.inFlight)
        queue.complete(); d.onRead(ByteArray(0))
        assertNull(queue.inFlight)
        assertEquals(listOf("start write want_config", "start read", "start write heartbeat", "start read"), ops)
    }

    @Test
    fun meshtasticUuidsAreTheStandardOnes() {
        assertEquals("6ba1b218-15a8-461f-9fa8-5dcae273eafd", MeshtasticBle.SERVICE_UUID)
        assertEquals("f75c76d2-129e-4dad-a1dd-7866124401e7", MeshtasticBle.TO_RADIO_UUID)
        assertEquals("2c55e69e-4993-11ed-b878-0242ac120002", MeshtasticBle.FROM_RADIO_UUID)
        assertEquals("ed9da18c-a800-4f66-a670-aa7547e34453", MeshtasticBle.FROM_NUM_UUID)
    }
}
