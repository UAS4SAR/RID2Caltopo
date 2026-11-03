package org.ncssar.rid2caltopo.mapping

import java.io.File
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

    @Test fun calibratedDefaultsMatchFullRunMagnitudes() {
        val n = 134
        val tiles = 195
        val costs = MappingProgressPhases.defaultCostsMs(n, "rtk-surface", tiles)
        assertEquals(26_300.0 * n, costs.getValue(MappingProgressPhases.MATCH_SEAMS), 1.0)
        assertEquals(12_800.0 * tiles, costs.getValue(MappingProgressPhases.PROJECT_TILES), 1.0)
        assertEquals(1_350.0 * n, costs.getValue(MappingProgressPhases.PREPARE_COLORS), 1.0)
        val sum = costs.values.sum()
        assertEquals(6_490_000.0, sum, 50_000.0)
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

    @Test fun tileLocalPrefersWorkUnitsOverUniformCount() {
        val byCount = MappingProgressPhases.tileLocalFraction(100, 195)
        val byWork = MappingProgressPhases.tileLocalFraction(100, 195, workDone = 200.0, workTotal = 2500.0)
        assertTrue("count=$byCount", byCount < 0.20)
        assertEquals(0.08, byWork, 1e-9)
        assertTrue(byWork < byCount || abs(byWork - 200.0 / 2500.0) < 1e-9)
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

    @Test fun nonFiniteLocalAndTimeHintTreatedAsNull() {
        val tracker = MappingProgressPhases.tracker("flat", photoCount = 10)
        val band = tracker.band(MappingProgressPhases.MATCH_SEAMS)
        assertEquals(band.start, tracker.overall(MappingProgressPhases.MATCH_SEAMS, Double.NaN), 1e-9)
        assertEquals(band.start, tracker.overall(MappingProgressPhases.MATCH_SEAMS, Double.POSITIVE_INFINITY), 1e-9)
        assertEquals(band.start, tracker.overall(MappingProgressPhases.MATCH_SEAMS, null, timeHint = Double.NaN), 1e-9)
        val advanced = tracker.overall(MappingProgressPhases.MATCH_SEAMS, 0.5)
        assertTrue(advanced.isFinite() && advanced > band.start)
    }

    @Test fun unitTotalNonPositiveSkippedAndIndexClamped() {
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = 20)
        val start = tracker.band(MappingProgressPhases.PROJECT_TILES).start
        assertEquals(start, tracker.overall(MappingProgressPhases.PROJECT_TILES, 5, 0), 1e-9)
        assertEquals(start, tracker.overall(MappingProgressPhases.PROJECT_TILES, -3, 10), 1e-9)
        val atEnd = tracker.overall(MappingProgressPhases.PROJECT_TILES, 99, 10)
        assertEquals(tracker.band(MappingProgressPhases.PROJECT_TILES).endInclusive, atEnd, 1e-9)
    }

    @Test fun unknownPhaseFallsBackSafely() {
        val tracker = MappingProgressPhases.tracker("flat", photoCount = 5)
        assertEquals(0.0, tracker.weight("no-such-phase"), 0.0)
        assertEquals("no-such-phase", tracker.title("no-such-phase"))
        assertEquals(0.0..0.0, tracker.band("no-such-phase"))
    }

    @Test fun costsWriteFailureDoesNotThrow() {
        val blocked = File(folder.root, "mapping-storage-stats").apply { writeText("not-a-dir") }
        MappingProgressPhases.Costs.record(folder.root, MappingProgressPhases.RECONSTRUCT, 1000, 10)
        MappingProgressPhases.Costs.write(folder.root, mapOf(MappingProgressPhases.RECONSTRUCT to 1.0))
        assertTrue(blocked.isFile)
    }

    /**
     * Replay the measured A5 Pro full run (134 photos, 195 tiles, ~6490 s) and assert
     * phase-boundary progress stays within ±10 pp of real elapsed share, with mid-tile ETA
     * within 2× of actual remaining.
     */
    @Test fun calibratedFullRunReplayMatchesWallShareAndEta() {
        val n = 134
        val tiles = 195
        val totalSec = 6490.0
        val earlySec = 167.0
        val colorsSec = 180.0
        val seamsSec = 3527.0
        val tilesSec = 2500.0
        val zoomSec = 12.0
        val packageSec = 102.0
        val tracker = MappingProgressPhases.tracker("rtk-surface", photoCount = n, tileCount = tiles)
        val t0 = 1_000_000_000_000L
        fun nanosAt(elapsedSec: Double) = t0 + (elapsedSec * 1_000_000_000.0).toLong()

        tracker.overall(MappingProgressPhases.CHECK_ORIGINALS, 1.0)
        tracker.etaSeconds(tracker.band(MappingProgressPhases.CHECK_ORIGINALS).endInclusive, nanosAt(10.0))
        val afterEarly = tracker.overall(MappingProgressPhases.RECONSTRUCT, 1.0)
        tracker.etaSeconds(afterEarly, nanosAt(earlySec))
        assertTrue("afterEarly=$afterEarly", afterEarly <= 0.15)

        val afterColors = tracker.overall(MappingProgressPhases.PREPARE_COLORS, 1.0)
        val colorsShare = (earlySec + colorsSec) / totalSec
        tracker.etaSeconds(afterColors, nanosAt(earlySec + colorsSec))
        assertTrue("afterColors=$afterColors", afterColors <= 0.15)
        assertTrue(
            "afterColors=$afterColors real=$colorsShare",
            abs(afterColors - colorsShare) <= 0.10,
        )

        // Seams report a real intra-phase fraction.
        var overall = afterColors
        val seamsStartElapsed = earlySec + colorsSec
        for (step in 1..20) {
            val local = step / 20.0
            overall = tracker.overall(MappingProgressPhases.MATCH_SEAMS, local)
            tracker.etaSeconds(overall, nanosAt(seamsStartElapsed + seamsSec * local))
        }
        val afterSeams = overall
        val seamsShare = (earlySec + colorsSec + seamsSec) / totalSec
        assertTrue("afterSeams=$afterSeams", afterSeams in 0.55..0.70)
        assertTrue(
            "afterSeams=$afterSeams real=$seamsShare",
            abs(afterSeams - seamsShare) <= 0.10,
        )

        // Non-uniform tile timings: 1–100 ~2 s, 101–157 ~10 s, 158–195 ~45 s.
        fun tileElapsed(completed: Int): Double {
            var sum = 0.0
            for (i in 1..completed) {
                sum += when {
                    i <= 100 -> 2.0
                    i <= 157 -> 10.0
                    else -> 45.0
                }
            }
            return sum
        }
        val tilesStartElapsed = seamsStartElapsed + seamsSec
        var midTileOverall = afterSeams
        var midTileEta: Long? = null
        var midTileRemaining: Double? = null
        for (i in 1..tiles) {
            overall = tracker.overall(MappingProgressPhases.PROJECT_TILES, i, tiles)
            val elapsed = tilesStartElapsed + tileElapsed(i)
            val eta = tracker.etaSeconds(overall, nanosAt(elapsed))
            // Mid-tile phase by wall time (~1250 s into tiles).
            if (midTileRemaining == null && tileElapsed(i) >= tilesSec / 2.0) {
                midTileOverall = overall
                midTileEta = eta
                midTileRemaining = totalSec - elapsed
            }
        }
        val afterTiles = overall
        val tilesShare = (earlySec + colorsSec + seamsSec + tilesSec) / totalSec
        assertTrue("afterTiles=$afterTiles", afterTiles >= 0.95)
        assertTrue(
            "afterTiles=$afterTiles real=$tilesShare",
            abs(afterTiles - tilesShare) <= 0.10,
        )

        assertNotNull("mid-tile ETA should be available", midTileEta)
        assertNotNull(midTileRemaining)
        val remaining = midTileRemaining!!
        val eta = midTileEta!!.toDouble()
        assertTrue(
            "midTile overall=$midTileOverall eta=$eta remaining=$remaining",
            eta in (remaining / 2.0)..(remaining * 2.0),
        )

        overall = tracker.overall(MappingProgressPhases.ZOOM_LEVELS, 1.0)
        tracker.etaSeconds(overall, nanosAt(tilesStartElapsed + tilesSec + zoomSec))
        overall = tracker.overall(MappingProgressPhases.PACKAGE, 1.0)
        tracker.etaSeconds(overall, nanosAt(totalSec - 5))
        val done = tracker.overall(MappingProgressPhases.PUBLISH, 1.0)
        assertEquals(1.0, done, 1e-9)
    }
}
