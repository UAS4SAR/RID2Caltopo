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
    }
    @Test fun offlineProviderCannotBeDisguisedByChangingSourceNameOrEndpoint() {
        assertTrue(MapTileUsagePolicy.allowsOfflineDownload("USGS-Contours", "https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export?f=image"))
        for (name in listOf("USGS-Contours", "OSM-Standard", "ArcGIS-WorldImagery")) {
            for (url in listOf("https://tile.openstreetmap.org/0/0/0.png", "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/0/0/0.jpg")) {
                assertFalse(MapTileUsagePolicy.allowsOfflineDownload(name,url))
            }
        }
        assertFalse(MapTileUsagePolicy.allowsOfflineDownload("USGS-Contours", "http://carto.nationalmap.gov/export"))
    }
}
