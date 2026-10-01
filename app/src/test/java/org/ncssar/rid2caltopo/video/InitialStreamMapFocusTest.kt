package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InitialStreamMapFocusTest {
    @Test fun zoomWhileWaitingForRidPreservesManualViewport() {
        var adjusted = false
        assertNull(initialStreamMapFocus(true, null, adjusted, listOf(null)))
        if (shouldSuspendMapFollow(OperatorMapGesture.Zoom)) adjusted = true
        val focus = initialStreamMapFocus(true, null, adjusted, listOf("mini-rid"))
        assertNull(focus)
        assertEquals(false, shouldReleaseFocusedDroneForMapGesture(
            MapPanePresentationMode.Full, false, OperatorMapGesture.Zoom))
        if (shouldSuspendMapFollow(OperatorMapGesture.Pan)) adjusted = true
        assertNull(initialStreamMapFocus(true, null, adjusted, listOf("mini-rid")))
    }
    @Test fun telemetryArrivalOverridesEarlierPanAndFocusWithoutRepeating() {
        val arrivals = StreamFocusArrival()
        var focus: String? = "other-drone"
        var adjusted = true
        assertNull(arrivals.observe(listOf(null)))
        arrivals.observe(listOf("mini-rid"))?.let {
            focus = it
            adjusted = false
        }
        assertEquals("mini-rid", focus)
        assertEquals(true, shouldFollowFocusedDrone(MapPanePresentationMode.Full, true, true, adjusted))
        // A later pan must not be undone by updates, loss, or reconnect.
        assertNull(arrivals.observe(listOf("MINI-RID")))
        assertNull(arrivals.observe(emptyList()))
        assertNull(arrivals.observe(listOf("mini-rid")))
    }

    @Test fun telemetryArrivalSelectsDroneEvenWithFollowDisabled() {
        val arrivals = StreamFocusArrival()
        assertEquals("mini-rid", arrivals.observe(listOf("mini-rid")))
        assertEquals(false, shouldFollowFocusedDrone(MapPanePresentationMode.Full, false, true, true))
    }

    @Test fun telemetryArrivalAvoidsAmbiguityButAcceptsOneNewMatch() {
        val arrivals = StreamFocusArrival()
        assertNull(arrivals.observe(listOf("a", "b")))
        assertNull(arrivals.observe(listOf("b")))
        assertEquals("c", arrivals.observe(listOf("b", "c", null)))
        assertNull(arrivals.observe(listOf("b", "c")))
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
