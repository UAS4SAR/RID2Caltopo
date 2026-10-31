package org.ncssar.rid2caltopo.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.app.FlightStorage

class ClueHydrationReconcileTest {
    @Test
    fun clueDeletedDuringHydrationIsNonFatalRace() {
        assertEquals(
            ClueHydrationReconcile.Verdict.RACE,
            ClueHydrationReconcile.verdict(
                stagedHash = "abc",
                expectedHash = "abc",
                archivePresent = false,
                archiveHash = null,
            ),
        )
        assertEquals(
            ClueHydrationReconcile.Verdict.RACE,
            ClueHydrationReconcile.verdict(
                stagedHash = null,
                expectedHash = "abc",
                archivePresent = true,
                archiveHash = "abc",
            ),
        )
    }

    @Test
    fun clueReplacedDuringHydrationReconcilesToCurrentArchive() {
        assertEquals(
            ClueHydrationReconcile.Verdict.RACE,
            ClueHydrationReconcile.verdict(
                stagedHash = "new-hash",
                expectedHash = "stale-hash",
                archivePresent = true,
                archiveHash = "new-hash",
            ),
        )
    }

    @Test
    fun unchangedClueStillMatches() {
        assertEquals(
            ClueHydrationReconcile.Verdict.MATCH,
            ClueHydrationReconcile.verdict(
                stagedHash = "same",
                expectedHash = "same",
                archivePresent = true,
                archiveHash = "same",
            ),
        )
    }

    @Test
    fun trueCorruptionStillMismatches() {
        assertEquals(
            ClueHydrationReconcile.Verdict.MISMATCH,
            ClueHydrationReconcile.verdict(
                stagedHash = "local-corrupt",
                expectedHash = "expected",
                archivePresent = true,
                archiveHash = "archive-other",
            ),
        )
    }

    @Test
    fun cluesHydrationRaceDoesNotSurfaceFlightStorageDialog() {
        assertTrue(
            FlightStorage.isTransientStorageCheckFailure(
                IllegalStateException("Archive hydration did not preserve clues; original copies were retained."),
            ),
        )
        assertTrue(
            OperationalStorage.isTransientPrepareFailure(
                IllegalStateException("Archive hydration did not preserve clues; original copies were retained."),
            ),
        )
        assertFalse(
            FlightStorage.isTransientStorageCheckFailure(
                IllegalStateException("Archive Dir is unavailable; reconnect its storage."),
            ),
        )
    }
}
