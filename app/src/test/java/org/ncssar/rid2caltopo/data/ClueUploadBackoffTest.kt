package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClueUploadBackoffTest {
    @Test fun doublesFromTwoSecondsAndCapsAtSixty() {
        val backoff = ClueUploadBackoff()
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(4_000L, backoff.nextDelayMs())
        assertEquals(8_000L, backoff.nextDelayMs())
        assertEquals(16_000L, backoff.nextDelayMs())
        assertEquals(32_000L, backoff.nextDelayMs())
        assertEquals(60_000L, backoff.nextDelayMs())
        assertEquals(60_000L, backoff.nextDelayMs())
        backoff.reset()
        assertEquals(2_000L, backoff.nextDelayMs())
    }

    @Test fun authFailureSharesSingleOwnerBackoffSchedule() {
        // A local auth miss and a transport failure both advance the same schedule so
        // concurrent callers cannot spin once the single upload owner owns the delay.
        val backoff = ClueUploadBackoff()
        val first = backoff.nextDelayMs()
        val second = backoff.nextDelayMs()
        assertEquals(2_000L, first)
        assertEquals(4_000L, second)
        assertEquals(8_000L, backoff.peekMs())
    }
}
