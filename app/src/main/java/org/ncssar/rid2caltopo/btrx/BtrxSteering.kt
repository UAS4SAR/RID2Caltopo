package org.ncssar.rid2caltopo.btrx

import kotlin.math.abs
import kotlin.math.sqrt
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * The pilot's steering overlay (btrx design doc section 41): one source, one circle
 * and vector in its color, one hint word. Pure Kotlin, fed live STEER frames from the
 * paired Drone Pod by [BtrxDeskPodModel]; the iPad has the same logic in Swift
 * (R2CCore BtrxSteering.swift). Keep them matching. Not thread-safe: the model's lock
 * guards it. Nothing here ever makes a clue.
 *
 * STEER facts (firmware/PROTOCOL.md, PR #18): one report per 1 s window. Bit 0 clear
 * means nothing was heard in that window (common for a weak or slow source): fade,
 * keep the last hint. The pod sends a no-target report once when the target goes,
 * then at most every 5 s. window_end_ms is pod A's clock (ms since power-up), the same
 * clock as DETECT. src_id restarts at 1 after a pod reboot, so window_end_ms going
 * back means the pod restarted: everything here is reset, colors included. Mutes live
 * only in the pod's RAM (max 8; cleared at power-up; a muted source unheard for 60 s
 * is forgotten and comes back unmuted under a new src_id); STEER bit 5 says at least
 * one is held, there is no count.
 *
 * Hint (section 41.2):
 * - LEFT / RIGHT from delta = port - starboard RSSI. LEFT at delta >= +3 dB, RIGHT at
 *   <= -3 dB, back to STRAIGHT only at |delta| < 2 dB. A side change needs two reports
 *   in a row, or one strong report (|delta| >= 6 dB). Heard on one patch only (the
 *   other is -128): that side; strong (immediate, hard) only with >= 2 hits on that
 *   patch, otherwise confirmed like any other change. Hard left/right at |delta| >= 8.
 * - Roll correction (section 24.2): delta + [ROLL_DB_PER_DEG] x roll, roll positive
 *   starboard side down, only with the IMU-ok flag. Off ([ROLL_CORRECTION_DEFAULT])
 *   until the bench roll-sign check (docs/btrx/BLE-bench-test.md section 6) has passed;
 *   the Desk Pod screen has a debug switch.
 * - CLOSER / FARTHER while STRAIGHT: least-squares slope of peak_rssi against
 *   window_end_ms over the last [ALONG_WINDOW_MS], at least [ALONG_MIN_REPORTS] reports
 *   with a target and not gated. CLOSER at >= +[ALONG_ENTER_DB_S] dB/s and at least
 *   [ALONG_SIGMA] x its standard error, FARTHER likewise negative (vector points back:
 *   turn around); cleared below [ALONG_EXIT_DB_S] dB/s.
 * - LEVEL at once while the latest report is gated: vector gray, last hint held.
 * - Faded at once when the latest report has no target, or no STEER for [STALE_MS].
 * - LOST at once after [LOST_MS] without a target: no vector, circle faded.
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
        /** Not current: LOST, a no-target report, or no STEER for [STALE_MS]. Draw faded. */
        val faded: Boolean,
        val devClass: Int,
        /** Bit 5 of the latest STEER: at least one source is muted on the pod. */
        val muted: Boolean,
        val steerSeq: Int,
        /** Time since the latest STEER arrived. */
        val ageMs: Long,
        /** A source to mute (not LOST, src_id non-zero). */
        val canMute: Boolean,
    )

    /** Least-squares fit of peak RSSI against time: slope in dB/s and its standard error. */
    data class Fit(val slopeDbPerS: Double, val stdErr: Double, val n: Int)

    /** Roll correction on/off (debug switch; off until the bench roll check passes). */
    var rollCorrection = ROLL_CORRECTION_DEFAULT

    /** Pod restarts seen (window_end_ms went back). */
    var restarts = 0
        private set

    private val colors = LinkedHashMap<Int, Int>()
    private var nextColor = 0
    private var src = 0
    private var side: Side? = null
    private var pendingSide: Side? = null
    private var pendingCount = 0
    private var hard = false
    private var along = Along.NONE
    /** (window_end_ms, peak_rssi) of target, ungated reports while STRAIGHT. */
    private val peaks = ArrayDeque<Pair<Long, Int>>()
    private var last: P.Steer? = null
    private var lastSteerMs: Long? = null
    private var lastTargetMs: Long? = null
    private var targetDevClass = P.DEV_UNKNOWN

    /** The current target's src_id, or 0. */
    val currentSrc: Int get() = src

    /** Forget everything, colors included (pod restarted, a different pod was paired, or it was forgotten). */
    fun reset() {
        colors.clear(); nextColor = 0
        src = 0; clearHint()
        last = null; lastSteerMs = null; lastTargetMs = null
        targetDevClass = P.DEV_UNKNOWN
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

    /** Returns true when this report showed the pod restarted (state was reset first). */
    fun onSteer(s: P.Steer, nowMs: Long): Boolean {
        val restarted = last?.let { s.windowEndMs < it.windowEndMs } == true
        if (restarted) {
            reset()
            restarts++
        }
        val previousTargetMs = lastTargetMs
        last = s
        lastSteerMs = nowMs
        if (!s.target) return restarted
        lastTargetMs = nowMs
        val wasLost = previousTargetMs == null || nowMs - previousTargetMs >= LOST_MS
        if (s.srcId != src || wasLost) {
            src = s.srcId
            clearHint()
        }
        colorFor(src)
        targetDevClass = s.devClass
        if (s.gated) return restarted // hold the last hint (LEVEL); not in the fit
        updateSide(s)
        updateAlong(s)
        return restarted
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
        val stale = nowMs - seen >= STALE_MS
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
            faded = lost || !s.target || stale, devClass = targetDevClass,
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
        val delta = if (oneSide != null) 0.0 else deltaDb(s, rollCorrection)
        val candidate = oneSide ?: when {
            delta >= SIDE_ENTER_DB -> Side.LEFT
            delta <= -SIDE_ENTER_DB -> Side.RIGHT
            abs(delta) < SIDE_EXIT_DB -> Side.STRAIGHT
            else -> side ?: Side.STRAIGHT
        }
        val oneSideHits = when (oneSide) {
            Side.LEFT -> s.hitsPort
            Side.RIGHT -> s.hitsStbd
            else -> 0
        }
        val strong = if (oneSide != null) oneSideHits >= ONE_SIDE_MIN_HITS else abs(delta) >= SIDE_STRONG_DB
        val current = side
        when {
            current == null || candidate == current || strong -> { side = candidate; pendingSide = null; pendingCount = 0 }
            pendingSide == candidate -> {
                pendingCount++
                if (pendingCount >= SIDE_CONFIRM_REPORTS) { side = candidate; pendingSide = null; pendingCount = 0 }
            }
            else -> { pendingSide = candidate; pendingCount = 1 }
        }
        val hardNow = if (oneSide != null) strong else abs(delta) >= HARD_DB
        hard = side != Side.STRAIGHT && side == candidate && hardNow
    }

    private fun updateAlong(s: P.Steer) {
        if (side != Side.STRAIGHT) {
            along = Along.NONE
            peaks.clear()
            return
        }
        peaks.addLast(s.windowEndMs to s.peakRssi)
        while (peaks.isNotEmpty() && s.windowEndMs - peaks.first().first > ALONG_WINDOW_MS) peaks.removeFirst()
        val fit = fit(peaks)
        if (fit == null || fit.n < ALONG_MIN_REPORTS) {
            along = Along.NONE
            return
        }
        val m = fit.slopeDbPerS
        val significant = abs(m) >= ALONG_ENTER_DB_S && abs(m) >= ALONG_SIGMA * fit.stdErr
        along = when {
            significant -> if (m > 0) Along.CLOSER else Along.FARTHER
            abs(m) < ALONG_EXIT_DB_S -> Along.NONE
            along == Along.CLOSER && m > 0 -> Along.CLOSER
            along == Along.FARTHER && m < 0 -> Along.FARTHER
            else -> Along.NONE
        }
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
        /** A one-patch report is strong (immediate, hard) only with this many hits on that patch. */
        const val ONE_SIDE_MIN_HITS = 2
        /** Section 41.2: S ~ 0.2 dB per degree of roll for these patches [EST: tune in flight test 1]. */
        const val ROLL_DB_PER_DEG = 0.2
        /**
         * Roll correction stays off until the bench roll-sign check passes
         * (docs/btrx/BLE-bench-test.md section 6: level about 0, starboard side down
         * about 10 deg reads about +20 roll_x2; pod B needs firmware 4a68518 or later).
         */
        const val ROLL_CORRECTION_DEFAULT = false
        /** CLOSER/FARTHER fit window on the pod's clock (window_end_ms). */
        const val ALONG_WINDOW_MS = 10_000L
        const val ALONG_MIN_REPORTS = 6
        const val ALONG_ENTER_DB_S = 0.5
        const val ALONG_EXIT_DB_S = 0.25
        /** The slope must be at least this many standard errors from zero. */
        const val ALONG_SIGMA = 2.0
        const val LOST_MS = 5_000L
        /** No STEER for this long after a report: fade (reports come every second with a target). */
        const val STALE_MS = 2_000L
        /** No STEER at all for this long hides the overlay (a no-target STEER comes at most every 5 s). */
        const val SHOW_MS = 12_000L
        const val MAX_REMEMBERED = 64

        /** Port minus starboard, roll-corrected when [rollCorrection] is on and the pod's IMU is OK. */
        fun deltaDb(s: P.Steer, rollCorrection: Boolean = ROLL_CORRECTION_DEFAULT): Double {
            val raw = (s.rssiPort - s.rssiStbd).toDouble()
            return if (rollCorrection && s.imuOk) raw + ROLL_DB_PER_DEG * (s.rollX2 / 2.0) else raw
        }

        /** Least-squares slope of peak (dB) against window_end_ms, in dB/s, with its standard error. */
        fun fit(points: Collection<Pair<Long, Int>>): Fit? {
            val n = points.size
            if (n < 3) return null
            val t0 = points.first().first
            val xs = points.map { (it.first - t0) / 1000.0 }
            val ys = points.map { it.second.toDouble() }
            val mx = xs.average()
            val my = ys.average()
            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until n) { sxx += (xs[i] - mx) * (xs[i] - mx); sxy += (xs[i] - mx) * (ys[i] - my) }
            if (sxx <= 0.0) return null
            val slope = sxy / sxx
            var ss = 0.0
            for (i in 0 until n) { val r = ys[i] - (my + slope * (xs[i] - mx)); ss += r * r }
            return Fit(slope, sqrt(ss / (n - 2) / sxx), n)
        }
    }
}

/**
 * Which stream tile shows the steering overlay (section 41.2: only the drone carrying
 * the pod). Automatic when exactly one stream is live; with more, only the tile the
 * operator marked "Drone Pod is on this drone". The choice ends when that stream does.
 * Same as the iPad's BtrxSteeringTarget.
 */
object BtrxSteeringTarget {
    fun showsOverlay(tile: String, live: Set<String>, chosen: String?): Boolean =
        tile in live && (live.size == 1 || chosen == tile)

    /** Offer "Drone Pod is on this drone" on this tile: several live, none chosen. */
    fun offersPick(tile: String, live: Set<String>, chosen: String?): Boolean =
        tile in live && live.size > 1 && (chosen == null || chosen !in live)

    /** The choice still standing, or null once its stream is no longer live. */
    fun retained(chosen: String?, live: Set<String>): String? = chosen?.takeIf { it in live }
}
