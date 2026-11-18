package org.ncssar.rid2caltopo.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field 2026-11-18: Compose read MediaMTXStatus snapshot state created on another thread and crashed. */
class MediaMTXStatusTest {
    @Test fun statusIsOneStateFlowValueWrittenFromAnyThread() {
        val writer = Thread { MediaMTXStatus.onServerStarted("v1.2.3") }
        writer.start(); writer.join()
        assertTrue(MediaMTXStatus.state.value.isServerRunning)
        assertEquals("v1.2.3", MediaMTXStatus.version)
        assertEquals("", MediaMTXStatus.serverExitReason)
        MediaMTXStatus.onServerExited("failed to start")
        val exited = MediaMTXStatus.state.value
        assertFalse(exited.isServerRunning)
        assertEquals("failed to start", exited.serverExitReason)
        assertEquals("v1.2.3", exited.version)
    }

    @Test fun noComposeSnapshotStateAndTheUiCollectsTheFlow() {
        val status = File("src/main/java/org/ncssar/rid2caltopo/data/MediaMTXStatus.kt").readText()
        assertFalse(status.contains("mutableStateOf"))
        val screen = File("src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt").readText()
        assertTrue(screen.contains("MediaMTXStatus.state.collectAsState()"))
        assertFalse(screen.contains("MediaMTXStatus.isServerRunning"))
    }
}
