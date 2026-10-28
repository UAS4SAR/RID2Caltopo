package org.ncssar.rid2caltopo.ui

import android.os.Build
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ncssar.rid2caltopo.video.mapcache.MapCacheUsage
import java.util.concurrent.atomic.AtomicInteger

/** Actual dialogs; controlled scan completion. Run only on the isolated emulator. */
@RunWith(AndroidJUnit4::class)
class MapCacheLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun requireEmulator() {
        assumeTrue(Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("mapCacheLoadingQualification") == "true")
    }
    private fun snapshot() = MapStorageSnapshot(
        MapCacheUsage(10, 20, 30, 40, 0), LocalMapStorage(50, 60, 70, true),
        10_000_000_000, 365, 200_000_000_000, 900_000_000_000, "Test archive"
    )

    @Test fun busyParentThenOnePopulatedPanel() {
        requireEmulator()
        val result = CompletableDeferred<MapStorageSnapshot>()
        val calls = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                StorageManagementDialog({}, {}, {}, loadCache = {
                    assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                    calls.incrementAndGet()
                    result.await()
                })
            }
        }
        compose.onNodeWithText("Map Cache Management...", substring = true).performClick()
        compose.waitUntil { calls.get() == 1 }
        compose.onNodeWithText("Manage Storage", substring = false).assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Archive storage", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Internal storage free:", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Scanning storage…").assertIsDisplayed()
        compose.onNodeWithText("Map Cache Management", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Max Cache Size (GB)").assertDoesNotExist()
        compose.onNodeWithText("Map Cache Management...", substring = true).assertIsNotEnabled()
        result.complete(snapshot())
        compose.waitUntil { compose.onAllNodesWithText("Map Cache Management", substring = false).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Manage Storage", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Map imagery: 10 B").assertIsDisplayed()
        compose.onNodeWithText("Max Cache Size (GB)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Max Age (days)").performScrollTo().assertIsDisplayed()
        assertEquals(1, calls.get())
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.onNodeWithText("Manage Storage", substring = false).assertIsDisplayed()
    }

    @Test fun closingDuringScanPreventsLatePanel() {
        requireEmulator()
        val result = CompletableDeferred<MapStorageSnapshot>()
        val calls = AtomicInteger()
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open) StorageManagementDialog({ open = false }, {}, {}, loadCache = {
                    calls.incrementAndGet()
                    result.await()
                })
            }
        }
        compose.onNodeWithText("Map Cache Management...", substring = true).performClick()
        compose.waitUntil { calls.get() == 1 }
        compose.onNodeWithText("Scanning storage…").assertIsDisplayed()
        compose.onNodeWithText("Close", substring = false).performClick()
        result.complete(snapshot())
        compose.waitForIdle()
        compose.onNodeWithText("Map Cache Management", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Manage Storage", substring = false).assertDoesNotExist()
    }
}
