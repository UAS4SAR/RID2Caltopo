package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MappingMissionTest {
    private fun feature()=JSONObject("""{"type":"Feature","id":"A1","properties":{"class":"Assignment","title":"North search"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.999,39],[-120.999,39.001],[-121,39.001],[-121,39]]]}}""")

    @Test fun adjustableClearanceAndLegacyDefault() {
        val mission=MappingMission(feature(),"MAP123")
        val camera=MappingResolution.profiles.first { it.id=="m4td.visible.wide" }
        val plan=mission.json(camera,3.0,maximumObstacleHeightMeters=70.0,clearanceMeters=5.0)
        val parameters=plan.getJSONObject("parameters")
        assertEquals(5.0,parameters.getDouble("clearanceMeters"),0.0)
        DjiMappingRoute(plan)
        assertThrows(IllegalArgumentException::class.java) { mission.json(camera,3.0,maximumObstacleHeightMeters=70.0,clearanceMeters=30.0) }
        for(invalid in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY,501.0)) {
            assertThrows(IllegalArgumentException::class.java) { mission.json(camera,3.0,clearanceMeters=invalid) }
        }
        parameters.put("maximumObstacleHeightMeters",75.0);parameters.remove("clearanceMeters")
        assertThrows(IllegalArgumentException::class.java) { DjiMappingRoute(plan) }
        parameters.put("clearanceMeters",5.0);DjiMappingRoute(plan)
        parameters.put("clearanceMeters","bad")
        assertThrows(Exception::class.java) { DjiMappingRoute(plan) }
    }

    @Test fun obstacleClearanceIsSavedAndCheckedForEveryPatternWithoutChangingOverlap() {
        val mission=MappingMission(feature(),"MAP123")
        val camera=MappingResolution.profiles.first { it.id=="m4td.visible.wide" }
        for(capture in MappingCaptureProfile.profiles.filter { it.exportQualified }) {
            val clear=mission.json(camera,3.0,capture,20.0,200*0.3048)
            val parameters=clear.getJSONObject("parameters")
            assertEquals(60.96,parameters.getDouble("maximumObstacleHeightMeters"),1e-9)
            val route=DjiMappingRoute(clear)
            assertEquals(DjiMappingRoute(mission.json(camera,3.0,capture,20.0)).photoEstimate,route.photoEstimate)
            assertThrows(IllegalArgumentException::class.java) { mission.json(camera,2.0,capture,20.0,60.96) }
            parameters.put("maximumObstacleHeightMeters",route.heightMeters-14.9)
            assertThrows(IllegalArgumentException::class.java) { DjiMappingRoute(clear) }
        }
        for(value in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { mission.json(camera,3.0,maximumObstacleHeightMeters=value) }
        }
        assertFalse(mission.json(camera,3.0).getJSONObject("parameters").has("maximumObstacleHeightMeters"))
        assertTrue(MappingCaptureProfile.nadir.description.contains("Single downward-looking"))
        assertFalse(MappingCaptureProfile.nadir.description.contains("−70°"))
    }

    @Test fun preservesGeometryAndIncidentAndSeparatesCaptureProfiles() {
        val feature=feature();val mission=MappingMission(feature,"MAP123")
        val rgb=mission.json(false);val thermal=mission.json(true)
        assertEquals("MAP123",rgb.getString("incidentId"))
        assertEquals(rgb.getString("missionId"),thermal.getString("missionId"))
        assertEquals(feature.getJSONObject("geometry").toString(),rgb.getJSONObject("feature").getJSONObject("geometry").toString())
        assertEquals(80,rgb.getJSONObject("parameters").getInt("forwardOverlapPercent"))
        assertEquals(85,thermal.getJSONObject("parameters").getInt("forwardOverlapPercent"))
        assertFalse(thermal.getJSONObject("parameters").getBoolean("fastOrthophoto"))
        assertEquals("thermal",thermal.getString("profile"))
        val intake = thermal.getJSONObject("photoIntake")
        assertEquals(thermal.getString("missionId"),intake.getString("missionId"))
        assertEquals("originals",intake.getString("collection"))
        assertEquals("thermal",intake.getString("captureMode"))
        assertTrue(intake.getBoolean("preserveOriginalBytes"))
        assertEquals("r2c_active_lan_session",intake.getString("transport"))
        assertFalse(intake.has("url")); assertFalse(intake.has("token"))
    }

    @Test fun rejectsMultipartAndHolesInsteadOfFlattening() {
        val feature=feature();feature.getJSONObject("geometry").put("type","MultiPolygon")
        assertThrows(IllegalArgumentException::class.java) { MappingMission(feature,"MAP123") }
        val holes=feature();holes.getJSONObject("geometry").getJSONArray("coordinates").put(org.json.JSONArray())
        assertThrows(IllegalArgumentException::class.java) { MappingMission(holes,"MAP123") }
        assertThrows(IllegalArgumentException::class.java) { MappingMission(feature(),"") }
    }

    @Test fun desiredGroundResolutionSetsCameraSpecificHeightAndIncludesLaunchReference() {
        assertEquals(6.1,MappingResolution.gsdCmPerPixel(60.0,MappingResolution.thermal)!!,0.2)
        assertEquals(2.07,MappingResolution.gsdCmPerPixel(60.0,MappingResolution.daylightWide)!!,0.1)
        val launch=MappingLaunchLocation(39.0,-121.0,8f,System.currentTimeMillis())
        val mission=MappingMission(feature(),"MAP123",launch).json(true,12.2)
        val parameters=mission.getJSONObject("parameters")
        assertEquals(12.2,parameters.getDouble("desiredGroundResolutionCmPerPixel"),0.001)
        assertEquals(120.0,parameters.getDouble("heightMeters"),3.0)
        assertEquals(39.0,mission.getJSONObject("launchLocation").getDouble("latitude"),0.001)
    }

    @Test fun verifiedCameraChoiceIsRecordedAndUnverifiedAircraftHaveNoCameraProfiles() {
        assertEquals(listOf(MappingResolution.MATRICE_4TD), MappingResolution.profiles.map { it.aircraft }.distinct())
        assertEquals(4, MappingResolution.profilesFor(MappingResolution.MATRICE_4TD).size)
        assertTrue(MappingResolution.profilesFor("DJI Matrice 400").isEmpty())
        val tele=MappingResolution.profiles.first { it.id=="m4td.visible.tele" }
        val data=MappingMission(feature(),"MAP123").json(tele,0.5)
        assertEquals("m4td.visible.tele",data.getString("cameraProfileId"))
        assertEquals("DJI Matrice 4TD",data.getString("aircraftModel"))
        assertEquals(4096,data.getJSONObject("cameraSpecifications").getInt("widthPixels"))
    }
}
