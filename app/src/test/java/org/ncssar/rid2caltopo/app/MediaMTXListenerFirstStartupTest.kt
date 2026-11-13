package org.ncssar.rid2caltopo.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.MediaMTXConfig

/** Field 2026-11-12: 13.1 s archive scan + 4.7 s recorder prepare held port 1935 closed. */
class MediaMTXListenerFirstStartupTest {
    private val service = File("src/main/java/org/ncssar/rid2caltopo/app/MediaMTXService.java").readText()
    private fun body(start: String, end: String) = service.substringAfter(start).substringBefore(end)

    @Test fun nothingBeforeTheListenerScansArchiveDirOrPreparesTheRecorder() {
        val start = body("private void startNativeServer()", "public static void requestRestart(")
        val beforeListener = start.substringBefore("MediaMTXNative.start(")
        val config = body("private File buildConfigAsset(", "private String loadAssetText(")
        for (blocking in listOf("prepareRecordingStart", "requireRecorderReady", "tryRequireRecorderReady",
                "getRecordingStagingDir(", "FlightStorage.snapshot(", "maintain(", "syncAll(")) {
            assertFalse("$blocking before the listener", beforeListener.contains(blocking))
            assertFalse("$blocking while writing config", config.contains(blocking))
        }
        assertTrue(beforeListener.contains("FlightStorage.recordingStartEstimate("))
        assertTrue(beforeListener.contains("recordingStagingDirWithoutPreparing("))
        val afterListener = start.substringAfter("MediaMTXNative.start(")
        assertTrue(afterListener.contains("FlightStorage.requestRecordingStartCheck("))
        assertTrue(afterListener.contains("recordingSyncExecutor.execute"))
        assertTrue(afterListener.contains("getRecordingStagingDir(appContext)"))
    }

    @Test fun storageChangesToggleRecordingThroughTheApiNotARestart() {
        val observer = body("private void observeStorageAfterSettings()", "@Override")
        assertTrue(observer.contains("recordingReconciler.updateStorageBlocked("))
        assertFalse(observer.contains("NativeControlAction.RESTART"))
    }

    @Test fun startEstimateKeepsTheReserveAllowanceAndArchiveRules() {
        val max = 10_000_000_000L
        val plenty = 50_000_000_000L
        assertNull(FlightStorage.recordingStartIssue(true, 1_000_000_000L, max, plenty, false))
        assertEquals(FlightStorageIssue.DEVICE_LOW, FlightStorage.recordingStartIssue(true, 0, max, 999_999_999L, false))
        assertNull(FlightStorage.recordingStartIssue(true, 0, max, 1_000_000_000L, false))
        assertEquals(FlightStorageIssue.ALLOWANCE, FlightStorage.recordingStartIssue(true, 9_000_000_001L, max, plenty, false))
        assertNull(FlightStorage.recordingStartIssue(true, 9_000_000_000L, max, plenty, false))
        assertEquals(FlightStorageIssue.ARCHIVE_REQUIRED, FlightStorage.recordingStartIssue(false, 0, max, plenty, false))
        assertEquals(FlightStorageIssue.ARCHIVE_WRITE, FlightStorage.recordingStartIssue(true, 0, max, plenty, true))
    }

    @Test fun configCanStartWithRecordingOffButReadyToSwitchOn() {
        val config = MediaMTXConfig.buildRuntimeConfig("rtmp: yes\npathDefaults:\n  source: publisher\n", true,
            File("/tmp/r2c-rec"), true, false)
        assertTrue(config.contains("  record: no"))
        assertFalse(config.contains("record: yes"))
        assertTrue(config.contains("recordPath: '/tmp/r2c-rec/%path/%path_%Y-%m-%d_%H-%M-%S-%f'"))
        assertTrue(config.contains("recordFormat: fmp4"))
        assertTrue(MediaMTXConfig.buildRuntimeConfig("rtmp: yes\n", true, File("/tmp/r2c-rec")).contains("record: yes"))
    }
}
