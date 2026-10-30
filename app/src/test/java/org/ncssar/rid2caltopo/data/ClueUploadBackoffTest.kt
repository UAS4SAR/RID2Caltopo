package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun perClueBackoffDoesNotBlockOtherIds() {
        val table = ClueUploadBackoffTable()
        assertEquals(2_000L, table.nextDelayMs("old"))
        table.markDelayedUntil("old", 10_000L)
        assertFalse(table.isReady("old", 5_000L))
        assertTrue(table.isReady("fresh", 5_000L))
        assertEquals(2_000L, table.nextDelayMs("fresh"))
        assertEquals(4_000L, table.backoff("old").peekMs())
    }

    @Test fun timeoutAnd5xxSharePerClueScheduleWithoutBlockingPeers() {
        val table = ClueUploadBackoffTable()
        // Simulate clue A hitting 502 / timeout storm.
        assertEquals(2_000L, table.nextDelayMs("a"))
        table.markDelayedUntil("a", 8_000L)
        assertEquals(4_000L, table.nextDelayMs("a"))
        table.markDelayedUntil("a", 20_000L)
        // Clue B remains immediately ready.
        assertTrue(table.isReady("b", 9_000L))
        assertFalse(table.isReady("a", 9_000L))
        assertEquals(2_000L, table.nextDelayMs("b"))
    }
}
