package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MappingProgressPhasesTest {
    @Test fun overallIsMonotonicAcrossPhases() {
        val tracker = MappingProgressPhases.tracker("flat")
        val samples = mutableListOf<Double>()
        for (phase in listOf(
            MappingProgressPhases.CHECK_ORIGINALS,
            MappingProgressPhases.RECONSTRUCT,
            MappingProgressPhases.PREPARE_COLORS,
            MappingProgressPhases.MATCH_SEAMS,
            MappingProgressPhases.PROJECT_TILES,
            MappingProgressPhases.ZOOM_LEVELS,
            MappingProgressPhases.PACKAGE,
            MappingProgressPhases.PUBLISH,
        )) {
            samples += tracker.overall(phase, 0.0)
            samples += tracker.overall(phase, 1.0)
        }
        for (i in 1 until samples.size) assertTrue(samples[i] + 1e-9 >= samples[i - 1])
        assertEquals(0.0, samples.first(), 1e-9)
        assertEquals(1.0, samples.last(), 1e-9)
    }

    @Test fun unitStepsStayUnderThirtyPointsWithinKnownWorkPhase() {
        val tracker = MappingProgressPhases.tracker("rtk-surface")
        val total = 20
        var previous = tracker.overall(MappingProgressPhases.PROJECT_TILES, 0, total)
        for (i in 1..total) {
            val next = tracker.overall(MappingProgressPhases.PROJECT_TILES, i, total)
            assertTrue(next >= previous)
            assertTrue("jump=${(next - previous) * 100}", (next - previous) * 100 <= 30.0 + 1e-9)
            previous = next
        }
    }

    @Test fun indeterminateStaysAtBandStart() {
        val tracker = MappingProgressPhases.tracker("flat")
        val band = tracker.band(MappingProgressPhases.MATCH_SEAMS)
        val value = tracker.overall(MappingProgressPhases.MATCH_SEAMS, null)
        assertEquals(band.start, value, 1e-9)
        assertTrue(value < band.endInclusive)
        // Optimizer cost must never be interpreted as local progress.
        assertEquals(band.start, tracker.overall(MappingProgressPhases.MATCH_SEAMS, null), 1e-9)
        assertTrue(abs(tracker.overall(MappingProgressPhases.MATCH_SEAMS, 0.85) - band.start) > 0.01)
    }

    @Test fun stageNamesPhaseAndCounts() {
        val tracker = MappingProgressPhases.tracker("flat")
        assertEquals("Checking originals · 3/10", tracker.stage(MappingProgressPhases.CHECK_ORIGINALS, 3, 10))
        assertEquals("Publishing", tracker.stage(MappingProgressPhases.PUBLISH))
    }
}
