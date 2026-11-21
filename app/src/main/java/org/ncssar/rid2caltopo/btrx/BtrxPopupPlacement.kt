package org.ncssar.rid2caltopo.btrx

import kotlin.math.max
import kotlin.math.min

/**
 * Where the map's BTRX popups go (Ken, Nov 20 A5 test: "BTRX sources here" opened at a tap near
 * the right edge ran off the screen and under the stream PiP). The popup stays inside the map,
 * clear of [obstacle] (the stream PiP) by [margin]: first the asked-for spot as is, then flipped
 * left, up, or both, then any of those slid back on screen, then beside or above the obstacle
 * with the height capped so it scrolls. Pixels, map coordinates. Same rules as the iPad's
 * BtrxPopupPlacement.swift; both run test-fixtures/btrx/popup-placement.tsv.
 */
object BtrxPopupPlacement {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** [choice]: 0-3 a candidate as is (0 asked for, 1 left, 2 up, 3 left and up), 4-7 the same slid on screen,
     *  8 left of the obstacle, 9 above it (height capped), 10 nothing clear (asked-for spot slid on screen). */
    data class Plan(val x: Float, val y: Float, val maxHeight: Float, val clear: Boolean, val choice: Int)

    /** The smallest height a popup is squeezed to above the obstacle before overlap is accepted. */
    const val MIN_BAND = 96f

    /** The tap panel: its top-left corner at the tap point, flipping left or up around it. */
    fun atPoint(width: Float, height: Float, popupW: Float, popupH: Float, x: Float, y: Float, obstacle: Box?, margin: Float): Plan {
        val w = min(popupW, max(1f, width - 2 * margin))
        val h = min(popupH, max(1f, height - 2 * margin))
        return place(width, height, w, h, listOf(x to y, x - w to y, x to y - h, x - w to y - h), obstacle, margin)
    }

    /** The inspect card: [left] from the map's left edge and [bottom] up from its bottom edge. */
    fun atBottomStart(width: Float, height: Float, popupW: Float, popupH: Float, left: Float, bottom: Float, obstacle: Box?, margin: Float): Plan {
        val w = min(popupW, max(1f, width - 2 * margin))
        val h = min(popupH, max(1f, height - 2 * margin))
        return place(width, height, w, h, listOf(left to height - bottom - h), obstacle, margin)
    }

    private fun place(width: Float, height: Float, w: Float, h: Float, cands: List<Pair<Float, Float>>, obstacle: Box?, m: Float): Plan {
        val cap = max(1f, height - 2 * m)
        fun fits(x: Float, y: Float) = x >= m - 0.01f && y >= m - 0.01f && x + w <= width - m + 0.01f && y + h <= height - m + 0.01f
        fun clear(x: Float, y: Float, hh: Float = h) = obstacle == null || !overlaps(Box(x, y, x + w, y + hh), obstacle, m)
        fun cx(x: Float) = x.coerceIn(m, max(m, width - m - w))
        fun cy(y: Float) = y.coerceIn(m, max(m, height - m - h))
        cands.forEachIndexed { i, (x, y) -> if (fits(x, y) && clear(x, y)) return Plan(x, y, cap, true, i) }
        cands.forEachIndexed { i, (x, y) -> if (clear(cx(x), cy(y))) return Plan(cx(x), cy(y), cap, true, 4 + i) }
        val (x0, y0) = cands[0]
        if (obstacle != null) {
            val lx = obstacle.left - m - w
            if (lx >= m && clear(lx, cy(y0))) return Plan(lx, cy(y0), cap, true, 8)
            val band = obstacle.top - 2 * m
            if (band >= min(h, MIN_BAND)) {
                val hh = min(h, band)
                val y = obstacle.top - m - hh
                if (clear(cx(x0), y, hh)) return Plan(cx(x0), y, hh, true, 9)
            }
        }
        return Plan(cx(x0), cy(y0), cap, false, 10)
    }

    fun overlaps(a: Box, b: Box, margin: Float): Boolean =
        a.left < b.right + margin && a.right > b.left - margin && a.top < b.bottom + margin && a.bottom > b.top - margin
}
