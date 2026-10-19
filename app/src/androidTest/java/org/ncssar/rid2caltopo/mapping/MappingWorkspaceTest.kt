package org.ncssar.rid2caltopo.mapping

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** All inputs synthetic; no aircraft connection, real reconstruction or original inventory edits. */
@RunWith(AndroidJUnit4::class)
class MappingWorkspaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun mission() = MappingMission(JSONObject("""{"id":"workspace-ui","properties":{"class":"Assignment","title":"Workspace control"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}"""), "synthetic-workspace")
    private fun secure() = compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }

    @Test fun edgeControlPersistsAndCanRestoreFullExtent() {
        secure()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val layer=MappingLayer(UUID.randomUUID().toString(),"synthetic-workspace","Boundary control","http://localhost/{Z}/{X}/{Y}.png",18,21,
            bounds=listOf(-121.01,38.99,-120.99,39.01),assignmentBoundary=listOf(listOf(-121.0,39.0),listOf(-120.9995,39.0),listOf(-120.9995,39.0005),listOf(-121.0,39.0005)))
        try {
            compose.setContent { MaterialTheme { MappingLayerInspector(layer,{},{}) } }
            compose.waitUntil(5000){compose.onAllNodesWithTag("mapping-soft-edges").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("mapping-soft-edges").performScrollTo().assertIsOn().performClick().assertIsOff()
            compose.runOnIdle {assertFalse(MappingBoundary.soft(context,layer.id));assertEquals(layer.bounds,layer.displayBounds(context))}
            compose.onNodeWithText("Full captured extent").assertExists()
            compose.onNodeWithTag("mapping-soft-edges").performClick().assertIsOn()
            compose.runOnIdle {assertTrue(MappingBoundary.soft(context,layer.id));assertNotEquals(layer.bounds,layer.displayBounds(context))}
            compose.onNodeWithText("Target region · 5 m outer fade").assertExists()
        } finally {context.getSharedPreferences("mapping_edges",0).edit().remove(layer.id).commit()}
    }

    @Test fun activeJobSurvivesRestorationBackAndPlannerRemountUntilExplicitCancel() {
        secure()
        val mission = mission()
        var job by mutableStateOf<MappingProcessingState?>(null)
        var cancelled = 0
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { MappingWorkspaceContent(mission, job, {}, onCancel = { cancelled++; job = job?.copy(active = false, stage = "Processing cancelled; originals retained") }) } }
        compose.onNodeWithContentDescription("Choose Capture pattern").performScrollTo().performClick()
        compose.onNodeWithText(MappingCaptureProfile.nadir60.label).performClick()
        compose.onNodeWithTag("mapping-target-gsd").performScrollTo().performTextReplacement("2.4")
        compose.runOnIdle { job = MappingProcessingState(mission.id, "Workspace control", stage = "Matching photos", progress = .35, remainingSeconds = 120, surfacePreviewId = UUID.randomUUID().toString()) }
        compose.onNodeWithTag("mapping-job").assertIsDisplayed()
        compose.onNodeWithTag("mapping-http-connect").assertDoesNotExist()
        compose.onNodeWithText("View 3D surface").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithText("Matching photos").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("mapping-job").assertIsDisplayed()
        compose.onNodeWithText("Cancel processing").performClick()
        compose.runOnIdle { assertEquals(1, cancelled); job = null }
        compose.onNodeWithTag("mapping-target-gsd").performScrollTo().assertTextContains("2.4")
        compose.onNodeWithText(MappingCaptureProfile.nadir60.label).assertExists()
    }

    @Test fun verticalFlightListReachesOlderFlightsAndDetailShowsExplicitPhotoChoices() {
        secure()
        val flights = (0..19).map { index -> MappingFlightStatus(UUID.randomUUID().toString(), "Trial", "2026-10-12T00:${"%02d".format(index)}:00Z",
            170, 970649600L, 0, (1..144).map { "%064x".format(it) }, "Processing cancelled; originals retained",
            storage=MappingStorageUsage(originals=970649600,controllerReturns=800000000,maps2D=60000000,models3D=80000000,exports=90000000,working=20000000,metadata=40000)) }
        var selected by mutableStateOf<MappingFlightStatus?>(null)
        var chosen: Boolean? = null
        compose.setContent { MaterialTheme { MappingFlightsContent(flights, selected, false, null,
            onSelect = { id -> selected = flights.firstOrNull { it.id == id } }, onDismiss = {}, onInspect = {}, onView = {}, onConnect = {},
            onProcess = { _, completed -> chosen = completed }) } }
        val oldest = flights.last()
        compose.onNodeWithTag("mapping-flight-list").performScrollToNode(hasTestTag("flight-${oldest.id}"))
        compose.onNodeWithTag("storage-${oldest.id}",useUnmergedTree=true).assertTextContains(oldest.storage!!.totalLabel,substring=true)
        compose.onNodeWithTag("flight-${oldest.id}").performClick()
        compose.onNodeWithTag("mapping-storage-total").assertTextContains(oldest.storage!!.totalLabel,substring=true)
        compose.onNodeWithText("2D map tiles: ${MappingStorageUsage.format(oldest.storage!!.maps2D)}").assertExists()
        compose.onNodeWithText("3D models and surface data: ${MappingStorageUsage.format(oldest.storage!!.models3D)}").assertExists()
        compose.onNodeWithText("Completed transfer selection: 144 originals").assertExists()
        compose.onNodeWithText("Reconstruct completed set · 144 originals").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, chosen) }
        compose.onNodeWithText("Reconstruct all received · 170 originals").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, chosen) }
        compose.onNodeWithText("Back to flights").performClick()
        compose.onNodeWithTag("mapping-flight-list").assertIsDisplayed()
    }
    @Test fun retainedFlightStatusKeepsCompletedSelectionSeparateAndOldResultAvailable() {
        val arguments=InstrumentationRegistry.getArguments()
        val trial=arguments.getString("trialMission");val old=arguments.getString("forecastMission")
        assumeTrue(trial!=null&&old!=null)
        val files=InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        for(id in listOf(trial!!,old!!)) {
            val root=File(files,"mapping-missions/$id")
            val missionFile=File(root,"mission.json");val statusFile=File(root,"processing-status.json")
            val beforeMission=missionFile.readText();val beforeStatus=statusFile.readText()
            val beforeInventory=File(root,"inventory").listFiles().orEmpty().associate{it.name to it.readText()}
            val status=MappingFlightRepository.snapshot(root,JSONObject(beforeMission))
            if(id==trial) {
                assertEquals(170,status.photoCount);assertEquals(144,status.completedDigests.size);assertEquals(26,status.extraCount)
                assertFalse(status.active);assertNotNull(status.storage)
                assertTrue(status.storage!!.total>=status.storage!!.originals+status.storage!!.controllerReturns)
                android.util.Log.i("MappingStorageTest","trial storage: ${status.storage}")
            } else {
                assertEquals(66,status.photoCount);assertEquals("Map ready",status.stage);assertNotNull(status.layerId)
                assertTrue(status.storage!!.maps2D>0);assertTrue(status.storage!!.models3D>0);assertTrue(status.storage!!.exports>0)
                android.util.Log.i("MappingStorageTest","previous flight storage: ${status.storage}")
            }
            assertEquals(beforeMission,missionFile.readText());assertEquals(beforeStatus,statusFile.readText())
            assertEquals(beforeInventory,File(root,"inventory").listFiles().orEmpty().associate{it.name to it.readText()})
        }
    }

    @Test fun tabletAllocatedBlocksCountActualLinksOrCopiesCorrectly() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"mapping-storage-control-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            val id=UUID.randomUUID().toString();val mission=File(root,"mapping-missions/$id").apply{mkdirs()}
            File(mission,"mission.json").writeText(JSONObject().put("missionId",id).toString())
            val photo=File(mission,"originals/control.jpg").apply{parentFile!!.mkdirs();writeBytes(ByteArray(5000))}
            val returned=File(mission,"cloud/storage/objects/control").apply{parentFile!!.mkdirs()}
            val linked=try {
                android.system.Os.link(photo.absolutePath,returned.absolutePath);true
            } catch(error:android.system.ErrnoException) {
                // This tablet's app sandbox denies hard links; production intake falls back to a full copy.
                if(error.errno !in listOf(android.system.OsConstants.EACCES,android.system.OsConstants.EPERM,
                    android.system.OsConstants.EOPNOTSUPP))throw error
                photo.copyTo(returned);false
            }
            var usage=MappingMissionStorage.index(root).mission(id)
            assertEquals(android.system.Os.stat(photo.absolutePath).st_blocks*512,usage.originals)
            val firstCopy=if(linked)0L else android.system.Os.stat(returned.absolutePath).st_blocks*512
            assertEquals(firstCopy,usage.controllerReturns)
            val copy=File(returned.parentFile,"copy");photo.copyTo(copy)
            usage=MappingMissionStorage.index(root).mission(id)
            assertEquals(firstCopy+android.system.Os.stat(copy.absolutePath).st_blocks*512,usage.controllerReturns)
        } finally { root.deleteRecursively() }
    }

}
