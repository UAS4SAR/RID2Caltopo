package org.ncssar.rid2caltopo.mapping

import kotlin.math.max
import kotlin.math.min

/** Phase-weighted mapping progress. Never maps optimizer cost into a fraction. */
internal object MappingProgressPhases {
    const val CHECK_ORIGINALS = "check-originals"
    const val RECONSTRUCT = "reconstruct"
    const val PREPARE_COLORS = "prepare-colors"
    const val MATCH_SEAMS = "match-seams"
    const val PROJECT_TILES = "project-tiles"
    const val ZOOM_LEVELS = "zoom-levels"
    const val PACKAGE = "package"
    const val PUBLISH = "publish"

    data class Phase(val id: String, val weight: Double, val title: String)

    private val flat = listOf(
        Phase(CHECK_ORIGINALS, 0.04, "Checking originals"),
        Phase(RECONSTRUCT, 0.28, "Reconstructing geometry"),
        Phase(PREPARE_COLORS, 0.04, "Preparing colors"),
        Phase(MATCH_SEAMS, 0.20, "Matching seams"),
        Phase(PROJECT_TILES, 0.24, "Projecting tiles"),
        Phase(ZOOM_LEVELS, 0.08, "Building zoom levels"),
        Phase(PACKAGE, 0.08, "Packaging"),
        Phase(PUBLISH, 0.04, "Publishing"),
    )

    private val rtk = listOf(
        Phase(CHECK_ORIGINALS, 0.04, "Checking originals"),
        Phase(RECONSTRUCT, 0.28, "Reconstructing geometry"),
        Phase(PREPARE_COLORS, 0.04, "Preparing colors"),
        Phase(MATCH_SEAMS, 0.20, "Matching seams"),
        Phase(PROJECT_TILES, 0.24, "Projecting tiles"),
        Phase(ZOOM_LEVELS, 0.08, "Building zoom levels"),
        Phase(PACKAGE, 0.08, "Packaging"),
        Phase(PUBLISH, 0.04, "Publishing"),
    )

    fun phases(engine: String): List<Phase> = if (engine == "rtk-surface") rtk else flat

    fun tracker(engine: String): Tracker = Tracker(phases(engine))

    class Tracker(private val phases: List<Phase>) {
        init {
            require(phases.isNotEmpty())
            require(kotlin.math.abs(phases.sumOf { it.weight } - 1.0) < 1e-9) { "Phase weights must sum to 1.0" }
        }

        private val starts: Map<String, Double> = buildMap {
            var start = 0.0
            for (phase in phases) {
                put(phase.id, start)
                start += phase.weight
            }
        }

        fun band(phase: String): ClosedFloatingPointRange<Double> {
            val p = phases.first { it.id == phase }
            val start = starts.getValue(phase)
            return start..(start + p.weight)
        }

        /** null local = indeterminate; overall stays at band.start. */
        fun overall(phase: String, local: Double?): Double {
            val range = band(phase)
            if (local == null) return range.start
            val clamped = min(1.0, max(0.0, local))
            return range.start + clamped * (range.endInclusive - range.start)
        }

        fun overall(phase: String, unitIndex: Int, unitTotal: Int): Double {
            require(unitTotal > 0 && unitIndex in 0..unitTotal)
            return overall(phase, unitIndex.toDouble() / unitTotal)
        }

        fun stage(phase: String, unitIndex: Int? = null, unitTotal: Int? = null): String {
            val title = phases.first { it.id == phase }.title
            return if (unitIndex != null && unitTotal != null && unitTotal > 0)
                "$title · $unitIndex/$unitTotal" else title
        }

        fun title(phase: String): String = phases.first { it.id == phase }.title
    }
}
