package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoNode

class R2CViewCoordinatorStatusTest {
    @Test
    fun coordinatorStatusDisplayText_compactsTrackerStatuses() {
        assertEquals("Tracker verified", coordinatorStatusDisplayText("Tracker verified"))
        assertEquals("Tracker standby", coordinatorStatusDisplayText("Tracker link standby"))
        assertEquals("Tracker degraded", coordinatorStatusDisplayText("Tracker link degraded"))
        assertEquals("Disabled", coordinatorStatusDisplayText("Tracker link disabled"))
        assertEquals("Not configured", coordinatorStatusDisplayText("Tracker link not configured"))
    }

    @Test
    fun coordinatorStatusDisplayText_compactsMqttStatuses() {
        assertEquals("MQTT OK", coordinatorStatusDisplayText("MQTT link healthy"))
        assertEquals("MQTT degraded", coordinatorStatusDisplayText("MQTT link degraded"))
        assertEquals("Unavailable", coordinatorStatusDisplayText("Coordinator unavailable"))
        assertEquals(
            "Re-enroll required",
            coordinatorStatusDisplayText("Tracker authorization rejected; re-enrollment required")
        )
    }

    @Test
    fun coordinatorStatusDisplayText_handlesGenericAndUnknownStatuses() {
        assertEquals("Not configured", coordinatorStatusDisplayText("R2C link not configured"))
        assertEquals("Custom link state", coordinatorStatusDisplayText("Custom link state"))
    }

    @Test
    fun incidentMapDisplayValue_usesMapNameOrStandaloneDefault() {
        assertEquals(org.ncssar.rid2caltopo.data.CaltopoClient.GetStandaloneIncident() + " · No map", incidentMapDisplayValue(CaltopoConnectionState.StandAlone))
        assertEquals(
            "Washoe Search",
            incidentMapDisplayValue(
                CaltopoConnectionState.MapSelected(
                    CaltopoNode.MapNode("map-42", "Washoe Search", 0L),
                ),
            ),
        )
        assertEquals(
            "map-42",
            incidentMapDisplayValue(
                CaltopoConnectionState.MapSelected(CaltopoNode.MapNode("map-42", " ", 0L)),
            ),
        )
    }
}
