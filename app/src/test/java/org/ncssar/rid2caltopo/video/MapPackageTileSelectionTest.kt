package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPackageTileSelectionTest {
    @Test fun defaultsIncludeAllSupportedSourcesAndTerrain() {
        val selected = mapPackageTileLabels.toSet()
        assertEquals(setOf("USGS Scanned Topo", "USFS Topo", "Contours", "DEM", "AOL"), selected)
        assertEquals(
            listOf("USGS-ScannedTopo", "USFS-Topo", "USGS-Contours"),
            mapPackageSources(selected).map { it.name() },
        )
        assertTrue(mapPackageSources(setOf("Imagery", "OpenStreetMap")).isEmpty())
        assertEquals(listOf("USFS-Topo"), mapPackageSources(setOf("USFS Topo")).map { it.name() })
    }
}
