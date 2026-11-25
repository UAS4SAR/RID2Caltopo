package org.ncssar.rid2caltopo.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 24 2026 missed flights: safeguards that are wiring rather than pure policy. */
class RecordingSafeguardsSourceTest {
    private fun src(path: String) = File("src/main/java/org/ncssar/rid2caltopo/$path").readText()

    @Test fun finishedRecordingsAreSyncedWhateverCaptureSaysNow() {
        val service = src("app/MediaMTXService.java")
        val listener = service.substringAfter("RecordFileCompleted").substringBefore("Unit.INSTANCE;\n        });")
        assertFalse(listener.contains("GetCaptureVideoStreamsFlag()) return"))
        assertFalse(service.contains("if (!CaltopoClient.GetCaptureVideoStreamsFlag()) return Unit.INSTANCE;"))
    }

    @Test fun sessionLogRollsOverInsteadOfStopping() {
        assertTrue(SessionLogRollover.shouldRollOver(9_999_990, 20, 10_000_000, fileAttached = true))
        assertFalse(SessionLogRollover.shouldRollOver(9_999_990, 10, 10_000_000, fileAttached = true))
        assertFalse(SessionLogRollover.shouldRollOver(9_999_990, 20, 10_000_000, fileAttached = false))
        assertFalse(SessionLogRollover.shouldRollOver(0, 20_000_000, 10_000_000, fileAttached = true))
        val client = src("data/CaltopoClient.java")
        val write = client.substringAfter("private static void WriteDiagnosticLog(").substringBefore("\n    }\n")
        assertTrue(write.contains("SessionLogRollover.shouldRollOver("))
        assertTrue(client.contains("SessionLogCardSync.finishRolledOver();"))
        assertFalse(client.contains("Future bits will be tossed on the floor"))
    }

    @Test fun everyRestoreAndImportIsLogged() {
        assertEquals("Settings applied source=restore; Capture Streams unchanged old=true new=true",
            AppConfigStore.captureRestoreLogLine("restore", true, true))
        val agent = src("data/AppConfigBackupAgent.kt")
        assertTrue(agent.contains("System restore (key/value)"))
        assertTrue(agent.contains("System restore (full)"))
        assertTrue(agent.contains("System backup (\$kind) skipped"))
    }

    @Test fun recorderLinesDriveTheMarker() {
        val boot = src("data/MediaMTXBootstrap.kt")
        assertTrue(boot.contains("RecordingStatus.onRecorderStarted("))
        assertTrue(boot.contains("RecordingStatus.onRecorderStopped("))
        assertTrue(src("ui/AlertSpeechCoordinator.kt").contains("RecordingConnectWarning.start("))
        assertTrue(src("ui/MainScreen.kt").contains("RecordingPromptHost()"))
    }
}
