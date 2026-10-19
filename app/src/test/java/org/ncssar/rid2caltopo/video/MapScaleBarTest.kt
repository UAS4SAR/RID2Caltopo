package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test

class MapScaleBarTest {
    @Test fun mileUses5280FeetAndExactPixelWidth() {
        val scale = mapScaleBarState(16.09344, 120.0)!!
        assertEquals("1 mi", scale.label)
        assertEquals(100.0, scale.widthPx, 0.00001)
        assertEquals(5280.0, scale.widthPx * 16.09344 / 0.3048, 0.00001)
    }
    @Test fun feetAndDensityPreserveDistance() {
        val normal = mapScaleBarState(0.3048, 120.0)!!
        val dense = mapScaleBarState(0.1524, 240.0)!!
        assertEquals("100 ft", normal.label)
        assertEquals(normal.label, dense.label)
        assertEquals(normal.widthPx * 2, dense.widthPx, 0.00001)
    }
    @Test fun invalidProjectionIsHidden() {
        assertNull(mapScaleBarState(0.0, 120.0))
        assertNull(mapScaleBarState(Double.NaN, 120.0))
        assertNull(mapScaleBarState(1.0, 0.0))
    }
    @Test fun metricScaleUsesExactDistanceInMetersAndKilometers() {
        val units = org.ncssar.rid2caltopo.data.DisplayUnits.METRIC
        val small = mapScaleBarState(1.0, 120.0, units)!!
        assertEquals("100 m", small.label)
        assertEquals(100.0, small.widthPx, 1e-8)
        val large = mapScaleBarState(10.0, 120.0, units)!!
        assertEquals("1 km", large.label)
        assertEquals(100.0, large.widthPx, 1e-8)
    }
}
