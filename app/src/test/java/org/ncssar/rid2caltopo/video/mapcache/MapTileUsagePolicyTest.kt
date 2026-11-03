package org.ncssar.rid2caltopo.video.mapcache

import org.junit.Assert.*
import org.junit.Test

class MapTileUsagePolicyTest {
    @Test fun legacyAndInvalidAgesCannotRefreshTilesBeforeSevenDays() {
        for (age in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 6L, 7L)) {
            assertEquals(7L, MapTileUsagePolicy.cacheAgeDays(age))
        }
        assertEquals(30L, MapTileUsagePolicy.cacheAgeDays(30))
        assertEquals(365L, MapTileUsagePolicy.cacheAgeDays(365))
        assertEquals(3650L, MapTileUsagePolicy.cacheAgeDays(Long.MAX_VALUE))
    }
    @Test fun exportsRejectPublicBasemapsAndUnknownSources() {
        for (name in listOf("OSM-Standard", "Mapnik", "MAPNIK", "ArcGIS-WorldImagery", "Imagery", "unknown")) {
            assertFalse(name, MapTileUsagePolicy.allowsPackageExport(name))
        }
        assertTrue(MapTileUsagePolicy.allowsPackageExport("USGS-Contours"))
        assertTrue(MapTileUsagePolicy.allowsPackageExport("USGS-ScannedTopo"))
        assertTrue(MapTileUsagePolicy.allowsPackageExport("USFS-Topo"))
    }
    @Test fun offlineProviderCannotBeDisguisedByChangingSourceNameOrEndpoint() {
        assertTrue(MapTileUsagePolicy.allowsOfflineDownload("USGS-Contours", "https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export?f=image"))
        assertTrue(MapTileUsagePolicy.allowsOfflineDownload(
            "USGS-ScannedTopo",
            "https://historical1.arcgis.com/arcgis/rest/services/USGS_Historical_Topographic_Maps/ImageServer/exportImage?f=image"
        ))
        assertTrue(MapTileUsagePolicy.allowsOfflineDownload(
            "USFS-Topo",
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/10/380/160"
        ))
        for (name in listOf("USGS-Contours", "OSM-Standard", "ArcGIS-WorldImagery", "USGS-ScannedTopo", "USFS-Topo")) {
            for (url in listOf("https://tile.openstreetmap.org/0/0/0.png", "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/0/0/0.jpg")) {
                assertFalse(MapTileUsagePolicy.allowsOfflineDownload(name,url))
            }
        }
        assertFalse(MapTileUsagePolicy.allowsOfflineDownload("USGS-Contours", "http://carto.nationalmap.gov/export"))
    }

    @Test fun fetchConcurrencyAllowsBoundedParallelOnlyForUsgsHostedTopo() {
        assertEquals(1, MapTileUsagePolicy.liveFetchConcurrency("OSM-Standard"))
        assertEquals(1, MapTileUsagePolicy.liveFetchConcurrency("ArcGIS-WorldImagery"))
        assertEquals(1, MapTileUsagePolicy.liveFetchConcurrency("USGS-Contours"))
        assertEquals(1, MapTileUsagePolicy.offlineFetchConcurrency("OSM-Standard"))
        assertEquals(1, MapTileUsagePolicy.offlineFetchConcurrency("ArcGIS-WorldImagery"))
        assertEquals(1, MapTileUsagePolicy.offlineFetchConcurrency("USGS-Contours"))

        assertEquals(5, MapTileUsagePolicy.liveFetchConcurrency("USGS-ScannedTopo"))
        assertEquals(5, MapTileUsagePolicy.liveFetchConcurrency("USFS-Topo"))
        assertEquals(5, MapTileUsagePolicy.offlineFetchConcurrency("USGS-ScannedTopo"))
        assertEquals(5, MapTileUsagePolicy.offlineFetchConcurrency("USFS-Topo"))
        assertTrue(MapTileUsagePolicy.USGS_HOSTED_FETCH_CONCURRENCY in 4..6)
        assertEquals(
            MapTileUsagePolicy.USGS_HOSTED_FETCH_CONCURRENCY * 2 + 1,
            MapTileUsagePolicy.OFFLINE_FETCH_THREAD_POOL
        )
    }
}
