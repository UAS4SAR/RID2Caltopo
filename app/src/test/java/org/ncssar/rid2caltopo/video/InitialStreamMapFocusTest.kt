package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InitialStreamMapFocusTest {
    @Test fun arrivalAllowsDelayedRidAfterPreStreamPanButDoesNotUndoLaterPan() {
        val arrivals = StreamFocusArrival()
        var adjusted = true
        if (arrivals.observe(setOf("mini"), true, false)) adjusted = false
        assertNull(initialStreamMapFocus(true, null, adjusted, listOf(null)))
        assertEquals("mini-rid", initialStreamMapFocus(true, null, adjusted, listOf("mini-rid")))
        adjusted = true
        assertEquals(false, arrivals.observe(setOf("mini"), true, false))
        arrivals.observe(emptySet(), true, false)
        assertEquals(false, arrivals.observe(setOf("mini"), true, false))
        assertNull(initialStreamMapFocus(true, null, adjusted, listOf("mini-rid")))
    }
    @Test fun arrivalPreservesExistingFocusDisabledFollowAndMultipleStreams() {
        val arrivals = StreamFocusArrival()
        assertEquals(false, arrivals.observe(setOf("a", "b"), true, false))
        assertEquals(false, arrivals.observe(setOf("b"), true, false))
        assertEquals(false, arrivals.observe(setOf("c"), true, true))
        assertEquals(false, arrivals.observe(setOf("d"), false, false))
    }

    @Test fun soleResolvedStreamFocusesWhenFollowIsEnabled() {
        assertEquals("drone-a", initialStreamMapFocus(true, null, false, listOf("drone-a")))
        assertNull(initialStreamMapFocus(true, null, false, emptyList()))
        assertNull(initialStreamMapFocus(true, null, false, listOf(null)))
        assertNull(initialStreamMapFocus(true, null, false, listOf("")))
        assertNull(initialStreamMapFocus(true, null, false, listOf("drone-a", null)))
        assertNull(initialStreamMapFocus(true, null, false, listOf("drone-a", "drone-b")))
    }
    @Test fun manualViewAndDisabledFollowArePreserved() {
        assertNull(initialStreamMapFocus(false, null, false, listOf("drone-a")))
        assertNull(initialStreamMapFocus(true, "drone-b", false, listOf("drone-a")))
        assertNull(initialStreamMapFocus(true, null, true, listOf("drone-a")))
    }
}
