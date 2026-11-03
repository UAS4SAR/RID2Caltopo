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

    @Test fun weightsScaleWithPhotoCount() {
        val small = MappingProgressPhases.phases("rtk-surface", photoCount = 20)
        val large = MappingProgressPhases.phases("rtk-surface", photoCount = 200)
        val smallPrepare = small.first { it.id == MappingProgressPhases.PREPARE_COLORS }.weight
        val largePrepare = large.first { it.id == MappingProgressPhases.PREPARE_COLORS }.weight
        val smallPublish = small.first { it.id == MappingProgressPhases.PUBLISH }.weight
        val largePublish = large.first { it.id == MappingProgressPhases.PUBLISH }.weight
        // Per-photo stages gain share as N grows; fixed publish shrinks.
        assertTrue(largePrepare > smallPrepare * 0.9)
        assertTrue(largePublish < smallPublish)
        val seams = large.first { it.id == MappingProgressPhases.MATCH_SEAMS }.weight
        assertTrue("seams=$seams", seams > 0.15)
    }

    @Test fun prepareColorsAtPartialCannotReachNextStageStart() {
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 134)
        val at54 = tracker.overall(MappingProgressPhases.PREPARE_COLORS, 54, 134)
        val seamsStart = tracker.band(MappingProgressPhases.MATCH_SEAMS).start
        assertTrue("at54=$at54 seamsStart=$seamsStart", at54 < seamsStart - 1e-9)
        assertTrue(at54 < 0.8)
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

    @Test fun stageNamesPhaseAndCounts() {
        val tracker = MappingProgressPhases.tracker("flat")
        assertEquals("Checking originals · 3/10", tracker.stage(MappingProgressPhases.CHECK_ORIGINALS, 3, 10))
        assertEquals("Publishing", tracker.stage(MappingProgressPhases.PUBLISH))
    }

    @Test fun etaDecreasesUnderConstantRate() {
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 50)
        val t0 = 1_000_000_000_000L
        // Simulate steady progress over ~60 s with 2 s steps.
        var etaPrev: Long? = null
        for (i in 0..40) {
            val overall = 0.1 + 0.02 * i
            val now = t0 + i * 2_000_000_000L
            val eta = tracker.etaSeconds(overall.coerceAtMost(0.9), now)
            if (eta != null && etaPrev != null) {
                assertTrue("eta rose $etaPrev -> $eta at i=$i", eta <= etaPrev + 2)
            }
            if (eta != null) etaPrev = eta
        }
        assertNotNull(etaPrev)
        assertTrue(etaPrev!! < 300)
    }

    @Test fun etaNullWhileEstimating() {
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 50)
        assertNull(tracker.etaSeconds(0.02, System.nanoTime()))
    }

    @Test fun flatStageDetection() {
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 50)
        val t0 = 1_000_000_000_000L
        assertFalse(tracker.isFlat(0.5, t0, flatMs = 60_000))
        assertFalse(tracker.isFlat(0.5, t0 + 30_000_000_000L, flatMs = 60_000))
        assertTrue(tracker.isFlat(0.5, t0 + 61_000_000_000L, flatMs = 60_000))
        assertFalse(tracker.isFlat(0.51, t0 + 62_000_000_000L, flatMs = 60_000))
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
