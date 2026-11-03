package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

// Mirrors ignoredFlightCluesAskThenStayLocalAfterNo in apple/Tests/R2CCoreTests/ClueBindingTests.swift.
class IgnoredFlightClueTest {
    @Test fun ignoredFlightCluesAskThenStayLocalAfterNo() {
        assertEquals("Current flight ignored", IgnoredFlightClue.TITLE)
        assertEquals("Do you want to publish it?", IgnoredFlightClue.MESSAGE)
        assertEquals(IgnoredFlightClue.SubmitAction.UPLOAD, IgnoredFlightClue.submitAction(flightIgnored = false, keptIgnored = false))
        // Published since (via the Drone Confirmation Panel): uploads even after an earlier No.
        assertEquals(IgnoredFlightClue.SubmitAction.UPLOAD, IgnoredFlightClue.submitAction(flightIgnored = false, keptIgnored = true))
        assertEquals(IgnoredFlightClue.SubmitAction.ASK, IgnoredFlightClue.submitAction(flightIgnored = true, keptIgnored = false))
        // No on the panel keeps the clue locally with the Don't Publish flight (never discarded).
        assertEquals(IgnoredFlightClue.SubmitAction.LOCAL_ONLY, IgnoredFlightClue.submitAction(flightIgnored = true, keptIgnored = true))
        assertEquals(
            "Current flight ignored: Submit saves this clue on this device only (no CalTopo upload).",
            IgnoredFlightClue.LOCAL_ONLY_NOTE
        )
    }
}
