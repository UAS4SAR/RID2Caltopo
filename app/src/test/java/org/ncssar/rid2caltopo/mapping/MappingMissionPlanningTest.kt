package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MappingMissionPlanningTest {
    private fun mission():MappingMission {
        val record=DjiCloudTestFixture.mission()
        return MappingMission(JSONObject(record.getJSONObject("feature").toString()),"incident-1")
    }
    @Test fun oneCentimeterOversizedTrialGetsVerifiedFitWithoutChangingRequestedMission() {
        for(capture in MappingCaptureProfile.approved60Trials) {
        val mission=mission();val camera=MappingResolution.profiles.first()
        val before=mission.json(camera,1.0,capture).toString()
        assertTrue(DjiMappingRoute(JSONObject(before)).photoEstimate>200)
        val suggestion=MappingMissionPlanning.withinPhotoLimit(mission,camera,1.0,capture)!!
        assertTrue(suggestion.gsdCmPerPixel>1.0)
        assertTrue(suggestion.route.photoEstimate<=200)
        assertEquals(before,mission.json(camera,1.0,capture).toString())
        assertEquals(mission.id,suggestion.route.id)
        assertEquals(capture.id,suggestion.route.mission.getJSONObject("captureProfile").getString("id"))
        assertEquals(2,suggestion.route.passes.size)
        assertEquals(suggestion.gsdCmPerPixel,suggestion.route.groundResolutionCmPerPixel,0.0)
        assertTrue(suggestion.route.kmz().isNotEmpty())
        }
    }
    @Test fun noSuggestionForAlreadyBoundedPlanInvalidInputOrGated45() {
        val mission=mission();val camera=MappingResolution.profiles.first()
        assertNull(MappingMissionPlanning.withinPhotoLimit(mission,camera,2.0,MappingCaptureProfile.nadir))
        assertNull(MappingMissionPlanning.withinPhotoLimit(mission,camera,Double.NaN,MappingCaptureProfile.nadir60))
        assertNull(MappingMissionPlanning.withinPhotoLimit(mission,camera,1.0,MappingCaptureProfile.nadir45))
    }
}
