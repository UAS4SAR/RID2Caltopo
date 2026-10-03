package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class MapPackageRegionTest {
    private val region = MapPackageRegion(1.0, 1.0, -1.0, -1.0)
    @Test fun includesCachedDetailOutsideEveryPreparationPreset() {
        for (z in listOf(0, 5, 12, 19, 22, 29)) {
            val mid = if (z == 0) 0 else 1 shl (z - 1)
            assertTrue("zoom $z", region.includesTile(z, mid, mid))
        }
        assertFalse(region.includesTile(22, 0, 0))
        assertFalse(region.includesTile(22, -1, 0))
    }
    @Test fun usesOverlapRatherThanRequiringEntireTileInsideViewport() {
        assertTrue(region.includesTile(0, 0, 0))
        assertFalse(region.overlaps(MapPackageRegion(2.0, 2.0, 1.0, 1.0)))
    }
    @Test fun wrappedViewportExcludesOppositeSideOfWorld() {
        val wrapped = MapPackageRegion(10.0, -179.0, -10.0, 179.0)
        assertTrue(wrapped.includesTile(10, 0, 512))
        assertTrue(wrapped.includesTile(10, 1023, 512))
        assertFalse(wrapped.includesTile(10, 512, 512))
    }
    @Test fun allDemResolutionsAreRecognized() {
        val expected = MapPackageRegion(40.0, -121.0, 39.0, -122.0)
        assertEquals(expected, MapPackageRegion.demBounds("USGS_1_n40w122.tif"))
        assertEquals(expected, MapPackageRegion.demBounds("USGS_13_n40w122.tif"))
        assertEquals(expected, MapPackageRegion.demBounds("R2C_S1M_3900000_4000000_-12200000_-12100000_piece.tif"))
        assertNull(MapPackageRegion.demBounds("USGS_1_n40w122.tif.partial"))
    }
}
