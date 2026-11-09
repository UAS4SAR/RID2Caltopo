package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneOwnershipPolicyTest {
    private val initial = DroneOwnershipPolicy.Record.initial

    @Test fun standaloneEditsDirectlyAndOrganizationMembersNeedConfigAdmin() {
        assertTrue(DroneOwnershipPolicy.canChange(false, false))
        assertTrue(DroneOwnershipPolicy.canChange(true, true))
        assertFalse(DroneOwnershipPolicy.canChange(true, false))
    }

    @Test fun localEditTakesEffectWithoutClearingTheAppliedBaseline() {
        val edited = DroneOwnershipPolicy.edit(initial, DroneOwnershipPolicy.STREAMING_DEVICE)
        assertEquals(DroneOwnershipPolicy.STREAMING_DEVICE, edited.mode)
        assertEquals(DroneOwnershipPolicy.ALL, edited.appliedMode)
        assertTrue(edited.staged)
        assertEquals(initial, DroneOwnershipPolicy.edit(edited, DroneOwnershipPolicy.ALL))
    }

    @Test fun snapshotRefreshKeepsAStagedEditAndANewPublicationReplacesIt() {
        val staged = DroneOwnershipPolicy.edit(initial, DroneOwnershipPolicy.STREAMING_DEVICE)
        assertEquals(staged, DroneOwnershipPolicy.receive(staged, DroneOwnershipPolicy.ALL))
        val published = DroneOwnershipPolicy.receive(staged, DroneOwnershipPolicy.SHARED_INCIDENT)
        assertEquals(DroneOwnershipPolicy.SHARED_INCIDENT, published.mode)
        assertEquals(DroneOwnershipPolicy.SHARED_INCIDENT, published.appliedMode)
        assertFalse(published.staged)
    }

    @Test fun scopesDoNotShareAValue() {
        assertEquals(DroneOwnershipPolicy.STANDALONE, DroneOwnershipPolicy.scope(false, "https://r2c-tracker.com/ncssar"))
        assertEquals("https://r2c-tracker.com/ncssar", DroneOwnershipPolicy.scope(true, "https://r2c-tracker.com/ncssar/"))
        assertEquals("https://r2c-tracker.com/other", DroneOwnershipPolicy.scope(true, "https://r2c-tracker.com/other"))
        val first = DroneOwnershipPolicy.edit(initial, DroneOwnershipPolicy.STREAMING_DEVICE)
        val second = DroneOwnershipPolicy.Record.initial
        assertEquals(DroneOwnershipPolicy.STREAMING_DEVICE, first.mode)
        assertEquals(DroneOwnershipPolicy.ALL, second.mode)
    }

    @Test fun unknownModeDoesNotInventASetting() {
        assertEquals(DroneOwnershipPolicy.ALL, DroneOwnershipPolicy.normalize("nope"))
        assertEquals(DroneOwnershipPolicy.ALL, DroneOwnershipPolicy.normalize(null))
    }
}
