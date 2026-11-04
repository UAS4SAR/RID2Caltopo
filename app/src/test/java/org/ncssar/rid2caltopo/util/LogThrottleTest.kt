package org.ncssar.rid2caltopo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

class LogThrottleTest {
    private val nowMs = AtomicLong(1_000_000L)

    @Before
    fun setUp() {
        LogThrottle.resetForTest()
        nowMs.set(1_000_000L)
        LogThrottle.clockMs = LongSupplier { nowMs.get() }
    }

    @Test
    fun emit_emitsOnChange() {
        assertEquals("sessions=1", LogThrottle.emit("k", "sessions=1", onChangeOnly = true))
        assertNull(LogThrottle.emit("k", "sessions=1", onChangeOnly = true))
        assertEquals("sessions=2", LogThrottle.emit("k", "sessions=2", onChangeOnly = true))
    }

    @Test
    fun emit_suppressesDuplicates() {
        assertNotNull(LogThrottle.emit("dup", "same", onChangeOnly = true))
        repeat(5) {
            assertNull(LogThrottle.emit("dup", "same", onChangeOnly = true))
        }
    }

    @Test
    fun emit_emitsSummaryAfterInterval() {
        assertEquals("hb ok", LogThrottle.emit("hb", "hb ok", minIntervalMs = 1_000L, onChangeOnly = true))
        assertNull(LogThrottle.emit("hb", "hb ok", minIntervalMs = 1_000L, onChangeOnly = true))
        assertNull(LogThrottle.emit("hb", "hb ok", minIntervalMs = 1_000L, onChangeOnly = true))
        nowMs.addAndGet(1_000L)
        assertEquals(
            "hb ok (repeated 2×)",
            LogThrottle.emit("hb", "hb ok", minIntervalMs = 1_000L, onChangeOnly = true),
        )
    }

    @Test
    fun emit_intervalGateWithoutOnChange() {
        assertEquals("tick", LogThrottle.emit("rate", "tick", minIntervalMs = 500L))
        assertNull(LogThrottle.emit("rate", "tick", minIntervalMs = 500L))
        nowMs.addAndGet(500L)
        assertEquals("tick (repeated 1×)", LogThrottle.emit("rate", "tick", minIntervalMs = 500L))
    }

    @Test
    fun shouldLog_intervalGate() {
        assertTrue(LogThrottle.shouldLog("s", 1_000L))
        assertFalse(LogThrottle.shouldLog("s", 1_000L))
        nowMs.addAndGet(999L)
        assertFalse(LogThrottle.shouldLog("s", 1_000L))
        nowMs.addAndGet(1L)
        assertTrue(LogThrottle.shouldLog("s", 1_000L))
    }

    @Test
    fun heartbeatStyleStableStateMessage_summarizesAtSixtySeconds() {
        assertEquals(
            "heartbeat_ack received: ownerLeaseExpireTs=0",
            LogThrottle.emit(
                "tracker.heartbeat_ack",
                "heartbeat_ack received: ownerLeaseExpireTs=0",
                minIntervalMs = 60_000L,
                onChangeOnly = true,
            ),
        )
        // Changing rtt must not be part of the throttle message.
        assertNull(
            LogThrottle.emit(
                "tracker.heartbeat_ack",
                "heartbeat_ack received: ownerLeaseExpireTs=0",
                minIntervalMs = 60_000L,
                onChangeOnly = true,
            ),
        )
        nowMs.addAndGet(60_000L)
        assertEquals(
            "heartbeat_ack received: ownerLeaseExpireTs=0 (repeated 1×)",
            LogThrottle.emit(
                "tracker.heartbeat_ack",
                "heartbeat_ack received: ownerLeaseExpireTs=0",
                minIntervalMs = 60_000L,
                onChangeOnly = true,
            ),
        )
    }

    @Test
    fun bridgeSummary_emitsAtMostOncePerFiveSeconds() {
        assertTrue(LogThrottle.shouldLog("dronescout.bridge.summary", 5_000L))
        assertFalse(LogThrottle.shouldLog("dronescout.bridge.summary", 5_000L))
        nowMs.addAndGet(5_000L)
        assertTrue(LogThrottle.shouldLog("dronescout.bridge.summary", 5_000L))
    }

    @Test
    fun mapSuppress_logsOncePerFlightKey() {
        assertNotNull(
            LogThrottle.emit(
                "map.suppress.rid/flight1",
                "newWaypoint(): non-published (peer owner zone-a)",
                0L,
                true,
            ),
        )
        assertNull(
            LogThrottle.emit(
                "map.suppress.rid/flight1",
                "newWaypoint(): non-published (peer owner zone-a)",
                0L,
                true,
            ),
        )
        assertNotNull(
            LogThrottle.emit(
                "map.suppress.rid/flight2",
                "newWaypoint(): non-published (peer owner zone-a)",
                0L,
                true,
            ),
        )
    }
}
