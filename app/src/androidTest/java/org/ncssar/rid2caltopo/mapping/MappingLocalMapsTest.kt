package org.ncssar.rid2caltopo.mapping

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MappingLocalMapsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun preparedFlightIsNamedAndReachableWithoutIncidentFilterAndCreationReturnsToSameHome() {
        val id=InstrumentationRegistry.getArguments().getString("preparedMission");assumeTrue(id!=null)
        compose.runOnUiThread{compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,"mapping-missions/$id/mission.json");val before=file.readText();val record=JSONObject(before)
        val assignment=MappingMission(record.getJSONObject("feature"),record.getString("incidentId"))
        var creating by mutableStateOf<MappingMission?>(null)
        compose.setContent{MaterialTheme{
            if(creating!=null)MappingMissionDialog(creating!!,{}, {creating=null})
            else MappingLocalLayerDialog(onView={},onDismiss={},assignments=listOf(assignment),onCreate={creating=it})
        }}
        compose.waitUntil(15000){compose.onAllNodesWithTag("mapping-entry-flight:$id").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("mapping-local-layer-list").performScrollToNode(hasTestTag("mapping-entry-flight:$id"))
        compose.onNodeWithTag("mapping-entry-flight:$id").onChildren().filterToOne(hasText("Open mission")).performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("mapping-connect-prepared").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(record.getString("title")).assertIsDisplayed()
        compose.onNodeWithText("Planned: 179 photos · 8.5 min route time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mapping-connect-prepared").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to local maps").performClick()
        compose.onNodeWithTag("mapping-local-layer-list").performScrollToNode(hasTestTag("mapping-new-flight"))
        compose.onNodeWithTag("mapping-new-flight").performClick()
        compose.onNodeWithText(assignment.title).performClick()
        compose.onNodeWithText("Define Map Mission").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Local maps").assertIsDisplayed()
        assertEquals(before,file.readText())
    }
}
