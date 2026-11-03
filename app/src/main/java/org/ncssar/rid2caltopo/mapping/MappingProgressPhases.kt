package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Phase-weighted mapping progress. Weights come from measured per-phase cost
 * (ms and photo count) with photo-scaled defaults. Never maps optimizer cost into a fraction.
 */
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

    private val ids = listOf(
        CHECK_ORIGINALS, RECONSTRUCT, PREPARE_COLORS, MATCH_SEAMS,
        PROJECT_TILES, ZOOM_LEVELS, PACKAGE, PUBLISH,
    )

    private val titles = mapOf(
        CHECK_ORIGINALS to "Checking originals",
        RECONSTRUCT to "Reconstructing geometry",
        PREPARE_COLORS to "Preparing colors",
        MATCH_SEAMS to "Matching seams",
        PROJECT_TILES to "Projecting tiles",
        ZOOM_LEVELS to "Building zoom levels",
        PACKAGE to "Packaging",
        PUBLISH to "Publishing",
    )

    /** Default ms cost model: dominant work scales with photo count. */
    fun defaultCostsMs(photoCount: Int, engine: String): Map<String, Double> {
        val n = photoCount.coerceAtLeast(1).toDouble()
        val reconstruct = if (engine == "rtk-surface") 3500.0 * n else 900.0 * n
        return mapOf(
            CHECK_ORIGINALS to 80.0 * n,
            RECONSTRUCT to reconstruct,
            PREPARE_COLORS to 120.0 * n,
            MATCH_SEAMS to 400.0 * n,
            PROJECT_TILES to 600.0 * n,
            ZOOM_LEVELS to 12_000.0 + 20.0 * n,
            PACKAGE to 25_000.0 + 40.0 * n,
            PUBLISH to 8_000.0,
        )
    }

    fun weightsFromCosts(costs: Map<String, Double>): List<Phase> {
        val ordered = ids.map { it to max(1.0, costs[it] ?: 1.0) }
        val sum = ordered.sumOf { it.second }
        return ordered.map { (id, cost) -> Phase(id, cost / sum, titles.getValue(id)) }
    }

    fun phases(engine: String, photoCount: Int = 50, measuredMsPerPhoto: Map<String, Double> = emptyMap()): List<Phase> {
        val defaults = defaultCostsMs(photoCount, engine).toMutableMap()
        val n = photoCount.coerceAtLeast(1).toDouble()
        for ((id, msPerPhoto) in measuredMsPerPhoto) {
            if (id !in defaults) continue
            val measured = msPerPhoto * n
            // Blend prior measurement with defaults so a single short run cannot zero a phase.
            defaults[id] = 0.65 * measured + 0.35 * defaults.getValue(id)
        }
        return weightsFromCosts(defaults)
    }

    fun tracker(engine: String, photoCount: Int = 50, measuredMsPerPhoto: Map<String, Double> = emptyMap()): Tracker =
        Tracker(phases(engine, photoCount, measuredMsPerPhoto), photoCount)

    /** Compact persistence of ms-per-photo by phase id. */
    object Costs {
        const val SCHEMA = 1
        fun file(filesDir: File) = File(filesDir, "mapping-storage-stats/phase-costs.json")

        fun load(filesDir: File): Map<String, Double> {
            val text = runCatching { file(filesDir).readText() }.getOrNull() ?: return emptyMap()
            return parse(text)
        }

        fun parse(text: String): Map<String, Double> = runCatching {
            val value = JSONObject(text)
            if (value.optInt("schema") != SCHEMA) return emptyMap()
            val phases = value.optJSONObject("msPerPhoto") ?: return emptyMap()
            phases.keys().asSequence().mapNotNull { key ->
                val v = phases.optDouble(key, Double.NaN)
                if (v.isFinite() && v > 0) key to v else null
            }.toMap()
        }.getOrDefault(emptyMap())

        fun record(filesDir: File, phaseId: String, elapsedMs: Long, photoCount: Int) {
            if (elapsedMs < 1 || photoCount < 1 || phaseId.isBlank()) return
            val prior = load(filesDir).toMutableMap()
            val sample = elapsedMs.toDouble() / photoCount.toDouble()
            val blended = prior[phaseId]?.let { 0.7 * sample + 0.3 * it } ?: sample
            prior[phaseId] = blended
            write(filesDir, prior)
        }

        fun write(filesDir: File, msPerPhoto: Map<String, Double>) {
            val destination = file(filesDir)
            destination.parentFile?.mkdirs()
            val body = JSONObject().put("schema", SCHEMA).put("msPerPhoto", JSONObject().also { out ->
                msPerPhoto.forEach { (k, v) -> out.put(k, v) }
            })
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.writeText(body.toString())
            check(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
                "Could not save phase costs."
            }
        }
    }

    class Tracker(val phases: List<Phase>, private val photoCount: Int) {
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
        private var floor = 0.0

        fun band(phase: String): ClosedFloatingPointRange<Double> {
            val p = phases.first { it.id == phase }
            val start = starts.getValue(phase)
            return start..(start + p.weight)
        }

        /** null local = indeterminate; overall stays at band.start unless time hint provided. */
        fun overall(phase: String, local: Double?, timeHint: Double? = null): Double {
            val range = band(phase)
            val raw = when {
                local != null -> range.start + min(1.0, max(0.0, local)) * (range.endInclusive - range.start)
                timeHint != null -> range.start + min(0.95, max(0.0, timeHint)) * (range.endInclusive - range.start)
                else -> range.start
            }
            val value = max(floor, raw)
            floor = value
            return value
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

        /** ETA once enough overall progress has accumulated to be stable. */
        fun etaSeconds(overall: Double, elapsedSeconds: Long): Long? {
            if (elapsedSeconds < 15 || overall < 0.08) return null
            if (overall >= 0.99) return 0L
            val remaining = elapsedSeconds.toDouble() * (1.0 - overall) / overall
            return remaining.toLong().coerceAtLeast(0L)
        }

        fun estimatedPhaseMs(phase: String, totalJobMsHint: Double? = null): Double {
            val weight = phases.first { it.id == phase }.weight
            val total = totalJobMsHint ?: defaultCostsMs(photoCount, "rtk-surface").values.sum()
            return weight * total
        }
    }
}
