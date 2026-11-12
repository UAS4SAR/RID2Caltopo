package org.ncssar.rid2caltopo.btrx

import kotlin.math.abs
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * The pilot's steering overlay (btrx design doc section 41): one source, one circle
 * and vector in its color, one hint word. Pure Kotlin, fed live STEER frames from the
 * paired Drone Pod by [BtrxDeskPodModel]; the iPad has the same logic in Swift
 * (R2CCore BtrxSteering.swift). Keep them matching. Not thread-safe: the model's lock
 * guards it. Nothing here ever makes a clue.
 *
 * Hint (section 41.2):
 * - LEFT / RIGHT from delta = port - starboard mean RSSI, roll-corrected as in section
 *   24.2 (delta + [ROLL_DB_PER_DEG] x roll, roll starboard-down positive, only with the
 *   IMU-ok flag). LEFT at delta >= +3 dB, RIGHT at <= -3 dB, back to STRAIGHT only at
 *   |delta| < 2 dB. Any change of the word needs two reports in a row, or one report
 *   with |delta| >= 6 dB. Heard on one patch only: that side at once.
 * - CLOSER / FARTHER while STRAIGHT, from the least-squares slope of peak_rssi over the
 *   last 3-5 reports: CLOSER at >= +[ALONG_ENTER_DB] dB per report, FARTHER at <= -that,
 *   back to STRAIGHT when the slope falls under [ALONG_EXIT_DB]. FARTHER means the
 *   source is behind: the vector points back ("turn around").
 * - LEVEL while the pod's attitude gate flag is set: the vector turns gray and the last
 *   hint holds underneath.
 * - LOST when no STEER with a target for [LOST_MS]; the circle fades. A no-target
 *   STEER before that keeps the last hint, faded.
 * The vector has five fixed directions plus "behind"; hard left/right at |delta| >= 8 dB.
 * A new src_id, or the same one after LOST, starts the hint afresh.
 */
class BtrxSteering {
    enum class Hint(val word: String) {
        LEFT("LEFT"), RIGHT("RIGHT"), STRAIGHT("STRAIGHT"), CLOSER("CLOSER"), FARTHER("FARTHER"), LEVEL("LEVEL"), LOST("LOST")
    }

    enum class Side { LEFT, STRAIGHT, RIGHT }
    enum class Along { NONE, CLOSER, FARTHER }

    /** Screen direction of the vector: degrees clockwise from straight up. */
    enum class Vector(val degrees: Int) { HARD_LEFT(-90), LEFT(-45), AHEAD(0), RIGHT(45), HARD_RIGHT(90), BEHIND(180) }

    data class View(
        val srcId: Int,
        /** Index into [PALETTE]; null when no source has a color (src_id 0). */
        val colorIndex: Int?,
        val hint: Hint,
        /** Null when LOST: no vector, only the faded circle. */
        val vector: Vector?,
        /** LEVEL: draw the vector gray. */
        val gray: Boolean,
        /** No target right now (LOST, or a no-target STEER within [LOST_MS]): draw faded. */
        val faded: Boolean,
        val devClass: Int,
        /** Bit 5 of the latest STEER: at least one source is muted on the pod. */
        val muted: Boolean,
        val steerSeq: Int,
        /** Time since the latest STEER arrived. */
        val ageMs: Long,
        /** Mutable target: a source to mute (not LOST, src_id non-zero). */
        val canMute: Boolean,
    )

    private val colors = LinkedHashMap<Int, Int>()
    private var nextColor = 0
    private var src = 0
    private var side: Side? = null
    private var pendingSide: Side? = null
    private var pendingCount = 0
    private var hard = false
    private var along = Along.NONE
    private val peaks = ArrayDeque<Int>()
    private var last: P.Steer? = null
    private var lastSteerMs: Long? = null
    private var lastTargetMs: Long? = null
    private var targetDevClass = P.DEV_UNKNOWN

    /** The current target's src_id, or 0. */
    val currentSrc: Int get() = src

    /** Forget everything, colors included (a different pod was paired or the pod was forgotten). */
    fun reset() {
        colors.clear(); nextColor = 0
        src = 0; clearHint()
        last = null; lastSteerMs = null; lastTargetMs = null
    }

    /** Color for a src_id: each new id takes the next palette entry, cycling after 8. */
    fun colorFor(srcId: Int): Int? {
        if (srcId == 0) return null
        colors[srcId]?.let { return it }
        val c = nextColor
        nextColor = (nextColor + 1) % PALETTE.size
        colors[srcId] = c
        if (colors.size > MAX_REMEMBERED) colors.remove(colors.keys.first())
        return c
    }

    fun onSteer(s: P.Steer, nowMs: Long) {
        val previousTargetMs = lastTargetMs
        last = s
        lastSteerMs = nowMs
        if (!s.target) return
        lastTargetMs = nowMs
        val wasLost = previousTargetMs == null || nowMs - previousTargetMs >= LOST_MS
        if (s.srcId != src || wasLost) {
            src = s.srcId
            clearHint()
        }
        colorFor(src)
        targetDevClass = s.devClass
        if (s.gated) return // hold the last hint (LEVEL)
        updateSide(s)
        updateAlong(s)
    }

    /** The overlay to draw, or null when there is nothing to show (no STEER for [SHOW_MS]). */
    fun view(nowMs: Long): View? {
        val seen = lastSteerMs ?: return null
        if (nowMs - seen > SHOW_MS) return null
        return lastView(nowMs)
    }

    /** The latest STEER as a view whatever its age (bench line on the Desk Pod screen). */
    fun lastView(nowMs: Long): View? {
        val s = last ?: return null
        val seen = lastSteerMs ?: return null
        val lost = lastTargetMs.let { it == null || nowMs - it >= LOST_MS }
        val hint = when {
            lost -> Hint.LOST
            s.gated -> Hint.LEVEL
            else -> heldHint()
        }
        val vector = if (lost) null else when (side) {
            Side.LEFT -> if (hard) Vector.HARD_LEFT else Vector.LEFT
            Side.RIGHT -> if (hard) Vector.HARD_RIGHT else Vector.RIGHT
            else -> if (along == Along.FARTHER) Vector.BEHIND else Vector.AHEAD
        }
        return View(
            srcId = src, colorIndex = colors[src], hint = hint, vector = vector, gray = hint == Hint.LEVEL,
            faded = lost || !s.target, devClass = targetDevClass,
            muted = s.muted, steerSeq = s.steerSeq, ageMs = nowMs - seen, canMute = !lost && src != 0,
        )
    }

    private fun heldHint(): Hint = when (side) {
        Side.LEFT -> Hint.LEFT
        Side.RIGHT -> Hint.RIGHT
        else -> when (along) {
            Along.CLOSER -> Hint.CLOSER
            Along.FARTHER -> Hint.FARTHER
            Along.NONE -> Hint.STRAIGHT
        }
    }

    private fun clearHint() {
        side = null; pendingSide = null; pendingCount = 0; hard = false
        along = Along.NONE; peaks.clear()
    }

    private fun updateSide(s: P.Steer) {
        val oneSide: Side? = when {
            s.portHeard && s.stbdHeard -> null
            s.portHeard -> Side.LEFT
            s.stbdHeard -> Side.RIGHT
            else -> return
        }
        val delta = if (oneSide != null) 0.0 else deltaDb(s)
        val candidate = oneSide ?: when {
            delta >= SIDE_ENTER_DB -> Side.LEFT
            delta <= -SIDE_ENTER_DB -> Side.RIGHT
            abs(delta) < SIDE_EXIT_DB -> Side.STRAIGHT
            else -> side ?: Side.STRAIGHT
        }
        val strong = oneSide != null || abs(delta) >= SIDE_STRONG_DB
        val current = side
        when {
            current == null || candidate == current -> { side = candidate; pendingSide = null; pendingCount = 0 }
            strong -> { side = candidate; pendingSide = null; pendingCount = 0 }
            pendingSide == candidate -> {
                pendingCount++
                if (pendingCount >= SIDE_CONFIRM_REPORTS) { side = candidate; pendingSide = null; pendingCount = 0 }
            }
            else -> { pendingSide = candidate; pendingCount = 1 }
        }
        hard = side != Side.STRAIGHT && side == candidate && (oneSide != null || abs(delta) >= HARD_DB)
    }

    private fun updateAlong(s: P.Steer) {
        if (side != Side.STRAIGHT) {
            along = Along.NONE
            peaks.clear()
            return
        }
        peaks.addLast(s.peakRssi)
        while (peaks.size > ALONG_WINDOW) peaks.removeFirst()
        if (peaks.size < ALONG_MIN_REPORTS) {
            along = Along.NONE
            return
        }
        val slope = slope(peaks)
        along = when (along) {
            Along.NONE -> enterAlong(slope)
            Along.CLOSER -> if (slope < ALONG_EXIT_DB) enterAlong(slope) else Along.CLOSER
            Along.FARTHER -> if (slope > -ALONG_EXIT_DB) enterAlong(slope) else Along.FARTHER
        }
    }

    private fun enterAlong(slope: Double): Along = when {
        slope >= ALONG_ENTER_DB -> Along.CLOSER
        slope <= -ALONG_ENTER_DB -> Along.FARTHER
        else -> Along.NONE
    }

    companion object {
        /** Okabe-Ito with white in place of black (section 41.3), drawn with a thin dark outline. */
        val PALETTE: List<Int> = listOf(0xE69F00, 0x56B4E9, 0x009E73, 0xF0E442, 0x0072B2, 0xD55E00, 0xCC79A7, 0xFFFFFF)
        /** LEVEL and hold; never a source color. */
        const val GRAY = 0x9E9E9E
        const val OUTLINE = 0x202020

        const val SIDE_ENTER_DB = 3.0
        const val SIDE_EXIT_DB = 2.0
        const val SIDE_STRONG_DB = 6.0
        const val HARD_DB = 8.0
        const val SIDE_CONFIRM_REPORTS = 2
        /** Section 24.2: S ~ 0.12 dB per degree of roll [EST]. */
        const val ROLL_DB_PER_DEG = 0.12
        const val ALONG_WINDOW = 5
        const val ALONG_MIN_REPORTS = 3
        /** dB per report (about 1 s); section 24.2 expects 0.4-0.65 dB/s at 100 m and 5 m/s [EST]. */
        const val ALONG_ENTER_DB = 0.5
        const val ALONG_EXIT_DB = 0.25
        const val LOST_MS = 5_000L
        /** No STEER at all for this long hides the overlay (a no-target STEER comes at most every 5 s). */
        const val SHOW_MS = 12_000L
        const val MAX_REMEMBERED = 64

        /** Port minus starboard, roll-corrected when the pod's IMU is OK. */
        fun deltaDb(s: P.Steer): Double {
            val raw = (s.rssiPort - s.rssiStbd).toDouble()
            return if (s.imuOk) raw + ROLL_DB_PER_DEG * (s.rollX2 / 2.0) else raw
        }

        /** Least-squares slope of evenly spaced values, per step. */
        fun slope(values: Collection<Int>): Double {
            val n = values.size
            if (n < 2) return 0.0
            val meanX = (n - 1) / 2.0
            val meanY = values.average()
            var num = 0.0
            var den = 0.0
            values.forEachIndexed { i, y -> num += (i - meanX) * (y - meanY); den += (i - meanX) * (i - meanX) }
            return num / den
        }
    }
}
