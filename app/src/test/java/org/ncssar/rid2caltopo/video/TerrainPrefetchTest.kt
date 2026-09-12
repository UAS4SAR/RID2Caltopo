package org.ncssar.rid2caltopo.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerrainPrefetchTest {
    @Test fun operatingEnvelopeCoversOneMileFromBothEdgesOfSameLocationCell() {
        val bounds = demPrefetchBounds(39.001, -121.001, DEM_OPERATING_RADIUS_METERS)
        val sameCellBounds = demPrefetchBounds(39.0049, -121.0049, DEM_OPERATING_RADIUS_METERS)
        assertEquals(bounds.latNorth, sameCellBounds.latNorth, 0.0)
        assertEquals(bounds.lonWest, sameCellBounds.lonWest, 0.0)
        val latPad = DEM_OPERATING_RADIUS_METERS / 111_195.0
        val lonPad = latPad / kotlin.math.cos(Math.toRadians(39.005))
        assertTrue(bounds.latSouth <= 39.0 - latPad)
        assertTrue(bounds.latNorth >= 39.005 + latPad)
        assertTrue(bounds.lonWest <= -121.005 - lonPad)
        assertTrue(bounds.lonEast >= -121.0 + lonPad)
    }

    @Test fun operatingAreaIncludesAdjacentTilesAtCornerAndRejectsUnrelatedTile() {
        val items = JSONArray()
        fun tile(name: String, south: Double, north: Double, west: Double, east: Double) = JSONObject()
            .put("downloadURL", "https://example.test/$name.tif")
            .put("sizeInBytes", 350000000L)
            .put("boundingBox", JSONObject().put("minY", south).put("maxY", north).put("minX", west).put("maxX", east))
        items.put(tile("sw", 38.9, 39.0, -121.1, -121.0))
        items.put(tile("se", 38.9, 39.0, -121.0, -120.9))
        items.put(tile("nw", 39.0, 39.1, -121.1, -121.0))
        items.put(tile("ne", 39.0, 39.1, -121.0, -120.9))
        items.put(tile("far", 40.0, 40.1, -120.0, -119.9))
        items.put(items.getJSONObject(0))
        val result = parseS1mDownloadsForBounds(JSONObject().put("items", items),
            demPrefetchBounds(39.0001, -121.0001, DEM_OPERATING_RADIUS_METERS))
        assertEquals(4, result.size)
        assertTrue(result.none { it.url.contains("far") })
    }

    @Test fun failedStartupTransferRetriesAndSuccessfulTransferDeduplicates() {
        val gate = TerrainPrefetchGate()
        assertTrue(gate.schedule("device", 0))
        assertFalse(gate.schedule("device", 1))
        gate.finish("device", false, 1000)
        assertFalse(gate.schedule("device", 30000))
        assertTrue(gate.schedule("device", 31000))
        gate.finish("device", true, 32000)
        assertFalse(gate.schedule("device", 1000000))
        assertTrue(gate.schedule("new-area", 1000000))
    }
}
