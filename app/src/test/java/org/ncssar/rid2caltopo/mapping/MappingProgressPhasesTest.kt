package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

class MappingProgressPhasesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun overallIsMonotonicAcrossPhases() {
        val tracker = MappingProgressPhases.tracker("flat", photoCount = 40)
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
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 20)
        val total = 20
        var previous = tracker.overall(MappingProgressPhases.PROJECT_TILES, 0, total)
        for (i in 1..total) {
            val next = tracker.overall(MappingProgressPhases.PROJECT_TILES, i, total)
            assertTrue(next >= previous)
            assertTrue("jump=${(next - previous) * 100}", (next - previous) * 100 <= 30.0 + 1e-9)
            previous = next
        }
    }

    @Test fun indeterminateStaysAtBandStartUntilTimeHint() {
        val tracker = MappingProgressPhases.tracker("flat", photoCount = 10)
        val band = tracker.band(MappingProgressPhases.MATCH_SEAMS)
        val value = tracker.overall(MappingProgressPhases.MATCH_SEAMS, null)
        assertEquals(band.start, value, 1e-9)
        assertTrue(value < band.endInclusive)
        assertTrue(abs(tracker.overall(MappingProgressPhases.MATCH_SEAMS, 0.85) - band.start) > 0.01)
    }

    @Test fun progressNeverGoesBackward() {
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 80)
        var previous = 0.0
        previous = tracker.overall(MappingProgressPhases.RECONSTRUCT, 0.5)
        val retreated = tracker.overall(MappingProgressPhases.RECONSTRUCT, 0.1)
        assertTrue(retreated + 1e-9 >= previous)
        val timeAdvanced = tracker.overall(MappingProgressPhases.RECONSTRUCT, null, timeHint = 0.9)
        assertTrue(timeAdvanced + 1e-9 >= retreated)
    }

    @Test fun rtkWeightsEmphasizeReconstructForLargePhotoCount() {
        val phases = MappingProgressPhases.phases("rtk-surface", photoCount = 200)
        val reconstruct = phases.first { it.id == MappingProgressPhases.RECONSTRUCT }.weight
        val packageWeight = phases.first { it.id == MappingProgressPhases.PACKAGE }.weight
        assertTrue("reconstruct=$reconstruct package=$packageWeight", reconstruct > packageWeight)
        assertTrue(reconstruct > 0.35)
    }

    @Test fun stageNamesPhaseAndCounts() {
        val tracker = MappingProgressPhases.tracker("flat")
        assertEquals("Checking originals · 3/10", tracker.stage(MappingProgressPhases.CHECK_ORIGINALS, 3, 10))
        assertEquals("Publishing", tracker.stage(MappingProgressPhases.PUBLISH))
    }

    @Test fun etaStableAfterEnoughProgress() {
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 50)
        assertNull(tracker.etaSeconds(0.05, 60))
        assertNull(tracker.etaSeconds(0.5, 10))
        val eta = tracker.etaSeconds(0.5, 120)
        assertNotNull(eta)
        assertTrue(eta!! in 100..140)
    }

    @Test fun phaseCostsRoundTripAndBlend() {
        val root = folder.root
        MappingProgressPhases.Costs.record(root, MappingProgressPhases.RECONSTRUCT, elapsedMs = 10_000, photoCount = 10)
        val loaded = MappingProgressPhases.Costs.load(root)
        assertEquals(1000.0, loaded.getValue(MappingProgressPhases.RECONSTRUCT), 1e-6)
        MappingProgressPhases.Costs.record(root, MappingProgressPhases.RECONSTRUCT, elapsedMs = 20_000, photoCount = 10)
        val blended = MappingProgressPhases.Costs.load(root).getValue(MappingProgressPhases.RECONSTRUCT)
        assertTrue(blended in 1300.0..1900.0)
    }
}
