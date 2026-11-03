package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Edge overrun, 3D surface detail and unknown-area fill: persistence, legacy defaults, route and report plumbing. */
class MappingPlanKnobsTest {
    private val camera = MappingResolution.profiles.first { it.id == "m4td.visible.wide" }
    private fun feature() = JSONObject().put("id", "a1").put("properties", JSONObject().put("class", "Assignment").put("title", "Test"))
        .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(JSONArray()
            .put(JSONArray(listOf(-122.0, 37.0))).put(JSONArray(listOf(-121.999, 37.0)))
            .put(JSONArray(listOf(-121.999, 37.001))).put(JSONArray(listOf(-122.0, 37.001)))
            .put(JSONArray(listOf(-122.0, 37.0))))))
    private val mission get() = MappingMission(feature(), "MAP")

    @Test fun explicitKnobsRoundTripThroughMissionJson() {
        val plan = mission.json(camera, 2.0, MappingCaptureProfile.nadir, marginMeters = 33.5, surfaceCellMeters = 0.1, fillUnknown2DAreas = false)
        // mission.json is persisted as text; read it back the way resume does.
        val p = JSONObject(plan.toString()).getJSONObject("parameters")
        assertEquals(33.5, p.getDouble("marginMeters"), 0.0)
        assertEquals(0.1, p.getDouble("surfaceCellMeters"), 0.0)
        assertFalse(p.getBoolean("fillUnknown2DAreas"))
        assertEquals(0.1, MappingMissionDefaults.cell(p), 0.0)
        assertFalse(MappingMissionDefaults.fillUnknown2D(p))
        assertEquals(33.5, MappingMissionDefaults.margin(p), 0.0)
    }

    @Test fun defaultMarginIsHalfPhotoWidthButAtLeastTenMeters() {
        // 4032 px at 2 cm = 80.64 m wide -> 40.32 m
        assertEquals(40.32, MappingMissionDefaults.defaultMarginMeters(4032, 2.0), 1e-9)
        // Small footprint falls back to the 10 m floor; very large footprints stop at the 100 m planner limit.
        assertEquals(10.0, MappingMissionDefaults.defaultMarginMeters(4032, 0.2), 0.0)
        assertEquals(100.0, MappingMissionDefaults.defaultMarginMeters(4032, 10.0), 0.0)
        val saved = mission.json(camera, 2.0, MappingCaptureProfile.nadir).getJSONObject("parameters")
        assertEquals(40.32, saved.getDouble("marginMeters"), 1e-9)
    }

    @Test fun unsupportedCellAndMarginAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { mission.json(camera, 2.0, MappingCaptureProfile.nadir, surfaceCellMeters = 0.3) }
        assertThrows(IllegalArgumentException::class.java) { mission.json(camera, 2.0, MappingCaptureProfile.nadir, marginMeters = 101.0) }
        assertThrows(IllegalArgumentException::class.java) { mission.json(camera, 2.0, MappingCaptureProfile.nadir, marginMeters = Double.NaN) }
        assertEquals(0.2, MappingMissionDefaults.cell(JSONObject().put("surfaceCellMeters", 0.3)), 0.0)
    }

    @Test fun legacyRouteWithoutKnobsUsesTenMeterMargin() {
        val plan = mission.json(camera, 2.0, MappingCaptureProfile.nadir)
        plan.getJSONObject("parameters").apply { remove("marginMeters"); remove("surfaceCellMeters"); remove("fillUnknown2DAreas") }
        val route = DjiMappingRoute(plan)
        assertEquals(10.0, route.routeEndMarginMeters, 0.0)
        assertEquals(10.0, route.summary().getDouble("routeEndMarginMeters"), 0.0)
        // Storage planning for a legacy mission reads the Standard 0.2 m cell.
        assertNotNull(MappingStorageEstimate.plan(plan, 40))
    }

    @Test fun summaryReportsClampedMarginAndObliqueExtension() {
        val plan = mission.json(camera, 2.0, MappingCaptureProfile.brushTrees, canopyHeightMeters = 10.0, maximumObstacleHeightMeters = 10.0, marginMeters = 20.0)
        plan.getJSONObject("parameters").put("marginMeters", 250.0)
        val summary = DjiMappingRoute(plan).summary()
        assertEquals(100.0, summary.getDouble("routeEndMarginMeters"), 0.0)
        val oblique = summary.getJSONArray("capturePasses").getJSONObject(1).getDouble("opticalCentreOffsetMeters")
        assertTrue(oblique > 0)
        assertEquals(Math.abs(oblique), summary.getDouble("obliqueEndExtensionMeters"), 1e-9)
        val nadir = DjiMappingRoute(mission.json(camera, 2.0, MappingCaptureProfile.nadir)).summary()
        assertEquals(0.0, nadir.getDouble("obliqueEndExtensionMeters"), 0.0)
    }

    @Test fun fineCellQuadruplesModelStorageTerm() {
        val m = mission
        val standard = MappingStorageEstimate.plan(m.json(camera, 2.0, surfaceCellMeters = 0.2), 40)
        val fine = MappingStorageEstimate.plan(m.json(camera, 2.0, surfaceCellMeters = 0.1), 40)
        val coarse = MappingStorageEstimate.plan(m.json(camera, 2.0, surfaceCellMeters = 0.4), 40)
        // products = 2*raster + model; raster is cell-independent, so Fine adds 3x and Coarse removes 0.75x the Standard model term.
        val standardModel = (fine.products - standard.products) / 3.0
        assertTrue(standardModel > 0)
        assertEquals(-0.75 * standardModel, (coarse.products - standard.products).toDouble(), 3.0)
        assertEquals(standard.originals, fine.originals)
        assertTrue(fine.temporary > standard.temporary && coarse.temporary < standard.temporary)
    }

    @Test fun layerParsesEstimatedFillAndFormatsInspectorLine() {
        val id = java.util.UUID.randomUUID().toString()
        val json = JSONObject().put("layerId", id).put("incidentId", "MAP").put("title", "Test").put("minZoom", 14).put("maxZoom", 20)
            .put("tileUrlTemplate", "http://127.0.0.1:8765/local-layer/$id/tiles/{Z}/{X}/{Y}.png")
            .put("estimatedFillFraction", 0.123).put("fillRadiusMeters", 25.0)
        val layer = MappingLayer.parse(json)
        assertEquals("Estimated (no measured depth): 12%", layer.estimatedFillSummary)
        assertEquals(25.0, layer.fillRadiusMeters, 0.0)
        assertEquals(0.123, layer.json().getDouble("estimatedFillFraction"), 0.0)
        val legacy = MappingLayer.parse(JSONObject(json.toString()).apply { remove("estimatedFillFraction"); remove("fillRadiusMeters") })
        assertNull(legacy.estimatedFillSummary)
        assertFalse(legacy.json().has("estimatedFillFraction"))
        assertEquals("Estimated (no measured depth): <1%", MappingLayer.fillSummary(0.004))
    }

    @Test fun geoTiffCarriesCompactImageDescriptionWithoutCoordinates() {
        val root = Files.createTempDirectory("geotiff-fill").toFile()
        try {
            File(root, "14/2600/6300.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf()) }
            val plan = MappingGeoTiff.plan(root, 14, 14)
            val description = MappingGeoTiff.imageDescription(0.1234)!!
            assertEquals("R2C map; estimated fill 12.3% of assignment (no measured depth)", description)
            val file = File(root, "map.tif")
            MappingGeoTiff.write(file, plan, { _, _ -> ByteArray(262144) }, description = description)
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val entries = (0 until b.getShort(8).toInt()).associate { val i = 10 + it * 12; (b.getShort(i).toInt() and 65535) to i }
            val i = entries.getValue(270)
            assertEquals(2, b.getShort(i + 2).toInt())
            val count = b.getInt(i + 4)
            assertEquals(description.length + 1, count)
            val bytes = ByteArray(count).also { b.position(b.getInt(i + 8)); b.get(it) }
            assertEquals(description, String(bytes, 0, count - 1, Charsets.US_ASCII))
            assertEquals(0, bytes.last().toInt())
            // Compact: no latitude/longitude style numbers.
            assertFalse(description.contains(Regex("-?\\d+\\.\\d{4,}")))
            assertNull(MappingGeoTiff.imageDescription(Double.NaN))
            // Without a description the tag is absent.
            val plain = File(root, "plain.tif")
            MappingGeoTiff.write(plain, plan, { _, _ -> ByteArray(262144) })
            val p = ByteBuffer.wrap(plain.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val plainTags = (0 until p.getShort(8).toInt()).map { p.getShort(10 + it * 12).toInt() and 65535 }
            assertFalse(270 in plainTags)
        } finally { root.deleteRecursively() }
    }
}
