package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Phase-weighted mapping progress. Weights come from measured per-phase cost
 * (ms and photo count) with photo-scaled defaults. Never maps optimizer cost into a fraction.
 *
 * ETA uses remaining weighted work / EMA of weighted progress per second — never
 * elapsed/overall alone (that grows when a long stage sits flat).
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

    /** Late tiles dominate wall time (A5 Pro: early ~2 s, late ~45 s). Count alone runs ahead. */
    const val TILE_LOCAL_EXPONENT = 3.5

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

    /**
     * Full-run calibration (A5 Pro, 134 photos, 195 tiles, ~6490 s wall):
     * early ~167 s; colors ~180 s (~1.35 s/photo); seams ~3527 s (~26.3 s/photo);
     * tiles ~2500 s (~12.8 s/tile); zoom ~12 s; package ~102 s.
     * Per-photo stages ∝ N; tile stage ∝ tile count (or estimate from N); fixed stages constant.
     */
    fun estimateTileCount(photoCount: Int): Int =
        max(1, (photoCount.coerceAtLeast(1) * 3 + 1) / 2) // ≈ 1.5 × N

    fun defaultCostsMs(photoCount: Int, engine: String, tileCount: Int = estimateTileCount(photoCount)): Map<String, Double> {
        val n = photoCount.coerceAtLeast(1).toDouble()
        val tiles = tileCount.coerceAtLeast(1).toDouble()
        val reconstruct = if (engine == "rtk-surface") 1170.0 * n else 900.0 * n
        return mapOf(
            CHECK_ORIGINALS to 80.0 * n,
            RECONSTRUCT to reconstruct,
            PREPARE_COLORS to 1350.0 * n,
            MATCH_SEAMS to 26_300.0 * n,
            PROJECT_TILES to 12_800.0 * tiles,
            ZOOM_LEVELS to 12_000.0,
            PACKAGE to 102_000.0,
            PUBLISH to 8_000.0,
        )
    }

    /**
     * Intra-phase fraction for tile projection. Prefers work units (pixels/area) when known;
     * otherwise applies a back-loaded curve so early cheap tiles do not run ahead of wall time.
     */
    fun tileLocalFraction(
        unitIndex: Int,
        unitTotal: Int,
        workDone: Double? = null,
        workTotal: Double? = null,
    ): Double {
        if (workDone != null && workTotal != null && workTotal > 0.0) {
            return (workDone / workTotal).coerceIn(0.0, 1.0)
        }
        if (unitTotal <= 0) return 0.0
        val f = (unitIndex.toDouble() / unitTotal.toDouble()).coerceIn(0.0, 1.0)
        return f.pow(TILE_LOCAL_EXPONENT).coerceIn(0.0, 1.0)
    }

    fun weightsFromCosts(costs: Map<String, Double>): List<Phase> {
        val ordered = ids.map { it to max(1.0, costs[it] ?: 1.0) }
        val sum = ordered.sumOf { it.second }
        return ordered.map { (id, cost) -> Phase(id, cost / sum, titles.getValue(id)) }
    }

    fun phases(
        engine: String,
        photoCount: Int = 50,
        measuredMsPerPhoto: Map<String, Double> = emptyMap(),
        tileCount: Int? = null,
    ): List<Phase> {
        val tiles = tileCount ?: estimateTileCount(photoCount)
        val defaults = defaultCostsMs(photoCount, engine, tiles).toMutableMap()
        val n = photoCount.coerceAtLeast(1).toDouble()
        for ((id, msPerPhoto) in measuredMsPerPhoto) {
            if (id !in defaults) continue
            val measured = msPerPhoto * n
            // Blend prior measurement with defaults so a single short run cannot zero a phase.
            defaults[id] = 0.65 * measured + 0.35 * defaults.getValue(id)
        }
        return weightsFromCosts(defaults)
    }

    fun tracker(
        engine: String,
        photoCount: Int = 50,
        measuredMsPerPhoto: Map<String, Double> = emptyMap(),
        tileCount: Int? = null,
    ): Tracker = Tracker(phases(engine, photoCount, measuredMsPerPhoto, tileCount), photoCount)

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
            runCatching {
                val prior = load(filesDir).toMutableMap()
                val sample = elapsedMs.toDouble() / photoCount.toDouble()
                val blended = prior[phaseId]?.let { 0.7 * sample + 0.3 * it } ?: sample
                prior[phaseId] = blended
                write(filesDir, prior)
            }.onFailure {
                android.util.Log.w("MappingProgress", "phase-costs record failed: ${it.message}")
            }
        }

        fun write(filesDir: File, msPerPhoto: Map<String, Double>) {
            runCatching {
                val destination = file(filesDir)
                destination.parentFile?.mkdirs()
                val body = JSONObject().put("schema", SCHEMA).put("msPerPhoto", JSONObject().also { out ->
                    msPerPhoto.forEach { (k, v) -> out.put(k, v) }
                })
                val temporary = File(destination.parentFile, "${destination.name}.tmp")
                temporary.writeText(body.toString())
                if (!(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination)))) {
                    android.util.Log.w("MappingProgress", "phase-costs write rename failed")
                }
            }.onFailure {
                android.util.Log.w("MappingProgress", "phase-costs write failed: ${it.message}")
            }
        }
    }

    /**
     * EMA of weighted progress per second. Half-life ~45 s so a long flat stage
     * decays the rate toward zero instead of inventing an ever-growing ETA.
     */
    class RateEma(private val halfLifeSeconds: Double = 45.0) {
        private var rate: Double? = null
        private var lastOverall: Double? = null
        private var lastNanos: Long? = null
        private var samples = 0

        fun observe(overall: Double, nowNanos: Long = System.nanoTime()): Double? {
            val prevO = lastOverall
            val prevT = lastNanos
            lastOverall = overall
            lastNanos = nowNanos
            if (prevO == null || prevT == null) return rate
            val dt = (nowNanos - prevT) / 1_000_000_000.0
            if (dt < 0.5) return rate
            val instant = ((overall - prevO) / dt).coerceAtLeast(0.0)
            val alpha = 1.0 - exp(-dt * ln(2.0) / halfLifeSeconds)
            rate = rate?.let { alpha * instant + (1.0 - alpha) * it } ?: instant
            samples++
            return rate
        }

        /** Null until the rate is stable enough to be useful. */
        fun etaSeconds(overall: Double): Long? {
            val r = rate ?: return null
            if (samples < 3 || overall < 0.05) return null
            if (overall >= 0.99) return 0L
            if (r < 1e-5) return null
            return ((1.0 - overall) / r).toLong().coerceIn(0L, 24L * 3600L)
        }

        fun currentRate(): Double? = rate
        fun sampleCount(): Int = samples
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
        val rate = RateEma()
        private var lastOverallForFlat = 0.0
        private var flatSinceNanos: Long? = null

        fun weight(phase: String): Double = phases.firstOrNull { it.id == phase }?.weight ?: 0.0

        fun band(phase: String): ClosedFloatingPointRange<Double> {
            val p = phases.firstOrNull { it.id == phase } ?: return 0.0..0.0
            val start = starts[phase] ?: 0.0
            return start..(start + p.weight)
        }

        /** null local = indeterminate; overall stays at band.start unless time hint provided. */
        fun overall(phase: String, local: Double?, timeHint: Double? = null): Double {
            val range = band(phase)
            val safeLocal = local?.takeIf { it.isFinite() }
            val safeHint = timeHint?.takeIf { it.isFinite() }
            val raw = when {
                safeLocal != null -> range.start + min(1.0, max(0.0, safeLocal)) * (range.endInclusive - range.start)
                safeHint != null -> range.start + min(0.85, max(0.0, safeHint)) * (range.endInclusive - range.start)
                else -> range.start
            }
            val value = max(floor, raw)
            if (value.isFinite()) {
                floor = value
                return value
            }
            return if (floor.isFinite()) floor else 0.0
        }

        fun overall(
            phase: String,
            unitIndex: Int,
            unitTotal: Int,
            workDone: Double? = null,
            workTotal: Double? = null,
        ): Double {
            if (unitTotal <= 0) return overall(phase, null)
            val index = unitIndex.coerceIn(0, unitTotal)
            val local = if (phase == PROJECT_TILES) {
                tileLocalFraction(index, unitTotal, workDone, workTotal)
            } else {
                index.toDouble() / unitTotal
            }
            return overall(phase, local)
        }

        fun stage(phase: String, unitIndex: Int? = null, unitTotal: Int? = null): String {
            val title = phases.firstOrNull { it.id == phase }?.title ?: phase
            return if (unitIndex != null && unitTotal != null && unitTotal > 0)
                "$title · $unitIndex/$unitTotal" else title
        }

        fun title(phase: String): String = phases.firstOrNull { it.id == phase }?.title ?: phase

        /** Observe progress for EMA ETA. Returns remaining seconds or null ("estimating…"). */
        fun etaSeconds(overall: Double, nowNanos: Long = System.nanoTime()): Long? {
            rate.observe(overall, nowNanos)
            return rate.etaSeconds(overall)
        }

        /** True when overall has not advanced for [flatMs] — used by tests and status writers. */
        fun isFlat(overall: Double, nowNanos: Long = System.nanoTime(), flatMs: Long = 60_000L): Boolean {
            if (kotlin.math.abs(overall - lastOverallForFlat) > 1e-4) {
                lastOverallForFlat = overall
                flatSinceNanos = nowNanos
                return false
            }
            val since = flatSinceNanos ?: nowNanos.also { flatSinceNanos = it }
            return (nowNanos - since) / 1_000_000L >= flatMs
        }

        fun estimatedPhaseMs(phase: String, totalJobMsHint: Double? = null): Double {
            val w = weight(phase)
            val total = totalJobMsHint ?: defaultCostsMs(photoCount, "rtk-surface").values.sum()
            return w * total
        }
    }
}
