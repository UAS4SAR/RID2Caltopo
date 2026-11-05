package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerCleanupRetryTest {
    @Test
    fun clearJournalOnlyAfterConfirmedDelete() {
        assertTrue(MarkerCleanupRetry.clearJournal(204, true))
        assertTrue(MarkerCleanupRetry.clearJournal(200, true))
        assertTrue(MarkerCleanupRetry.clearJournal(404, false))
        assertFalse(MarkerCleanupRetry.clearJournal(200, false))
        assertFalse(MarkerCleanupRetry.clearJournal(400, false))
        assertFalse(MarkerCleanupRetry.clearJournal(403, false))
        assertFalse(MarkerCleanupRetry.clearJournal(500, false))
    }

    @Test
    fun backoffGrowsAndPrefixIsEightCharacters() {
        assertEquals(30_000L, MarkerCleanupRetry.delayMs(1))
        assertEquals(120_000L, MarkerCleanupRetry.delayMs(2))
        assertEquals(21_600_000L, MarkerCleanupRetry.delayMs(99))
        assertEquals("833a8140", MarkerCleanupRetry.idPrefix("833a8140-2a8b-3b23-8876-c3af62524ef0"))
    }

    @Test
    fun resolveUsesServerShapeIdInsteadOfClientUuid() {
        val feature = JSONObject(
            """{"id":"serverShape","properties":{"class":"Shape","r2c-guid":"marker-guid-test"}}"""
        )
        val target = DeviceMarkerDeletePlan.resolve(
            "833a8140-2a8b-3b23-8876-c3af62524ef0",
            "marker-guid-test",
            listOf(feature),
        )
        assertEquals("Shape", target.featureClass)
        assertEquals("serverShape", target.id)
        assertEquals("Shape/serverShape", target.wire())
    }

    @Test
    fun resolveSkipsFoldersAndKeepsMarkerIdsBare() {
        val folder = JSONObject("""{"id":"folder1","properties":{"class":"Folder","r2c-guid":"marker-guid-test"}}""")
        val marker = JSONObject("""{"id":"srvMarker","properties":{"class":"Marker","r2c-guid":"marker-guid-test"}}""")
        val target = DeviceMarkerDeletePlan.resolve("client-uuid", "marker-guid-test", listOf(folder, marker))
        assertEquals("Marker", target.featureClass)
        assertEquals("srvMarker", target.id)
        assertEquals("srvMarker", target.wire())
    }
}
