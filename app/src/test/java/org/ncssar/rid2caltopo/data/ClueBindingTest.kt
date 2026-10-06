package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClueBindingTest {
    private val t0 = 1_790_553_873_000L

    private fun point(offsetMs: Long, lat: Double = 39.0 + offsetMs / 1_000.0 * 0.0001, received: Long? = null) =
        ClueBindingPoint(t0 + offsetMs, received ?: (t0 + offsetMs + 350), lat, -121.0, 100.0, "rid", true)

    private fun bind(points: List<ClueBindingPoint>, captureMs: Long = t0, receivedAt: Long = captureMs + 400,
                     frame: ClueBindingFramePosition? = null) =
        ClueBinder.bind("RID-1", "flight-1", captureMs, "stream-pts", receivedAt, points, frame, false)

    @Test fun qualityThresholds() {
        assertEquals(ClueBindingQuality.EXACT, ClueBinder.quality(0, false))
        assertEquals(ClueBindingQuality.EXACT, ClueBinder.quality(-2_000, false))
        assertEquals(ClueBindingQuality.APPROXIMATE, ClueBinder.quality(2_001, false))
        assertEquals(ClueBindingQuality.APPROXIMATE, ClueBinder.quality(-10_000, false))
        assertEquals(ClueBindingQuality.APPROXIMATE_WARNING, ClueBinder.quality(10_001, false))
        assertEquals(ClueBindingQuality.APPROXIMATE_WARNING, ClueBinder.quality(30_000, false))
        assertEquals(ClueBindingQuality.UNBOUND, ClueBinder.quality(30_001, false))
        assertEquals(ClueBindingQuality.UNBOUND, ClueBinder.quality(null, false))
        // Hovering is never flagged.
        assertEquals(ClueBindingQuality.EXACT, ClueBinder.quality(25_000, true))
    }

    @Test fun bindsNearestWaypointBeforeOrAfterWithSignedOffset() {
        val after = bind(listOf(point(-3_000), point(1_250)))
        assertEquals(1_250L, after.offsetMs)
        assertEquals(t0 + 1_250, after.waypoint!!.timeMs)
        val before = bind(listOf(point(-800), point(2_500)))
        assertEquals(-800L, before.offsetMs)
        // Ties go to the earlier waypoint.
        assertEquals(-1_000L, bind(listOf(point(1_000), point(-1_000))).offsetMs)
        assertEquals(ClueBindingQuality.EXACT, before.quality)
    }

    @Test fun signedOffsetKeepsMillisecondPrecision() {
        assertEquals("+1.234 s", ClueBindingText.offset(1_234))
        assertEquals("-0.250 s", ClueBindingText.offset(-250))
        assertEquals("+0.000 s", ClueBindingText.offset(0))
        assertEquals("-12.007 s", ClueBindingText.offset(-12_007))
        val binding = bind(listOf(point(-3_457)))
        assertEquals(-3_457L, binding.offsetMs)
        assertTrue(ClueBindingText.formSummary(binding).startsWith("-3.457 s · approximate"))
        val data = ClueBindingText.extendedData(binding).toMap()
        assertEquals("-3.457", data["r2c_binding_offset_s"])
        // JSON round trip keeps the sign and every millisecond.
        val decoded = ClueBinding.fromJson(JSONObject(binding.toJson().toString()))!!
        assertEquals(binding, decoded)
    }

    @Test fun bindingUsesCaptureTimeNeverSubmissionOrReceiveTime() {
        // Received 9 s after capture (and submitted later still): the waypoint near the capture wins.
        val binding = bind(listOf(point(0), point(9_000)), captureMs = t0, receivedAt = t0 + 9_000)
        assertEquals(0L, binding.offsetMs)
        assertEquals(t0 + 9_000, binding.captureReceivedAtMs)
    }

    @Test fun approximateBindingsAreFlaggedUnlessHovering() {
        val moving = bind(listOf(point(-6_000), point(6_000)))
        assertEquals(ClueBindingQuality.APPROXIMATE, moving.quality)
        assertTrue(moving.flagged)
        val hover = bind(listOf(point(-6_000, lat = 39.0), point(6_000, lat = 39.00001)))
        assertTrue(hover.hovering)
        assertEquals(ClueBindingQuality.EXACT, hover.quality)
        assertFalse(hover.flagged)
        assertEquals("exact (hovering)", ClueBindingText.qualityLabel(hover))
        // The frame's own position next to the waypoint also means hovering.
        val frame = bind(listOf(point(-12_000, lat = 39.2)), frame = ClueBindingFramePosition(39.2, -121.0))
        assertTrue(frame.hovering)
    }

    @Test fun noBindingBeyondThirtySeconds() {
        val binding = bind(listOf(point(-31_000), point(45_000)))
        assertNull(binding.waypoint)
        assertNull(binding.offsetMs)
        assertEquals(-31_000L, binding.nearestOffsetMs)
        assertNotNull(binding.nearest)
        assertEquals(ClueBindingQuality.UNBOUND, binding.quality)
        assertEquals("not bound (no waypoint within 30 s); nearest -31.000 s", ClueBindingText.formSummary(binding))
        assertTrue(ClueBindingText.descriptionLines(binding).contains("  Offset: none; nearest waypoint -31.000 s"))
    }

    @Test fun bindingUsesWaypointsAvailableNowWithoutWaitingForLaterFixes() {
        // Only a fix 4 s before the capture so far: bound to it, nothing provisional or held.
        val first = bind(listOf(point(-4_000)))
        assertEquals(-4_000L, first.offsetMs)
        assertEquals("-4.000 s · approximate", ClueBindingText.formSummary(first))
        assertFalse(ClueBindingText.descriptionLines(first).any { it.contains("provisional") })
        assertNull(ClueBindingText.extendedData(first).toMap()["r2c_binding_final"])
        assertFalse(first.toJson().has("final"))
        // While the form is open (and once more on Submit) a nearer fix re-binds.
        val next = ClueBinder.refresh(first, listOf(point(-4_000), point(1_000)))
        assertEquals(1_000L, next.offsetMs)
        assertEquals(first.flightStartMs, next.flightStartMs)
        // Without points (flight over) the stored binding is kept.
        assertEquals(first, ClueBinder.refresh(first, emptyList()))
        // Bindings saved by the previous build ("final"/"movedFrom" keys) still load.
        val legacy = JSONObject(first.toJson().toString()).put("final", false)
            .put("movedFrom", JSONObject().put("latitude", 39.1).put("longitude", -121.1))
        assertEquals(first, ClueBinding.fromJson(legacy))
    }

    @Test fun submitOriginShiftOnlyMovesWaypointProjectedClues() {
        val shown = bind(listOf(point(-4_000, lat = 39.0))).copy(originIsWaypoint = true)
        val submitted = ClueBinder.refresh(shown, listOf(point(-4_000, lat = 39.0), point(500, lat = 39.0005)))
        val (from, to) = ClueBinder.originShift(shown, submitted)!!
        assertEquals(t0 - 4_000, from.timeMs)
        assertEquals(t0 + 500, to.timeMs)
        val (lat, lng) = ClueBinder.translated(39.001, -121.001, from, to)
        assertEquals(39.0015, lat, 1e-9)
        assertEquals(-121.001, lng, 1e-6)
        // Same waypoint: no shift.
        assertNull(ClueBinder.originShift(submitted, submitted))
        // A clue projected from the frame's own position never moves.
        val framed = shown.copy(originIsWaypoint = false)
        val framedSubmitted = ClueBinder.refresh(framed, listOf(point(-4_000, lat = 39.0), point(500)))
        assertEquals(500L, framedSubmitted.offsetMs)
        assertNull(ClueBinder.originShift(framed, framedSubmitted))
    }

    @Test fun publishedDescriptionCarriesTheBinding() {
        val binding = bind(listOf(point(1_500, received = t0 + 1_900)), receivedAt = t0 + 400)
        val text = ClueBindingText.publishedDescription("Red jacket\n", binding)
        assertTrue(text.startsWith("Red jacket\n\nWaypoint binding:\n  Offset: +1.500 s (waypoint minus capture, drone clock)"))
        assertTrue(text.contains("  Quality: exact"))
        assertTrue(text.contains("  Capture: 2026-09-28T00:04:33.000Z (stream-pts)"))
        assertTrue(text.contains("  App receive times (diagnostic): capture 2026-09-28T00:04:33.400Z, waypoint 2026-09-28T00:04:34.900Z"))
        assertFalse(text.contains("provisional"))
        assertEquals("Plain", ClueBindingText.publishedDescription("Plain", null))
    }

    @Test fun streamDroneClockAnchorsPtsAndResetsOnJump() {
        val clock = StreamDroneClock()
        assertNull(clock.droneTimeMs(1_000_000))
        clock.observe(1_000_000, t0 + 300)
        clock.observe(2_000_000, t0 + 1_150) // less delayed: anchor moves earlier
        clock.observe(3_000_000, t0 + 2_400)
        assertEquals(t0 - 850, clock.offsetMs)
        assertEquals(t0 + 4_150, clock.droneTimeMs(5_000_000))
        // PTS restart (new session) re-anchors.
        clock.observe(500_000, t0 + 10_000)
        assertEquals(t0 + 9_500, clock.offsetMs)
        clock.observe(null, t0)
        assertEquals(t0 + 9_500, clock.offsetMs)
    }

    @Test fun clueRecordsWithoutBindingFieldsStillDecode() {
        val legacy = JSONObject().put("captureTimeMs", t0)
        val binding = ClueBinding.fromJson(legacy)!!
        assertEquals("app-receive", binding.captureTimeSource)
        assertEquals(ClueBindingQuality.UNBOUND, binding.quality)
        assertNull(ClueBinding.fromJson(JSONObject()))
        assertNull(ClueBinding.fromJson(null))
    }
}
