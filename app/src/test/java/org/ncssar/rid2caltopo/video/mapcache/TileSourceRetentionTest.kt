package org.ncssar.rid2caltopo.video.mapcache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSourceRetentionTest {
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L
    private val year = 365L * day

    @Test fun restrictedWindowIsASingleThreeDayConstant() {
        assertEquals(3L, TileSourceRetention.RESTRICTED_RETENTION_DAYS)
        assertEquals(3L * day, TileSourceRetention.RESTRICTED_RETENTION_MS)
    }

    @Test fun osmAndEsriNamesAreRestricted() {
        for (name in listOf(
            "OSM-Standard", "Mapnik", "MAPNIK", "openStreetMap", "OpenStreetMap", "osm",
            "ArcGIS-WorldImagery", "Imagery", "imagery", "Esri", "World_Imagery", "WorldImagery"
        )) {
            assertEquals(name, TileRetentionClass.RESTRICTED, TileSourceRetention.classifySource(name))
            assertEquals(name, TileRetentionClass.RESTRICTED, TileSourceRetention.classifyLocation("v1|$name|12|3|4"))
        }
    }

    @Test fun demAolAndPublicToposAreNeverPurged() {
        val ancient = now - 3_000L * day
        val locations = listOf(
            "v1|USGS-Topo|12|3|4",
            "v1|usgsTopo|8|1|2",
            "v1|USFS-Topo|10|4|5",
            "v1|usfsTopo|10|4|5",
            "v1|FSTopo|10|4|5",
            "v1|DEM|1|2|3",
            "v1|S1M|1|2|3",
            "v1|usgs-geotiff|1|2|3",
            "v1|AOL|1|2|3",
            "v1|lidar|1|2|3",
            "/storage/DEM/S1M_n0450e1510_20260821.tif",
            "/storage/MapTiles/surface_v1/tile-0-0.aol",
            "cache/dem/n45w122.tiff",
            "v1|custom-layer|4|5|6",
            "not-a-cache-key"
        )
        for (location in locations) {
            assertEquals(location, TileRetentionClass.UNRESTRICTED, TileSourceRetention.classifyLocation(location))
            assertFalse(location, TileSourceRetention.shouldPurge(location, ancient, now, year))
        }
    }

    @Test fun osmAndEsriExpireAfterThreeDaysAndNotBefore() {
        val key = "v1|ArcGIS-WorldImagery|12|1582|657"
        val legacy = "/var/MapTiles/openStreetMap/12/657/1582.png"
        assertFalse(TileSourceRetention.shouldPurge(key, now - TileSourceRetention.RESTRICTED_RETENTION_MS + 1, now, year))
        assertTrue(TileSourceRetention.shouldPurge(key, now - TileSourceRetention.RESTRICTED_RETENTION_MS, now, year))
        assertTrue(TileSourceRetention.shouldPurge(legacy, now - 4 * day, now, year))
        assertTrue(TileSourceRetention.shouldPurge("v1|MAPNIK|1|0|0", now - 4 * day, now, year))
        // A long operator age must not keep a restricted tile.
        assertTrue(TileSourceRetention.shouldPurge("v1|OSM-Standard|1|0|0", now - 4 * day, now, 3650L * day))
    }

    @Test fun missingAgeIsNotDeleted() {
        assertFalse(TileSourceRetention.shouldPurge("v1|OSM-Standard|1|0|0", 0L, now, year))
        assertFalse(TileSourceRetention.shouldPurge("v1|USGS-Contours|1|0|0", -1L, now, day))
        val capped = TileSourceRetention.decision("v1|Imagery|1|0|0", 0L, now + year, now, year)
        assertEquals(TileRetentionDecision.RewriteExpiry(now + TileSourceRetention.RESTRICTED_RETENTION_MS), capped)
    }

    @Test fun youngRestrictedTilesKeepAThreeDayCap() {
        val created = now - day
        val decision = TileSourceRetention.decision(
            "v1|OSM-Standard|3|1|2",
            created,
            created + 30L * day,
            now,
            year
        )
        assertEquals(TileRetentionDecision.RewriteExpiry(created + TileSourceRetention.RESTRICTED_RETENTION_MS), decision)
        val alreadyCapped = TileSourceRetention.decision(
            "v1|OSM-Standard|3|1|2",
            created,
            created + TileSourceRetention.RESTRICTED_RETENTION_MS,
            now,
            year
        )
        assertEquals(TileRetentionDecision.Keep, alreadyCapped)
    }

    @Test fun contoursFollowTheOperatorAgeAndUnrestrictedExpiryIsCleared() {
        val contours = "v1|USGS-Contours|9|8|7"
        assertEquals(TileRetentionClass.STANDARD, TileSourceRetention.classifyLocation(contours))
        assertFalse(TileSourceRetention.shouldPurge(contours, now - 4 * day, now, year))
        assertTrue(TileSourceRetention.shouldPurge(contours, now - year, now, year))
        assertEquals(
            TileRetentionDecision.RewriteExpiry(Long.MAX_VALUE),
            TileSourceRetention.decision("usgsTopo/12/1/2.jpg", now - year, now + day, now, year)
        )
        assertEquals(
            TileRetentionDecision.Keep,
            TileSourceRetention.decision("v1|USGS-Topo|1|0|0", now - year, Long.MAX_VALUE, now, year)
        )
    }

    @Test fun newWritesUseTheSourceWindow() {
        assertEquals(now + TileSourceRetention.RESTRICTED_RETENTION_MS, TileSourceRetention.expiresAtMs("OSM-Standard", now, year))
        assertEquals(now + TileSourceRetention.RESTRICTED_RETENTION_MS, TileSourceRetention.expiresAtMs("ArcGIS-WorldImagery", now, year))
        assertEquals(Long.MAX_VALUE, TileSourceRetention.expiresAtMs("USGS-Topo", now, year))
        assertEquals(Long.MAX_VALUE, TileSourceRetention.expiresAtMs("USFS-Topo", now, year))
        assertEquals(Long.MAX_VALUE, TileSourceRetention.expiresAtMs("S1M", now, year))
        assertEquals(now + year, TileSourceRetention.expiresAtMs("USGS-Contours", now, year))
    }
}
