package org.ncssar.rid2caltopo.ui
import org.junit.Assert.*
import org.junit.Test
class AwaitingMapReminderTest {
    @Test fun laterSuppressesRepeatsButNextFlightReminds() {
        val reminder = AwaitingMapReminder()
        assertFalse(reminder.shouldPresent(emptySet(), false))
        assertTrue(reminder.shouldPresent(setOf("first"), false))
        repeat(5) { assertFalse(reminder.shouldPresent(setOf("first"), false)) }
        assertFalse(reminder.shouldPresent(emptySet(), false))
        assertFalse(reminder.shouldPresent(setOf("first"), false))
        assertTrue(reminder.shouldPresent(setOf("first", "second"), false))
    }
    @Test fun selectedMapDoesNotConsumeReminderAndReconnectDoesNotReopenIt() {
        val reminder = AwaitingMapReminder()
        assertFalse(reminder.shouldPresent(setOf("flight"), true))
        assertTrue(reminder.shouldPresent(setOf("flight"), false))
        assertFalse(reminder.shouldPresent(setOf("flight"), true))
        assertFalse(reminder.shouldPresent(setOf("flight"), false))
    }
}
