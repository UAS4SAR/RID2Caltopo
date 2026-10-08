package org.ncssar.rid2caltopo.ui

import org.junit.Assert.*
import org.junit.Test

class WorkspacePresentationPolicyTest {
    @Test fun alertRequestAloneDoesNotRevealBell() {
        assertEquals(WorkspaceAlertTone.Hidden, workspaceAlertTone(false, true, true))
    }
    @Test fun visualCautionBandRejectsInvalidAndDistantTelemetry() {
        assertTrue(workspaceSeparationCaution(109.0, 109.0, 100.0))
        assertFalse(workspaceSeparationCaution(111.0, 0.0, 100.0))
        assertFalse(workspaceSeparationCaution(90.0, 111.0, 100.0))
        assertFalse(workspaceSeparationCaution(Double.NaN, null, 100.0))
        assertFalse(workspaceSeparationCaution(10.0, 10.0, 0.0))
        assertTrue(workspaceSeparationCaution(105.0, null, 100.0))
    }
    @Test fun ignoredAndUnknownAircraftRetainDistinctPrompts() {
        assertEquals(WorkspaceDroneAction.Add, workspaceDroneAction(false, false))
        assertEquals(WorkspaceDroneAction.Confirm, workspaceDroneAction(true, false))
        assertEquals(WorkspaceDroneAction.Inspect, workspaceDroneAction(true, true))
    }
}
