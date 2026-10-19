package org.ncssar.rid2caltopo.mapping

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI controls only: no HTTP click, saved flight, credential display or aircraft start. */
@RunWith(AndroidJUnit4::class)
class MappingMissionDialogTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun mission():MappingMission {
        val feature=JSONObject("""{"id":"synthetic-ui-assignment","properties":{"class":"Assignment","title":"HTTP availability control"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.998,39],[-120.998,39.002],[-121,39.002],[-121,39]]]}}""")
        return MappingMission(feature,"synthetic-ui-incident")
    }
    @Test fun microSdEnablesLargerFlightWithoutCoarseningSampling() {
        assumeTrue(DjiCloudCredentialStore.configured(compose.activity))
        assumeTrue(MappingVolume.card(compose.activity)!=null)
        compose.setContent{MaterialTheme{MappingMissionDialog(mission(),{}, {})}}
        compose.onNodeWithText(MappingCaptureProfile.brushTrees.label).assertExists()
        compose.onNodeWithTag("mapping-http-connect").assertIsEnabled()
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir.label).performClick()
        compose.onNodeWithTag("mapping-target-gsd").performScrollTo().performTextReplacement("1.0")
        compose.onNodeWithTag("mapping-http-connect").assertIsEnabled()
        compose.onNodeWithTag("mapping-target-gsd").assertTextContains("1.0")
        compose.onNodeWithTag("mapping-fit-photo-limit").assertDoesNotExist()
    }
    @Test fun HTTPRecoveryPreserves60TrialAnd45Gate() {
        assumeTrue(DjiCloudCredentialStore.configured(compose.activity))
        compose.runOnUiThread{compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        val mission=mission()
        compose.setContent{MaterialTheme{MappingMissionDialog(mission,{}, {})}}
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir60.label).performClick()
        assumeTrue(MappingVolume.card(compose.activity)!=null)
        compose.onNodeWithTag("mapping-http-connect").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir45.label).performClick()
        compose.onNodeWithTag("mapping-http-connect").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("mapping-fit-photo-limit").assertDoesNotExist()
        compose.onNodeWithText("−45° is preview-only; choose nadir or the approved −60° trial.").assertIsDisplayed()
    }
    @Test fun lowerOverlapOptionUpdatesEstimatesAndRetainsHttpAndPilotReview() {
        assumeTrue(DjiCloudCredentialStore.configured(compose.activity))
        compose.runOnUiThread{compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        val feature=JSONObject("""{"id":"synthetic-small-ui","properties":{"class":"Assignment","title":"Overlap comparison"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}""")
        val mission=MappingMission(feature,"synthetic-ui-incident")
        val camera=MappingResolution.profiles.first()
        val dense=DjiMappingRoute(mission.json(camera,2.0,MappingCaptureProfile.nadir60))
        val standard=DjiMappingRoute(mission.json(camera,2.0,MappingCaptureProfile.nadir60Standard))
        compose.setContent{MaterialTheme{MappingMissionDialog(mission,{}, {})}}
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir60Standard.label).performClick()
        compose.onNodeWithTag("mapping-route-estimate").performScrollTo().assertTextContains("${standard.photoEstimate} originals",substring=true)
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir.label).performClick()
        val openGround=DjiMappingRoute(mission.json(camera,2.0,MappingCaptureProfile.nadir))
        compose.onNodeWithTag("mapping-route-estimate").performScrollTo().assertTextContains("${openGround.photoEstimate} originals",substring=true)
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir60.label).performClick()
        compose.onNodeWithTag("mapping-route-estimate").assertTextContains("${dense.photoEstimate} originals",substring=true)
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir60Standard.label).performClick()
        compose.onNodeWithTag("mapping-route-estimate").performScrollTo().assertTextContains("${standard.photoEstimate} originals",substring=true)
        compose.onNodeWithTag("mapping-route-estimate").assertTextContains(java.lang.String.format(java.util.Locale.getDefault(),"%.1f",standard.durationEstimateSeconds/60.0)+" min",substring=true)
        compose.onNodeWithTag("mapping-http-connect").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Controlled −60° trial: the pilot reviews the loaded route and clearance in Pilot 2, then starts the flight there. Confirm 12 MP originals and both capture passes.").assertExists()
    }
    @Test fun retainedAssignmentForecastReportsCountsWithoutTouchingMission() {
        val id=InstrumentationRegistry.getArguments().getString("forecastMission")
        assumeTrue(id!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,"mapping-missions/$id/mission.json");val before=file.readText()
        val old=JSONObject(before)
        val mission=MappingMission(old.getJSONObject("feature"),old.getString("incidentId"))
        val camera=MappingResolution.profiles.first()
        for(capture in listOf(MappingCaptureProfile.nadir,MappingCaptureProfile.nadir60,MappingCaptureProfile.nadir60Standard)) {
            val fit=MappingMissionPlanning.withinPhotoLimit(mission,camera,1.0,capture)
            for(gsd in listOf(1.0,2.0,2.4)){
                val route=DjiMappingRoute(mission.json(camera,gsd,capture))
                android.util.Log.i("MappingHttpPlanTest","${capture.id}: $gsd cm/px -> ${route.photoEstimate} photos, ${route.durationEstimateSeconds} s at ${route.heightMeters} m; suggested ${fit?.gsdCmPerPixel} cm/px / ${fit?.route?.photoEstimate} photos")
            }
        }
        assertEquals(before,file.readText())
    }
}
