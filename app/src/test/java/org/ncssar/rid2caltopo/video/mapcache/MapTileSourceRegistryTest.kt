package org.ncssar.rid2caltopo.video.mapcache

import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.video.UsgsContoursTileSource
import org.ncssar.rid2caltopo.video.UsgsScannedTopoTileSource
import org.ncssar.rid2caltopo.video.UsfsTopoTileSource
import org.ncssar.rid2caltopo.video.offlinePrepTileOperationCount
import org.ncssar.rid2caltopo.video.offlinePrepTileSources

class MapTileSourceRegistryTest {
    @Test
    fun crossPlatformIdsMatchAppleContract() {
        assertEquals(
            listOf(
                "ArcGIS-WorldImagery",
                "OSM-Standard",
                "USFS-Topo",
                "USGS-Contours",
                "USGS-ScannedTopo",
            ),
            MapTileSourceRegistry.crossPlatformIds
        )
    }

    @Test
    fun offlineAllowedSourcesExcludeOsmAndEsri() {
        val offlineIds = MapTileSourceRegistry.offlineAllowedSources.map { it.id }.toSet()
        assertEquals(setOf("USGS-ScannedTopo", "USFS-Topo", "USGS-Contours"), offlineIds)
        assertFalse(MapTileSourceRegistry.descriptor("OSM-Standard")!!.offlineAllowed)
        assertFalse(MapTileSourceRegistry.descriptor("ArcGIS-WorldImagery")!!.offlineAllowed)
        assertEquals(
            listOf("USGS-ScannedTopo", "USFS-Topo"),
            MapTileSourceRegistry.offlineAllowedBaseSources.map { it.id }
        )
    }

    @Test
    fun selectingBothTopoSourcesAndContoursDownloadsAllThree() {
        assertEquals(
            listOf(
                UsgsScannedTopoTileSource.name(),
                UsfsTopoTileSource.name(),
                UsgsContoursTileSource.name(),
            ),
            offlinePrepTileSources(
                includeUsgsScannedTopo = true,
                includeUsfsTopo = true,
                includeContours = true
            ).map { it.name() }
        )
    }

    @Test
    fun zoomClampingHonorsNativeSourceRange() {
        val usgs = MapTileSourceRegistry.clampZoomRange("USGS-ScannedTopo", 8, 22)
        assertEquals(8, usgs.first)
        assertEquals(16, usgs.last)
        val usfs = MapTileSourceRegistry.clampZoomRange("USFS-Topo", 12, 16)
        assertEquals(12, usfs.first)
        assertEquals(16, usfs.last)
        val overMax = MapTileSourceRegistry.clampZoomRange("USGS-ScannedTopo", 18, 20)
        assertEquals(16, overMax.first)
        assertEquals(16, overMax.last)
        assertTrue(MapTileSourceRegistry.clampZoomRange("unknown-source", 8, 12).isEmpty())
    }

    @Test
    fun estimateCountsEverySelectedSource() {
        assertEquals(36, offlinePrepTileOperationCount(12, 3))
        assertEquals(12, offlinePrepTileOperationCount(12, 1))
        assertEquals(0, offlinePrepTileOperationCount(12, 0))
    }

    @Test
    fun concurrencyGreaterThanOneOnlyForUsgsAndUsfsTopo() {
        for (source in MapTileSourceRegistry.all) {
            val concurrency = MapTileUsagePolicy.offlineFetchConcurrency(source.id)
            if (source.id == "USGS-ScannedTopo" || source.id == "USFS-Topo") {
                assertTrue(source.id, concurrency > 1)
                assertEquals(MapTileUsagePolicy.USGS_HOSTED_FETCH_CONCURRENCY, concurrency)
            } else {
                assertEquals(source.id, 1, concurrency)
            }
        }
    }

    @Test
    fun usfsTopoRetainsLegacyUsgsTopoCacheAlias() {
        assertEquals(listOf("USGS-Topo"), MapTileSourceRegistry.legacyCacheNames("USFS-Topo"))
        assertTrue(MapTileSourceRegistry.legacyCacheNames("USGS-ScannedTopo").isEmpty())
        assertEquals(listOf("Mapnik", "MAPNIK"), MapTileSourceRegistry.legacyCacheNames("OSM-Standard"))
    }

    @Test
    fun backgroundPoolCoversBothParallelSourcesPlusContours() {
        assertEquals(
            MapTileUsagePolicy.USGS_HOSTED_FETCH_CONCURRENCY * 2 + 1,
            MapTileUsagePolicy.OFFLINE_FETCH_THREAD_POOL
        )
    }
}
