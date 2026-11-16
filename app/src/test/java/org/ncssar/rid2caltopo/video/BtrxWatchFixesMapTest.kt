package org.ncssar.rid2caltopo.video

import kotlin.math.cos
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxBearingEstimate
import org.ncssar.rid2caltopo.btrx.BtrxBearingSnapshot
import org.ncssar.rid2caltopo.btrx.BtrxBearingSource
import org.ncssar.rid2caltopo.btrx.BtrxBearingStats
import org.ncssar.rid2caltopo.btrx.BtrxBearingText

/** Nov 16 watch test: camera centre crosshair, crosshair target, focus zoom box, inspect card text. */
class BtrxWatchFixesMapTest {
    private val m = 6378137.0
    private fun north(lat: Double) = Math.toRadians(lat - 39.0) * m
    private fun east(lon: Double) = Math.toRadians(lon + 121.0) * m * cos(Math.toRadians(39.0))

    @Test fun crosshairIsTheOpticalAxisGroundPoint() {
        // Nadir: right under the aircraft.
        val nadir = CameraFootprintGeometry.project(CameraFootprintInput(39.0, -121.0, 39.0, -121.0, 100.0, 0.0, -90.0, 90.0, 60.0))
        val c0 = nadir.center!!
        assertEquals(0.0, north(c0.latitude), 0.01)
        assertEquals(0.0, east(c0.longitude), 0.01)
        // 45 deg down, heading east, 100 m up: 100 m east.
        val oblique = CameraFootprintGeometry.project(CameraFootprintInput(39.0, -121.0, 39.0, -121.0, 100.0, 90.0, -45.0, 60.0, 40.0))
        val c1 = oblique.center!!
        assertEquals(100.0, east(c1.longitude), 0.05)
        assertEquals(0.0, north(c1.latitude), 0.05)
        // 30 deg down heading north: height / tan(30).
        val shallow = CameraFootprintGeometry.project(CameraFootprintInput(39.0, -121.0, 39.0, -121.0, 100.0, 0.0, -30.0, 60.0, 40.0))
        assertEquals(100.0 / tan(Math.toRadians(30.0)), north(shallow.center!!.latitude), 0.1)
        // Terrain projection: same point on a flat DEM at any absolute height.
        val terrain = CameraFootprintGeometry.project(CameraFootprintInput(39.0, -121.0, 39.0, -121.0, 100.0, 90.0, -45.0, 60.0, 40.0), elevation = { _, _ -> 900.0 })
        assertEquals(100.0, east(terrain.center!!.longitude), 1.0)
        // Axis above the horizon: no crosshair (outline open).
        val up = CameraFootprintGeometry.project(CameraFootprintInput(39.0, -121.0, 39.0, -121.0, 100.0, 0.0, 5.0, 60.0, 40.0))
        assertNull(up.center)
        // The displayed outline carries the crosshair.
        val drawing = cameraFootprintLiveDrawing(CameraFootprintInput(39.0, -121.0, 39.0, -121.0, 100.0, 90.0, -45.0, 60.0, 40.0), null)
        assertNotNull(cameraFootprintDisplayOutline(drawing).center)
    }

    @Test fun crosshairToTargetDistanceAndBearing() {
        val c = CameraFootprintVertex(39.0, -121.0, clipped = false, corner = false)
        val dLat = Math.toDegrees(50.0 / m)
        val (d, b) = cameraCrosshairToTarget(c, 39.0 - dLat, -121.0)
        assertEquals(50.0, d, 0.2)
        assertEquals(180.0, b, 0.01)
        val dLon = Math.toDegrees(30.0 / (m * cos(Math.toRadians(39.0))))
        val (d2, b2) = cameraCrosshairToTarget(c, 39.0, -121.0 + dLon)
        assertEquals(30.0, d2, 0.2)
        assertEquals(90.0, b2, 0.05)
        assertEquals("50 m · 180°", BtrxBearingText.crosshairToTarget(50.2, 180.0))
        assertEquals("12 m · 0°", BtrxBearingText.crosshairToTarget(12.0, 359.7))
    }

    private fun src(key: Long, est: BtrxBearingEstimate?, strongest: Pair<Double, Double>? = null, pinned: Boolean = false) = BtrxBearingSource(
        index = 0, key = key, shortId = "Q3x9Kd", provisional = false, devClass = 2, rgb = 0x2196F3, flags = 0,
        firstHeardUs = 0L, lastHeardUs = 5_000_000L, reportsIn = 40, reportsUsed = 30, reportsInWindow = 25, peakAlpha = 0.6f,
        estimate = est, latestRay = null, srcId = 50703, lastRssiDbm = -61, maxRssiDbm = -52,
        strongestLat = strongest?.first, strongestLon = strongest?.second, pinned = pinned,
    )

    private fun snap(focus: Long, vararg s: BtrxBearingSource) = BtrxBearingSnapshot(
        1L, 65_000_000L, 0, 0, 0.0, 0.0, 0.0, 0.0, ByteArray(0), s.toList(),
        BtrxBearingStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0L, 0L, 0L, 0, 0, 0f, 0f, 0, 0, 0, 0L, 0, emptyList()),
        focusKey = focus,
    )

    @Test fun crosshairTargetIsInspectedElseFocusedSource() {
        val est = BtrxBearingEstimate(39.001, -121.001, 6f, 4f, 30f, false, 0.0, 0.0, 0f)
        val a = src(1L, est, pinned = true)
        val b = src(2L, null, strongest = 39.002 to -121.002)
        assertNull(btrxCrosshairTarget(snap(0L, a, b), null))
        assertEquals(39.001 to -121.001, btrxCrosshairTarget(snap(0L, a, b), 1L))
        assertEquals(39.002 to -121.002, btrxCrosshairTarget(snap(2L, a, b), null))
        assertEquals(39.001 to -121.001, btrxCrosshairTarget(snap(2L, a, b), 1L))
        assertNull(btrxCrosshairTarget(null, 1L))
    }

    @Test fun focusBoxCoversEstimateAndStrongestHere() {
        val est = BtrxBearingEstimate(39.0, -121.0, 10f, 5f, 0f, false, 0.0, 0.0, 0f)
        val box = BtrxBearingMapOverlay.focusBounds(src(1L, est, strongest = 39.001 to -121.0))!!
        assertTrue(box.latNorth >= 39.001 && box.latSouth <= 39.0 - 20.0 / 111_320.0)
        // At least 150 m wide.
        assertTrue((box.lonEast - box.lonWest) * 111_320.0 * cos(Math.toRadians(39.0)) >= 149.9)
        assertNull(BtrxBearingMapOverlay.focusBounds(src(1L, null)))
    }

    @Test fun inspectCardLines() {
        val est = BtrxBearingEstimate(39.0012345, -121.0006789, 6f, 4f, 30f, false, 0.0, 0.0, 0f)
        val lines = BtrxBearingText.inspectLines(src(1L, est, pinned = true), 65_000_000L)
        assertEquals("Source Q3x9Kd (id 50703) · watch", lines[0])
        assertEquals("Estimate 39.001235, -121.000679", lines[1])
        assertEquals("Uncertainty ±12 m (2σ)", lines[2])
        assertEquals("Reports 25 in the fade window (30 used of 40)", lines[3])
        assertEquals("Last heard 1 min ago", lines[4])
        assertEquals("Peak RSSI -52 dBm (latest -61 dBm)", lines[5])
    }
}
