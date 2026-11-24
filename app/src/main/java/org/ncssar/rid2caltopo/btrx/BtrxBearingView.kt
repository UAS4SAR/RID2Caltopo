package org.ncssar.rid2caltopo.btrx

import kotlin.math.roundToInt

/**
 * Focus by class and/or maker (Nov 20 back-deck test: 40 devices around the takeoff spot).
 * [classMask]: bit (1 shl class) per bearing class (1 phone, 2 watch, 3 hearing aid, 4 tag,
 * 5 audio, 6 computer, 0 unknown); [companyId]: Bluetooth SIG company id, -1 = any;
 * [hideOthers]: the others are not drawn at all instead of dimmed. Same as the iPad.
 */
data class BtrxBearingFocusFilter(val classMask: Int = 0, val companyId: Int = -1, val hideOthers: Boolean = false) {
    val active: Boolean get() = classMask != 0 || companyId >= 0

    fun withClass(devClass: Int): BtrxBearingFocusFilter = copy(classMask = classMask xor (1 shl devClass))
    fun withCompany(id: Int): BtrxBearingFocusFilter = copy(companyId = if (companyId == id) -1 else id)

    fun logText(): String = if (!active) "none" else
        "classes 0x%x, company %s%s".format(java.util.Locale.US, classMask, if (companyId < 0) "any" else "0x%04x".format(java.util.Locale.US, companyId),
            if (hideOthers) ", others hidden" else "")

    companion object {
        val NONE = BtrxBearingFocusFilter()
    }
}

/**
 * Map display thresholds (Developer Tools > BTRX > Bearing map layer, Nov 20): estimates wider
 * than ±[maxPlusMinusM] (2 sigma; 0 = no limit) are not drawn, nor labels of sources with fewer
 * than [minReports] reports in the fade window; [launchAutoMute] mutes sources strong at the
 * takeoff spot before takeoff. The focused source always shows. Same as the iPad.
 */
data class BtrxBearingDisplay(
    val maxPlusMinusM: Int = DEFAULT_MAX_PLUS_MINUS_M,
    val minReports: Int = DEFAULT_MIN_REPORTS,
    val launchAutoMute: Boolean = true,
) {
    fun clamped() = copy(
        maxPlusMinusM = if (maxPlusMinusM <= 0) 0 else maxPlusMinusM.coerceIn(MIN_PLUS_MINUS_M, MAX_PLUS_MINUS_M),
        minReports = minReports.coerceIn(1, MAX_MIN_REPORTS),
    )

    companion object {
        const val DEFAULT_MAX_PLUS_MINUS_M = 50
        const val DEFAULT_MIN_REPORTS = 5
        const val MIN_PLUS_MINUS_M = 10
        /** The engine's own limit (est_max_axis_2s_m 100) is ±100 m; above that the slider means "no limit". */
        const val MAX_PLUS_MINUS_M = 100
        const val MAX_MIN_REPORTS = 30
    }
}

/** How the map draws one source (Android and iPad overlays). */
enum class BtrxBearingDrawMode { FULL, DIM, HIDDEN }

/** One entry of the focus menu: a class or a maker present on the map, with its count. */
data class BtrxBearingFocusOption(val isClass: Boolean, val value: Int, val label: String, val count: Int)

object BtrxBearingView {
    const val FOCUS_MENU = "Focus…"
    const val FOCUS_MENU_TITLE = "Focus on"
    const val FOCUS_CLASSES = "Kinds"
    const val FOCUS_MAKERS = "Makers"
    const val FOCUS_HIDE_OTHERS = "Hide the others"
    const val FOCUS_NONE_ON_MAP = "No sources on the map yet"
    const val LAUNCH_SHOW = "Show"
    const val LAUNCH_HIDE = "Hide"
    const val SETTING_MAX_PM = "Hide estimates wider than"
    const val SETTING_MIN_REPORTS = "Hide labels with fewer reports than"
    const val SETTING_LAUNCH_MUTE = "Mute sources at the takeoff spot"
    const val SETTING_LAUNCH_MUTE_HINT =
        "Devices heard -70 dBm or stronger while the drone sits at its first position (before takeoff) are muted. Show them from the map chip."

    fun maxPlusMinusText(m: Int): String = if (m <= 0) "no limit" else "±$m m"

    /** The "Hide estimates wider than" slider runs 10..110 in steps of 10; 110 = no limit (0). */
    fun maxPmFromSlider(v: Float): Int {
        val m = (v / 10f).roundToInt() * 10
        return if (m > BtrxBearingDisplay.MAX_PLUS_MINUS_M) 0 else m.coerceAtLeast(BtrxBearingDisplay.MIN_PLUS_MINUS_M)
    }

    fun sliderFromMaxPm(m: Int): Float = if (m <= 0) (BtrxBearingDisplay.MAX_PLUS_MINUS_M + 10).toFloat() else m.toFloat()

    /**
     * Hidden: muted, ignored, not heard for [BtrxStaleness.HIDE_S] (Nov 23), or outside a "hide
     * the others" focus; dimmed: outside any other focus.
     */
    fun drawMode(s: BtrxBearingSource, snap: BtrxBearingSnapshot): BtrxBearingDrawMode =
        if (BtrxStaleness.stale(s, snap.tUs)) BtrxBearingDrawMode.HIDDEN
        else drawMode(s, snap.focusKey, snap.focusActive, snap.focusFilter.hideOthers)

    fun drawMode(s: BtrxBearingSource, focusKey: Long, focusActive: Boolean, hideOthers: Boolean): BtrxBearingDrawMode = when {
        s.muted || s.ignored -> BtrxBearingDrawMode.HIDDEN
        !focusActive || s.focusMatch || (focusKey != 0L && s.key == focusKey) -> BtrxBearingDrawMode.FULL
        hideOthers -> BtrxBearingDrawMode.HIDDEN
        else -> BtrxBearingDrawMode.DIM
    }

    /** The estimate (dot, ellipses, pin) is drawn: not wider than the limit, unless this is the focused source. */
    fun estimateShown(s: BtrxBearingSource, d: BtrxBearingDisplay, focusKey: Long): Boolean {
        val e = s.estimate ?: return false
        if (s.key == focusKey && focusKey != 0L) return true
        return d.maxPlusMinusM <= 0 || e.plusMinus2sM <= d.maxPlusMinusM
    }

    /** The label is drawn: enough reports, or pinned, or the focused source. */
    fun labelShown(s: BtrxBearingSource, d: BtrxBearingDisplay, focusKey: Long): Boolean =
        (focusKey != 0L && s.key == focusKey) || s.pinned || s.reportsInWindow >= d.minReports

    /**
     * Label placement priority: focused, pinned, valid estimate, then more reports. Nov 23: a
     * new source ranks with the focus matches (first billing), a fading one last.
     */
    fun labelPriority(s: BtrxBearingSource, snap: BtrxBearingSnapshot): Int {
        val base = labelPriority(s, snap.focusKey, snap.focusActive)
        if (snap.focusKey != 0L && s.key == snap.focusKey) return base
        val reports = s.reportsInWindow.coerceIn(0, 99_999)
        return when (BtrxStaleness.tier(s, snap.tUs)) {
            BtrxStaleness.TIER_NEW -> maxOf(base, 3 * 100_000 + reports)
            BtrxStaleness.TIER_FADING, BtrxStaleness.TIER_STALE -> reports
            else -> base
        }
    }

    /** Map label with the "new" tag for a source first heard within [BtrxStaleness.NEW_S]. */
    fun mapLabel(s: BtrxBearingSource, nowUs: Long): String =
        (if (BtrxStaleness.isNew(s, nowUs)) "${BtrxStaleness.NEW_TAG} · " else "") + BtrxBearingText.label(s)

    fun labelPriority(s: BtrxBearingSource, focusKey: Long, focusActive: Boolean): Int {
        val tier = when {
            focusKey != 0L && s.key == focusKey -> 4
            focusActive && s.focusMatch -> 3
            s.pinned -> 2
            s.estimate != null -> 1
            else -> 0
        }
        return tier * 100_000 + s.reportsInWindow.coerceIn(0, 99_999)
    }

    /** Classes and makers among the sources not ignored, most first (ties by name): the focus menu. */
    fun focusOptions(sources: List<BtrxBearingSource>): List<BtrxBearingFocusOption> {
        val live = sources.filter { !it.ignored }
        val classes = live.groupingBy { it.devClass }.eachCount()
            .map { (c, n) -> BtrxBearingFocusOption(true, c, BtrxBearingText.className(c), n) }
            .sortedWith(compareByDescending<BtrxBearingFocusOption> { it.count }.thenBy { it.label })
        val makers = live.filter { it.companyId != 0 }.groupingBy { it.companyId }.eachCount()
            .map { (id, n) -> BtrxBearingFocusOption(false, id, BtrxMakers.maker(id) ?: "?", n) }
            .sortedWith(compareByDescending<BtrxBearingFocusOption> { it.count }.thenBy { it.label })
        return classes + makers
    }

    /** "watch, Garmin · others hidden" for the focus chip; null with no filter. */
    fun filterText(f: BtrxBearingFocusFilter): String? {
        if (!f.active) return null
        val parts = (0..6).filter { f.classMask and (1 shl it) != 0 }.map { BtrxBearingText.className(it) } +
            listOfNotNull(f.companyId.takeIf { it >= 0 }?.let { BtrxMakers.maker(it) })
        return parts.joinToString(", ") + if (f.hideOthers) " · others hidden" else ""
    }

    /** Launch chip: "3 muted at takeoff" (sources flagged at the launch point and still muted). */
    fun launchChipText(mutedCount: Int, flaggedCount: Int): String? = when {
        mutedCount > 0 -> "$mutedCount muted at takeoff"
        flaggedCount > 0 -> "$flaggedCount at takeoff shown"
        else -> null
    }

    fun minReportsText(n: Int): String = BtrxBearingText.reports(n)

    fun pm(m: Float): String = "±${m.roundToInt()} m"
}

/**
 * Greedy label placement on screen (Nov 20: labels piled on each other and on the drone
 * telemetry label near takeoff). Highest priority first; each label tries 8 spots around its
 * anchor and takes the first that overlaps no placed label and no obstacle (by [margin]);
 * none free: hidden. Pure geometry in pixels; the iPad has the same (BtrxLabelLayout.swift)
 * and both run test-fixtures/btrx/label-layout.tsv.
 */
object BtrxLabelLayout {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(o: Box, margin: Float): Boolean =
            left < o.right + margin && o.left < right + margin && top < o.bottom + margin && o.top < bottom + margin
    }

    data class Item(val id: Long, val anchorX: Float, val anchorY: Float, val width: Float, val height: Float, val priority: Int)

    data class Placed(val id: Long, val spot: Int, val box: Box)

    const val SPOTS = 8

    /** Spot [k] of [it]: 0 right-above (the old fixed place), 1 right-below, 2 left-above, 3 left-below, 4 above, 5 below, 6 right, 7 left. */
    fun spot(it: Item, k: Int, gap: Float): Box {
        val w = it.width; val h = it.height; val x = it.anchorX; val y = it.anchorY
        val (l, t) = when (k) {
            0 -> (x + gap) to (y - gap - h)
            1 -> (x + gap) to (y + gap)
            2 -> (x - gap - w) to (y - gap - h)
            3 -> (x - gap - w) to (y + gap)
            4 -> (x - w / 2) to (y - 2 * gap - h)
            5 -> (x - w / 2) to (y + 2 * gap)
            6 -> (x + gap) to (y - h / 2)
            else -> (x - gap - w) to (y - h / 2)
        }
        return Box(l, t, l + w, t + h)
    }

    /** Placed labels by id; ids missing from the map are hidden. */
    fun place(items: List<Item>, obstacles: List<Box>, gap: Float, margin: Float): Map<Long, Placed> {
        val order = items.sortedWith(compareByDescending<Item> { it.priority }.thenBy { it.id })
        val taken = ArrayList<Box>(items.size)
        val out = LinkedHashMap<Long, Placed>()
        for (it in order) {
            for (k in 0 until SPOTS) {
                val b = spot(it, k, gap)
                if (obstacles.any { o -> b.overlaps(o, margin) } || taken.any { o -> b.overlaps(o, margin) }) continue
                taken += b
                out[it.id] = Placed(it.id, k, b)
                break
            }
        }
        return out
    }
}

/**
 * Source staleness (Ken, Nov 23 Timberland flight: an iPhone and two "unknown" sources heard
 * a few times early stayed on the map all flight). Presentation only: a source not heard for
 * [FADE_START_S] fades, by [HIDE_S] it is off the map and out of the panel, and the engine
 * drops its focus (btrx_bearing.h stale_*; the evidence, the snapshot's source list, replay
 * and logs keep it). New sources (first heard within [NEW_S]) get a "new" tag and first place.
 * Same as the iPad; both run test-fixtures/btrx/staleness.tsv.
 */
object BtrxStaleness {
    /** Same as BTRX_BEARING_STALE_FADE_START_S / _HIDE_S in btrx_bearing.h (BtrxStalenessTest checks). */
    const val FADE_START_S = 25f
    const val HIDE_S = 60f
    const val NEW_S = 10f
    /** The open Nearby Sources panel re-sorts at most this often (rows update in place between). */
    const val PANEL_RESORT_S = 12f
    /** Panel score: RSSI less this much per second since last heard. */
    const val AGE_PENALTY_DB_PER_S = 0.5f
    const val NEW_TAG = "new"
    const val TIER_NEW = 0
    const val TIER_FRESH = 1
    const val TIER_FADING = 2
    const val TIER_STALE = 3

    fun ageS(nowUs: Long, thenUs: Long): Float = ((nowUs - thenUs).coerceAtLeast(0L)) / 1e6f

    /** 1 up to [FADE_START_S], then linear to 0 at [HIDE_S] (btrx_bearing_presence). */
    fun presence(ageS: Float): Float = when {
        !(ageS > FADE_START_S) -> 1f
        ageS >= HIDE_S -> 0f
        else -> (HIDE_S - ageS) / (HIDE_S - FADE_START_S)
    }

    fun presence(s: BtrxBearingSource, nowUs: Long): Float = presence(ageS(nowUs, s.lastHeardUs))

    fun tier(firstAgeS: Float, lastAgeS: Float): Int = when {
        lastAgeS >= HIDE_S -> TIER_STALE
        lastAgeS > FADE_START_S -> TIER_FADING
        firstAgeS <= NEW_S -> TIER_NEW
        else -> TIER_FRESH
    }

    fun tier(s: BtrxBearingSource, nowUs: Long): Int = tier(ageS(nowUs, s.firstHeardUs), ageS(nowUs, s.lastHeardUs))

    fun isNew(s: BtrxBearingSource, nowUs: Long): Boolean = tier(s, nowUs) == TIER_NEW

    fun stale(s: BtrxBearingSource, nowUs: Long): Boolean = tier(s, nowUs) == TIER_STALE

    /** Signal strength and freshness: the latest RSSI (else the peak; none = -127) less 0.5 dB per second unheard. */
    fun score(s: BtrxBearingSource, nowUs: Long): Float =
        (s.lastRssiDbm ?: s.maxRssiDbm ?: -127).toFloat() - AGE_PENALTY_DB_PER_S * minOf(ageS(nowUs, s.lastHeardUs), HIDE_S)

    /** New, fresh, fading, stale; within each the higher score; then the key. */
    fun rank(sources: List<BtrxBearingSource>, nowUs: Long): List<BtrxBearingSource> =
        sources.map { Triple(it, tier(it, nowUs), score(it, nowUs)) }
            .sortedWith(compareBy<Triple<BtrxBearingSource, Int, Float>> { it.second }
                .thenByDescending { it.third }
                .thenComparator { a, b -> java.lang.Long.compareUnsigned(a.first.key, b.first.key) })
            .map { it.first }
}

/**
 * Row order of the open Nearby Sources panel (Ken, Nov 23): ranked by [BtrxStaleness.rank], but
 * re-sorted at most every [BtrxStaleness.PANEL_RESORT_S] and never while a finger is on the list,
 * so a row does not move under a tap. Between re-sorts rows keep their places (values update in
 * place), newcomers join at the end, evicted sources leave. Stale sources are left out at a
 * re-sort unless [showOlder]; changing [showOlder] re-sorts at once. Same as the iPad.
 */
class BtrxPanelOrder {
    private var keys: List<Long> = emptyList()
    private var sortedAtUs: Long? = null
    private var older: Boolean = false

    fun arrange(sources: List<BtrxBearingSource>, nowUs: Long, holding: Boolean, showOlder: Boolean): List<BtrxBearingSource> {
        val byKey = sources.associateBy { it.key }
        val at = sortedAtUs
        val due = at == null || showOlder != older || nowUs < at ||
            nowUs - at >= (BtrxStaleness.PANEL_RESORT_S * 1e6f).toLong()
        fun shown(s: BtrxBearingSource) = showOlder || !BtrxStaleness.stale(s, nowUs)
        if (due && (!holding || at == null)) {
            keys = BtrxStaleness.rank(sources.filter(::shown), nowUs).map { it.key }
            sortedAtUs = nowUs
            older = showOlder
        } else {
            val kept = keys.filter { it in byKey }
            val keptSet = kept.toHashSet()
            keys = kept + BtrxStaleness.rank(sources.filter { it.key !in keptSet && shown(it) }, nowUs).map { it.key }
        }
        return keys.mapNotNull { byKey[it] }
    }

    /** Sources left out as stale (the "Show N older" button). */
    fun olderCount(sources: List<BtrxBearingSource>, nowUs: Long): Int = sources.count { BtrxStaleness.stale(it, nowUs) }
}

/**
 * Ken, Nov 23: "Show bearing layer on map" was off by default; it goes on whenever BTRX is
 * enabled (a Desk Pod link on at launch, or turned on), unless it already is. Turning BTRX off
 * leaves it as it is. Same as the iPad.
 */
object BtrxBearingAutoOn {
    fun shouldTurnOn(btrxEnabled: Boolean, bearingOn: Boolean): Boolean = btrxEnabled && !bearingOn
}
