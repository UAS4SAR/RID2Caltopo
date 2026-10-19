package org.ncssar.rid2caltopo.mapping

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class MappingFlightDeletionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun confirmationCancelPreservesFlightAndOnlyExplicitConfirmationDeletesOriginals() {
        val flight = MappingFlightStatus(UUID.randomUUID().toString(), "Deletion test", "2026-10-13T12:00:00Z", 12, 1024, 0, emptyList(), "Processing stopped")
        val requests = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { MappingFlightsContent(listOf(flight), flight, false, null,
            {}, {}, {}, {}, {}, { _, _ -> }, onDelete = { _, originals -> requests.add(originals) }) } }
        compose.onNodeWithTag("mapping-delete-flight").performClick()
        compose.onNodeWithText("Delete entire saved flight?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("mapping-delete-results").performClick()
        compose.onNodeWithTag("mapping-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(listOf(false), requests) }
        compose.onNodeWithTag("mapping-delete-flight").performClick()
        compose.onNodeWithText("Delete flight and original photos").performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), requests) }
    }
    @Test fun allResultVersionsAreAccessibleWithinTheFlight() {
        val flight = MappingFlightStatus(UUID.randomUUID().toString(), "Grouped flight", "2026-10-13T12:00:00Z", 12, 1024, 0, emptyList(), "Map ready")
        val results = List(2) { index -> MappingLayer(UUID.randomUUID().toString(), "test", "Version ${index + 1}", "http://localhost/{Z}/{X}/{Y}.png", 18, 21) }
        var inspected: String? = null
        compose.setContent { MaterialTheme { MappingFlightsContent(listOf(flight), flight, false, null,
            {}, {}, {}, {}, {}, { _, _ -> }, results = results, onInspectResult = { inspected = it.id }) } }
        compose.onNodeWithText("Flight plan").assertExists()
        compose.onNodeWithText("Captured images").assertExists()
        results.forEach { result ->
            compose.onNodeWithTag("mapping-result-${result.id}").performScrollTo()
            compose.onNodeWithTag("mapping-result-${result.id}").onChildren()
                .filterToOne(hasText("Inspect / share result · 2D and 3D", substring = false)).performClick()
            compose.runOnIdle { assertEquals(result.id, inspected) }
        }
    }
    @Test fun activeReconstructionDisablesBothDeleteActions() {
        val flight = MappingFlightStatus(UUID.randomUUID().toString(), "Active test", "2026-10-13T12:00:00Z", 12, 1024, 0, emptyList(), "Processing", active = true)
        compose.setContent { MaterialTheme { MappingFlightsContent(listOf(flight), flight, false, null,
            {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithTag("mapping-delete-results").assertIsNotEnabled()
        compose.onNodeWithTag("mapping-delete-flight").assertIsNotEnabled()
    }
    @Test fun microSdResultsThenEntireFlightDeletionPreservesOtherMission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val card = MappingVolume.card(context) ?: error("A microSD card is required")
        val id = UUID.randomUUID().toString(); val layerId = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString(); val workId = UUID.randomUUID().toString()
        val root = MappingVolume.directory(context, "mapping-missions", id, true)
        val otherRoot = MappingVolume.directory(context, "mapping-missions", other, true)
        val layer = MappingVolume.directory(context, "mapping-layers", layerId, true)
        val work = MappingVolume.directory(context, "mapping-processing", workId, true)
        val preferences = context.getSharedPreferences("mapping_layers", 0)
        try {
            preferences.edit().putString(id, org.json.JSONArray().put(JSONObject().put("layerId", layerId)).toString()).commit()
            listOf(root to id, otherRoot to other).forEach { (dir, key) ->
                File(dir, "mission.json").writeText(JSONObject().put("missionId", key).toString())
                File(dir, "originals/test.jpg").writeText("test original")
            }
            File(layer, "layer.json").writeText(JSONObject().put("missionId", id).toString())
            File(layer, "share.zip").writeBytes(ByteArray(1024))
            File(work, "work-owner.json").writeText(JSONObject().put("missionId", id).toString())
            MappingFlightDeletion.delete(context, id, false)
            assertEquals("test original", File(root, "originals/test.jpg").readText())
            assertFalse(layer.exists()); assertFalse(File(card, "mapping-layers/$layerId").exists())
            assertEquals(0, org.json.JSONArray(preferences.getString(id, "[]")).length())
            assertFalse(work.exists()); assertFalse(File(card, "mapping-processing/$workId").exists())
            assertFalse(JSONObject(File(root, "processing-status.json").readText()).has("layerId"))
            MappingFlightDeletion.delete(context, id, true)
            assertFalse(root.exists()); assertFalse(File(card, "mapping-missions/$id").exists())
            assertEquals("test original", File(otherRoot, "originals/test.jpg").readText())
        } finally {
            preferences.edit().remove(id).commit()
            listOf(root, otherRoot, layer, work).forEach { alias ->
                MappingFlightDeletion.removeTree(alias)
                MappingFlightDeletion.removeTree(File(card, "${alias.parentFile!!.name}/${alias.name}"))
            }
        }
    }
}
