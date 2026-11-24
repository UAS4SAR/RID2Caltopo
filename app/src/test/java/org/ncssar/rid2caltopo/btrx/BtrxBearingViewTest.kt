package org.ncssar.rid2caltopo.btrx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nov 20 back-deck test: focus by class or maker, display thresholds, label layout, maker in
 * labels, the "computer" class. Same cases as the iPad's BtrxBearingViewTests; the label
 * layout runs the shared test-fixtures/btrx/label-layout.tsv.
 */
class BtrxBearingViewTest {
    private fun src(
        key: Long, devClass: Int = 2, companyId: Int = 0, flags: Int = 0, reports: Int = 25,
        pm: Float? = 18f, pinned: Boolean = false, focusMatch: Boolean = false,
    ) = BtrxBearingSource(
        index = 0, key = key, shortId = "Q3x9Kd", provisional = false, devClass = devClass, rgb = 0x2196F3, flags = flags,
        firstHeardUs = 0L, lastHeardUs = 5_000_000L, reportsIn = 40, reportsUsed = 30, reportsInWindow = reports, peakAlpha = 0.6f,
        estimate = pm?.let { BtrxBearingEstimate(39.0, -121.0, it / 2f, it / 4f, 30f, false, 0.0, 0.0, 0f) },
        latestRay = null, pinned = pinned, companyId = companyId, focusMatch = focusMatch,
    )

    @Test
    fun sharedLabelLayoutFixtureMatchesIos() {
        val fixture = sequenceOf(File("test-fixtures/btrx/label-layout.tsv"), File("../test-fixtures/btrx/label-layout.tsv")).first { it.isFile }
        val rows = fixture.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        val cases = rows.groupBy { it[0] }
        assertEquals(8, cases.size)
        for ((name, rs) in cases) {
            val obstacles = rs.filter { it[1] == "obstacle" }.map { BtrxLabelLayout.Box(it[3].toFloat(), it[4].toFloat(), it[5].toFloat(), it[6].toFloat()) }
            val items = rs.filter { it[1] == "item" }.map {
                BtrxLabelLayout.Item(it[2].toLong(), it[3].toFloat(), it[4].toFloat(), it[5].toFloat(), it[6].toFloat(), it[7].toInt())
            }
            val placed = BtrxLabelLayout.place(items, obstacles, 8f, 2f)
            for (r in rs.filter { it[1] == "item" }) {
                assertEquals("$name item ${r[2]}", r[8].toInt(), placed[r[2].toLong()]?.spot ?: -1)
            }
            // Nothing placed overlaps anything else placed or an obstacle.
            val boxes = placed.values.map { it.box }
            for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) assertFalse(name, boxes[i].overlaps(boxes[j], 2f))
                for (o in obstacles) assertFalse(name, boxes[i].overlaps(o, 2f))
            }
        }
    }

    @Test
    fun drawModeFollowsFocus() {
        val watch = src(1, focusMatch = true)
        val phone = src(2, devClass = 1)
        val muted = src(3, flags = BtrxBearingNative.SRC_MUTED)
        val launch = src(4, flags = BtrxBearingNative.SRC_LAUNCH)
        assertEquals(BtrxBearingDrawMode.FULL, BtrxBearingView.drawMode(phone, 0L, false, false))
        assertEquals(BtrxBearingDrawMode.HIDDEN, BtrxBearingView.drawMode(muted, 0L, false, false))
        assertEquals(BtrxBearingDrawMode.FULL, BtrxBearingView.drawMode(launch, 0L, false, false))
        assertEquals(BtrxBearingDrawMode.FULL, BtrxBearingView.drawMode(watch, 0L, true, false))
        assertEquals(BtrxBearingDrawMode.DIM, BtrxBearingView.drawMode(phone, 0L, true, false))
        assertEquals(BtrxBearingDrawMode.HIDDEN, BtrxBearingView.drawMode(phone, 0L, true, true))
        assertEquals(BtrxBearingDrawMode.FULL, BtrxBearingView.drawMode(phone, 2L, true, true)) // the focused source itself
    }

    @Test
    fun thresholdsHideWideEstimatesAndThinLabels() {
        val d = BtrxBearingDisplay()
        assertEquals(50, d.maxPlusMinusM); assertEquals(5, d.minReports); assertTrue(d.launchAutoMute)
        assertTrue(BtrxBearingView.estimateShown(src(1, pm = 50f), d, 0L))
        assertFalse(BtrxBearingView.estimateShown(src(1, pm = 51f), d, 0L))
        assertTrue(BtrxBearingView.estimateShown(src(1, pm = 90f), d, 1L))                  // focused: always
        assertTrue(BtrxBearingView.estimateShown(src(1, pm = 90f), d.copy(maxPlusMinusM = 0), 0L)) // no limit
        assertFalse(BtrxBearingView.estimateShown(src(1, pm = null), d, 0L))
        assertTrue(BtrxBearingView.labelShown(src(1, reports = 5), d, 0L))
        assertFalse(BtrxBearingView.labelShown(src(1, reports = 4), d, 0L))
        assertTrue(BtrxBearingView.labelShown(src(1, reports = 1, pinned = true), d, 0L))
        assertTrue(BtrxBearingView.labelShown(src(1, reports = 1), d, 1L))
        assertEquals(BtrxBearingDisplay(0, 1, false), BtrxBearingDisplay(-3, 0, false).clamped())
        assertEquals(BtrxBearingDisplay(100, 30), BtrxBearingDisplay(400, 99).clamped())
        assertEquals(BtrxBearingDisplay(10, 5), BtrxBearingDisplay(4, 5).clamped())
        assertEquals(0, BtrxBearingView.maxPmFromSlider(110f))
        assertEquals(50, BtrxBearingView.maxPmFromSlider(52f))
        assertEquals(110f, BtrxBearingView.sliderFromMaxPm(0))
        assertEquals("no limit", BtrxBearingView.maxPlusMinusText(0))
        assertEquals("±50 m", BtrxBearingView.maxPlusMinusText(50))
    }

    @Test
    fun labelPriorityOrdersFocusPinnedEstimateReports() {
        val focused = BtrxBearingView.labelPriority(src(1, reports = 3), 1L, true)
        val match = BtrxBearingView.labelPriority(src(2, reports = 3, focusMatch = true), 1L, true)
        val pinned = BtrxBearingView.labelPriority(src(3, reports = 3, pinned = true), 1L, true)
        val est = BtrxBearingView.labelPriority(src(4, reports = 90), 1L, true)
        val none = BtrxBearingView.labelPriority(src(5, reports = 900, pm = null), 1L, true)
        assertTrue(focused > match && match > pinned && pinned > est && est > none)
        assertTrue(BtrxBearingView.labelPriority(src(6, reports = 91), 0L, false) > est)
    }

    @Test
    fun focusOptionsFilterTextAndLaunchChip() {
        val srcs = listOf(
            src(1, devClass = 2, companyId = 0x0087), src(2, devClass = 1, companyId = 0x004C), src(3, devClass = 1, companyId = 0x004C),
            src(4, devClass = 6, companyId = 0x0006), src(5, devClass = 1, flags = BtrxBearingNative.SRC_IGNORED),
        )
        assertEquals(listOf(
            BtrxBearingFocusOption(true, 1, "phone", 2), BtrxBearingFocusOption(true, 6, "computer", 1), BtrxBearingFocusOption(true, 2, "watch", 1),
            BtrxBearingFocusOption(false, 0x004C, "Apple", 2), BtrxBearingFocusOption(false, 0x0087, "Garmin", 1),
            BtrxBearingFocusOption(false, 0x0006, "Microsoft", 1),
        ), BtrxBearingView.focusOptions(srcs))
        val f = BtrxBearingFocusFilter.NONE.withClass(2).withCompany(0x0087)
        assertTrue(f.active)
        assertEquals(1 shl 2, f.classMask)
        assertEquals("watch, Garmin", BtrxBearingView.filterText(f))
        assertEquals("watch, Garmin · others hidden", BtrxBearingView.filterText(f.copy(hideOthers = true)))
        assertEquals(BtrxBearingFocusFilter(0, -1), f.withClass(2).withCompany(0x0087))
        assertNull(BtrxBearingView.filterText(BtrxBearingFocusFilter.NONE))
        assertEquals("classes 0x4, company 0x0087, others hidden", f.copy(hideOthers = true).logText())
        assertEquals("3 muted at takeoff", BtrxBearingView.launchChipText(3, 5))
        assertEquals("5 at takeoff shown", BtrxBearingView.launchChipText(0, 5))
        assertNull(BtrxBearingView.launchChipText(0, 0))
    }

    @Test
    fun labelsNameTheMakerAndComputerClass() {
        assertEquals("watch · Garmin · Q3x9Kd · 25 rpt · ±18 m", BtrxBearingText.label(src(1, companyId = 0x0087)))
        assertEquals("computer · Microsoft · Q3x9Kd · 25 rpt · ±18 m", BtrxBearingText.label(src(1, devClass = 6, companyId = 0x0006)))
        assertEquals("watch · Q3x9Kd · 25 rpt · ±18 m", BtrxBearingText.label(src(1)))
        assertEquals("computer", BtrxBearingText.className(6))
        val launch = src(1, companyId = 0x0087, flags = BtrxBearingNative.SRC_LAUNCH or BtrxBearingNative.SRC_MUTED)
        val lines = BtrxBearingText.inspectLines(launch, 5_000_000L)
        assertEquals("Source Q3x9Kd · watch · Garmin", lines[0])
        assertEquals("Heard strongly near the takeoff spot (muted)", lines[1])
        assertTrue(launch.launch)
    }

    @Test
    fun popupDismissWordingMatchesTheIpad() {
        // Nov 23 watch test: × at the top right of the map popups hides them; it is not "Close".
        assertEquals("Dismiss", BtrxBearingText.DISMISS)
        assertEquals("Done", BtrxBearingText.DONE)
        assertEquals("Close", BtrxBearingText.CLOSE)
    }
}
