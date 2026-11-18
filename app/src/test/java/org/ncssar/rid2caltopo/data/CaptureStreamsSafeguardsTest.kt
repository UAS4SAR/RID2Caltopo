package org.ncssar.rid2caltopo.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-18: Capture Streams was found off on the S11U and a search flight was not recorded. */
class CaptureStreamsSafeguardsTest {
    private val on = AppConfig.newBuilder().setMinDistanceFeet(5).setCaptureVideoStreams(true).build()
    private val off = AppConfig.newBuilder().setMinDistanceFeet(5).setCaptureVideoStreams(false).build()
    private val missing = AppConfig.newBuilder().setMinDistanceFeet(5).build()

    @Test fun missingValueLoadsAsOnAndAnExplicitOffSurvivesARoundTrip() {
        assertFalse(missing.hasCaptureVideoStreams())
        assertTrue(SettingsBackupPolicy.captureStreamsEnabled(missing))
        assertTrue(SettingsBackupPolicy.captureStreamsEnabled(AppConfig.getDefaultInstance()))
        val reparsed = AppConfig.parseFrom(off.toByteArray())
        assertTrue(reparsed.hasCaptureVideoStreams())
        assertFalse(SettingsBackupPolicy.captureStreamsEnabled(reparsed))
    }

    @Test fun restoreOrImportNeverTurnsCaptureOff() {
        val result = SettingsBackupPolicy.restoreWithNotice(on, off)
        assertTrue(result.captureKeptOn)
        assertTrue(SettingsBackupPolicy.captureStreamsEnabled(result.config))
        val fromMissing = SettingsBackupPolicy.restoreWithNotice(missing, off)
        assertTrue(fromMissing.captureKeptOn)
        assertTrue(SettingsBackupPolicy.captureStreamsEnabled(fromMissing.config))
    }

    @Test fun restoreCanTurnCaptureOnAndLeavesAnExistingOffAlone() {
        val turnedOn = SettingsBackupPolicy.restoreWithNotice(off, on)
        assertFalse(turnedOn.captureKeptOn)
        assertTrue(SettingsBackupPolicy.captureStreamsEnabled(turnedOn.config))
        val stillOff = SettingsBackupPolicy.restoreWithNotice(off, off)
        assertFalse(stillOff.captureKeptOn)
        assertFalse(SettingsBackupPolicy.captureStreamsEnabled(stillOff.config))
        // A backup without the field means ON.
        assertTrue(SettingsBackupPolicy.captureStreamsEnabled(SettingsBackupPolicy.restore(off, missing)))
    }

    @Test fun settingsOnlyKeepsFieldPresence() {
        assertFalse(SettingsBackupPolicy.settingsOnly(missing).hasCaptureVideoStreams())
        assertTrue(SettingsBackupPolicy.settingsOnly(off).hasCaptureVideoStreams())
    }

    @Test fun backupAgentNeverBacksUpTheBlankDefault() {
        val dir = kotlin.io.path.createTempDirectory("backup").toFile()
        try {
            assertNull(AppConfigBackupAgent.snapshotOf(File(dir, "missing.pb")))
            val empty = File(dir, "empty.pb").apply { writeBytes(ByteArray(0)) }
            assertNull(AppConfigBackupAgent.snapshotOf(empty))
            val defaults = File(dir, "default.pb").apply { writeBytes(AppConfig.getDefaultInstance().toByteArray()) }
            assertNull(AppConfigBackupAgent.snapshotOf(defaults))
            val saved = File(dir, "saved.pb").apply { writeBytes(on.toByteArray()) }
            val bytes = AppConfigBackupAgent.snapshotOf(saved)!!
            assertTrue(AppConfig.parseFrom(bytes).captureVideoStreams)
        } finally { dir.deleteRecursively() }
    }

    @Test fun noticeNamesTheSource() {
        assertEquals(
            "Capture Streams stayed ON. The settings restore would have turned stream capture off. You can still turn it off in Settings.",
            CaptureStreamsNotice.message("restore"),
        )
    }

    private fun source(path: String) = File("src/main/java/org/ncssar/rid2caltopo/$path").readText()

    @Test fun everyChangeIsLoggedWithOldNewAndSource() {
        val client = source("data/CaltopoClient.java")
        val setter = client.substringAfter("public static void SetCaptureVideoStreamsFlag(boolean flag, @NonNull String source)").substringBefore("public static boolean GetCaptureVideoStreamsFlag")
        assertTrue(setter.contains("\"Capture Streams changed old=\" + old + \" new=\" + flag + \" source=\" + source"))
        assertTrue(client.substringAfter("public static void ResetPersistedClientState()").substringBefore("NotifySettingsChanged();").contains("CAPTURE_SOURCE_RESET"))
        val store = source("data/AppConfigStore.kt")
        assertTrue(store.contains("Capture Streams loaded value="))
        assertTrue(store.contains("CaltopoClient.CAPTURE_SOURCE_IMPORT"))
        assertTrue(store.contains("CaltopoClient.CAPTURE_SOURCE_RESTORE"))
        assertTrue(source("ui/CaltopoSettingsViewModel.kt").contains("SetCaptureVideoStreamsFlag(_captureIncomingVideo.value, CaltopoClient.CAPTURE_SOURCE_SETTINGS)"))
        assertTrue(source("video/NotCapturingStreams.kt").contains("CaltopoClient.CAPTURE_SOURCE_PANEL"))
    }

    @Test fun mediaServerLogsTheSettingAtEveryStartAndCaptureChangesNeverRestartVideo() {
        val service = source("app/MediaMTXService.java")
        val start = service.substringAfter("private void startNativeServer()").substringBefore("MediaMTXNative.start(")
        assertTrue(start.contains("Capture Streams setting at media server start: ON"))
        assertTrue(start.contains("Capture Streams setting at media server start: OFF"))
        val listener = service.substringAfter("private final Runnable captureStreamsListener").substringBefore("};")
        assertTrue(listener.contains("recordingReconciler.updateCaptureEnabled(enabled)"))
        assertFalse(listener.contains("requestRestart"))
        val save = source("ui/CaltopoSettingsViewModel.kt")
        val restart = save.substringAfter("val restartMediaMtx =").substringBefore("\n")
        assertFalse(restart.contains("Capture"))
        // Storage changes only switch recording; they never restart the server.
        val storage = service.substringAfter("private void observeStorageAfterSettings()").substringBefore("@Override")
        assertTrue(storage.contains("recordingReconciler.updateStorageBlocked(blocked)"))
        assertFalse(storage.contains("requestRestart"))
        assertFalse(storage.contains("enqueueNativeControl"))
    }

    @Test fun turningCaptureOffInSettingsAsksFirst() {
        val screen = source("ui/CaltopoSettingsScreen.kt")
        val toggle = screen.substringAfter("label = \"Capture Streams\"").substringBefore("LabeledSwitch(")
        assertTrue(toggle.contains("if (enabled) settingsViewModel.onCaptureIncomingVideoChanged(true) else confirmCaptureOff = true"))
        assertTrue(toggle.contains("settingsViewModel.onCaptureIncomingVideoChanged(false)"))
    }
}
