package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MappingSurfaceParameterTest {
    private fun feature() = JSONObject().put("id", "a1").put("properties", JSONObject().put("class", "Assignment").put("title", "Test"))
        .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(JSONArray()
            .put(JSONArray(listOf(-122.0, 37.0))).put(JSONArray(listOf(-121.999, 37.0)))
            .put(JSONArray(listOf(-121.999, 37.001))).put(JSONArray(listOf(-122.0, 37.001)))
            .put(JSONArray(listOf(-122.0, 37.0))))))

    @Test fun newMissionPersistsSurfaceKnobsAndRaisedMargin() {
        val camera = MappingResolution.profiles.first { it.id == "m4td.visible.wide" }
        val plan = MappingMission(feature(), "MAP").json(camera, 2.0, MappingCaptureProfile.nadir)
        val p = plan.getJSONObject("parameters")
        assertEquals(0.2, p.getDouble("surfaceCellMeters"), 0.0)
        assertTrue(p.getBoolean("fillUnknown2DAreas"))
        val footprint = camera.sensor.widthPixels * (2.0 / 100)
        assertEquals(maxOf(10.0, 0.5 * footprint), p.getDouble("marginMeters"), 1e-9)
    }

    @Test fun oldMissionDefaults() {
        val p = JSONObject().put("desiredGroundResolutionCmPerPixel", 2.0)
        assertEquals(0.2, MappingMissionDefaults.cell(p), 0.0)
        assertTrue(MappingMissionDefaults.fillUnknown2D(p))
        assertEquals(10.0, MappingMissionDefaults.margin(p), 0.0)
        assertEquals(0.2, MappingMissionDefaults.cell(null), 0.0)
        assertTrue(MappingMissionDefaults.fillUnknown2D(null))
    }

    @Test fun storageEstimateScalesWithInverseCellSquared() {
        val camera = MappingResolution.profiles.first { it.id == "m4td.visible.wide" }
        val mission = MappingMission(feature(), "MAP")
        val standard = MappingStorageEstimate.plan(mission.json(camera, 2.0, surfaceCellMeters = 0.2), 40)
        val fine = MappingStorageEstimate.plan(mission.json(camera, 2.0, surfaceCellMeters = 0.1), 40)
        val coarse = MappingStorageEstimate.plan(mission.json(camera, 2.0, surfaceCellMeters = 0.4), 40)
        // products = 2*raster + model; only the model term depends on the cell (raster is fixed by GSD).
        val standardModel = (fine.products - standard.products) / 3.0
        assertTrue(standardModel > 0)
        assertEquals(-0.75 * standardModel, (coarse.products - standard.products).toDouble(), 3.0)
    }

    @Test fun largerEdgeOverrunIncreasesPhotoCount() {
        val camera = MappingResolution.profiles.first { it.id == "m4td.visible.wide" }
        val mission = MappingMission(feature(), "MAP")
        val tight = DjiMappingRoute(mission.json(camera, 2.0, MappingCaptureProfile.nadir, marginMeters = 10.0))
        val wide = DjiMappingRoute(mission.json(camera, 2.0, MappingCaptureProfile.nadir, marginMeters = 40.0))
        assertTrue(wide.photoEstimate > tight.photoEstimate)
        assertEquals(10.0, tight.routeEndMarginMeters, 0.0)
        assertEquals(40.0, wide.routeEndMarginMeters, 0.0)
    }

    @Test fun obliquePlanExtendsEndsBeyondMargin() {
        val camera = MappingResolution.profiles.first { it.id == "m4td.visible.wide" }
        val mission = MappingMission(feature(), "MAP")
        val brush = DjiMappingRoute(mission.json(camera, 2.0, MappingCaptureProfile.brushTrees, canopyHeightMeters = 10.0, maximumObstacleHeightMeters = 10.0, marginMeters = 10.0))
        assertTrue(brush.obliqueEndExtensionMeters > 0)
        val nadir = DjiMappingRoute(mission.json(camera, 2.0, MappingCaptureProfile.nadir, marginMeters = 10.0))
        assertEquals(0.0, nadir.obliqueEndExtensionMeters, 0.0)
        assertTrue(brush.photoEstimate > nadir.photoEstimate)
    }
}
