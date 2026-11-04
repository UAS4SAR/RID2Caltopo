package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CluePhotoGeometrySyncTest {
    @Test
    fun identicalPointsDoNotNeedRepair() {
        assertFalse(CluePhotoGeometrySync.needsRepair(47.0, -122.0, 47.0, -122.0))
    }

    @Test
    fun subMeterOffsetDoesNotNeedRepair() {
        // ~0.3 m north of 47°N
        assertFalse(CluePhotoGeometrySync.needsRepair(47.0, -122.0, 47.0000027, -122.0))
    }

    @Test
    fun multiMeterOffsetNeedsRepair() {
        // ~11 m north of 47°N
        assertTrue(CluePhotoGeometrySync.needsRepair(47.0, -122.0, 47.0001, -122.0))
    }

    @Test
    fun pointLatLngReadsFeatureCoordinates() {
        val feature = JSONObject(
            """{"id":"x","type":"Feature","geometry":{"type":"Point","coordinates":[-122.5,47.25]},"properties":{"class":"Marker"}}""",
        )
        val ll = CluePhotoGeometrySync.pointLatLng(feature)
        assertNotNull(ll)
        assertEquals(47.25, ll!![0], 1e-9)
        assertEquals(-122.5, ll[1], 1e-9)
    }

    @Test
    fun pointLatLngMissingGeometryReturnsNull() {
        assertNull(CluePhotoGeometrySync.pointLatLng(JSONObject("""{"id":"x"}""")))
    }

    @Test
    fun copyPropertiesPreservesParentAndBackendMedia() {
        val feature = JSONObject(
            """{"properties":{"class":"MapMediaObject","parentId":"Marker:abc","backendMediaId":"m1","title":"t"}}""",
        )
        val props = CluePhotoGeometrySync.copyProperties(feature)
        assertEquals("MapMediaObject", props.getString("class"))
        assertEquals("Marker:abc", props.getString("parentId"))
        assertEquals("m1", props.getString("backendMediaId"))
        assertEquals("t", props.getString("title"))
    }
}
