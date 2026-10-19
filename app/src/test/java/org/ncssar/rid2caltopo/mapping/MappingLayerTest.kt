package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MappingLayerTest {
    private fun layer(bounds: String) = JSONObject("""{"type":"r2c_mapping_layer","version":1,"layerId":"00000000-0000-0000-0000-000000000001","incidentId":"MAP123","title":"Thermal","tileScheme":"xyz","minZoom":18,"maxZoom":21,"bounds":$bounds,"tileUrlTemplate":"http://192.168.1.20:8765/tiles/{Z}/{X}/{Y}.png"}""")

    @Test fun parsesOptionalBoundsForOfflineMapPackaging() {
        val parsed = MappingLayer.parse(layer("[-121.1,39.1,-121.0,39.2]"))
        assertEquals(listOf(-121.1,39.1,-121.0,39.2), parsed.bounds)
        assertEquals("MAP123", parsed.incidentId)
    }

    @Test fun rejectsReversedBounds() {
        assertThrows(IllegalArgumentException::class.java) { MappingLayer.parse(layer("[-121.0,39.2,-121.1,39.1]")) }
    }

    @Test fun resultGenerationSurvivesRoundTripAndOrdersRepeatedCapture() {
        val capture = "2026:10:13 18:23:48"
        val first = MappingLayer.parse(layer("[]").put("title", "MapTest · $capture")
            .put("captureTimeLocal",capture).put("processedAt","2026-10-14T16:23:11.949380Z"))
        val later = first.copy(processedAt="2026-10-14T16:24:00Z")
        val unknown = first.copy(processedAt="")
        assertEquals(first.processedAt, MappingLayer.parse(first.json()).processedAt)
        assertEquals("MapTest", first.resultTitle)
        assertEquals(listOf(later,first,unknown), listOf(first,unknown,later).sortedByDescending { it.generatedInstant })
        assertEquals("Oct 14, 2026 09:23:11 PDT", MappingTimestamps.generated(first.processedAt,java.time.ZoneId.of("America/Los_Angeles")))
        assertEquals("Oct 13, 2026 18:23:48",MappingTimestamps.captured(capture))
        assertEquals("Time unavailable",unknown.generatedLabel)
    }

}
