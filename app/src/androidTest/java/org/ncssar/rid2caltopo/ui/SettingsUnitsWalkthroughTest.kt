package org.ncssar.rid2caltopo.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.DisplayUnits
import org.ncssar.rid2caltopo.data.Units
import java.io.File

/** Run only on the dedicated empty units-qualification emulator. Actual Settings UI; no terms acceptance. */
@RunWith(AndroidJUnit4::class)
class SettingsUnitsWalkthroughTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun shot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "units-walkthrough").apply { mkdirs() }
        compose.onNode(hasScrollAction()).captureToImage().asAndroidBitmap().useBitmap { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
    private fun field(label: String) = compose.onNodeWithContentDescription(label)
    private fun click(label: String) { compose.onNodeWithText(label, substring = false).performScrollTo().performClick() }
    @Test fun visualConversionsSaveCancelAndReopen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unitsUiQualification") == "true")
        compose.runOnUiThread {
            Units.save(DisplayUnits.IMPERIAL)
            CaltopoClient.setMinDistanceInFeet(100)
            CaltopoClient.SetBridgeCheckDistanceFeet(20)
            CaltopoClient.SetProximityAlertSpacingFeet(100)
        }
        var showing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (showing) CaltopoSettingsScreen(onDismiss = { showing = false }, onShowDeveloperTools = {}, onSelectIncident = {})
                else Button(onClick = { showing = true }) { Text("Open Settings") }
            }
        }
        compose.onNodeWithText("Imperial").assertIsSelected()
        shot("android-imperial-selector")
        field("Min Dist (ft)").performScrollTo().assertTextContains("100")
        click("Metric")
        field("Min Dist (m)").performScrollTo().assertTextContains("30.48")
        compose.onNodeWithText("Minimum Location Accuracy: 30.0 m").performScrollTo().assertIsDisplayed()
        field("Bridge Check Distance (m)").performScrollTo().assertTextContains("6.1")
        field("Proximity Alert Spacing (m)").performScrollTo().assertTextContains("30.48")
        shot("android-metric-conversions")
        field("Min Dist (m)").performScrollTo().performTextReplacement("60.96")
        shot("android-metric-draft")
        click("Cancel")
        compose.runOnIdle {
            assertEquals(DisplayUnits.IMPERIAL, Units.current)
            assertEquals(100L, CaltopoClient.GetMinDistanceInFeet())
        }
        compose.onNodeWithText("Open Settings").performClick()
        compose.onNodeWithText("Imperial").assertIsSelected()
        field("Min Dist (ft)").performScrollTo().assertTextContains("100")
        click("Metric")
        click("Save")
        compose.runOnIdle {
            assertEquals(DisplayUnits.METRIC, Units.current)
            assertEquals(100L, CaltopoClient.GetMinDistanceInFeet())
        }
        compose.onNodeWithText("Open Settings").performClick()
        compose.onNodeWithText("Metric").assertIsSelected()
        field("Min Dist (m)").performScrollTo().performTextReplacement("60.96")
        click("Imperial")
        field("Min Dist (ft)").performScrollTo().assertTextContains("200")
        click("Metric")
        field("Min Dist (m)").performScrollTo().assertTextContains("60.96")
        click("Save")
        compose.runOnIdle {
            assertEquals(DisplayUnits.METRIC, Units.current)
            assertEquals(200L, CaltopoClient.GetMinDistanceInFeet())
        }
        compose.onNodeWithText("Open Settings").performClick()
        click("Imperial")
        field("Min Dist (ft)").performScrollTo().assertTextContains("200")
        field("Min Dist (ft)").performTextReplacement("300")
        click("Cancel")
        compose.runOnIdle {
            assertEquals(DisplayUnits.METRIC, Units.current)
            assertEquals(200L, CaltopoClient.GetMinDistanceInFeet())
        }
        // Reopening is a new Settings presentation and must retain persisted values.
        compose.onNodeWithText("Open Settings").performClick()
        compose.onNodeWithText("Metric").assertIsSelected()
        field("Min Dist (m)").performScrollTo().assertTextContains("60.96")
        shot("android-metric-saved-reopened")
    }
    /** Invoked in a separate instrumentation process after visualConversionsSaveCancelAndReopen. */
    @Test fun persistedSaveSurvivesProcessRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unitsPersistenceQualification") == "true")
        compose.runOnIdle {
            assertEquals(DisplayUnits.METRIC, Units.current)
            assertEquals(200L, CaltopoClient.GetMinDistanceInFeet())
        }
        compose.setContent {
            MaterialTheme { CaltopoSettingsScreen(onDismiss = {}, onShowDeveloperTools = {}, onSelectIncident = {}) }
        }
        compose.onNodeWithText("Metric").assertIsSelected()
        field("Min Dist (m)").performScrollTo().assertTextContains("60.96")
        shot("android-after-process-restart")
    }
}
