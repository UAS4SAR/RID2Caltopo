package org.ncssar.rid2caltopo.video

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecordingStatusTest {
    private var now = 1_000L

    @Before fun setUp() { RecordingStatus.resetForTests(); RecordingStatus.clock = { now } }
    @After fun tearDown() { RecordingStatus.resetForTests() }

    private fun state(path: String = "alpha") = RecordingStatus.state.value.marker(path, live = true, localPlayback = false, nowMs = now).state

    @Test fun followsRecorderStartAndStop() {
        RecordingStatus.updateInputs { RecordingInputs(serverRunning = true, captureEnabled = true, storageBlocked = false, recorderReady = true, archiveWritable = true) }
        RecordingStatus.onStreamStarted("alpha")
        assertEquals(RecordingMarkerState.STARTING, state())
        RecordingStatus.onRecorderStarted("/alpha/")
        assertEquals(RecordingMarkerState.RECORDING, state())
        RecordingStatus.onRecorderStopped("alpha")
        now += RecordingMarkerPolicy.START_GRACE_MS
        assertEquals(RecordingMarkerState.NOT_RECORDING, state())
    }

    @Test fun serverRestartForgetsRecorders() {
        RecordingStatus.updateInputs { it.copy(serverRunning = true, recorderReady = true) }
        RecordingStatus.onRecorderStarted("alpha")
        RecordingStatus.onServerStarted()
        assertEquals(emptySet<String>(), RecordingStatus.state.value.recordingPaths)
        RecordingStatus.onRecorderStarted("alpha")
        RecordingStatus.updateInputs { it.copy(serverRunning = false) }
        assertEquals(emptySet<String>(), RecordingStatus.state.value.recordingPaths)
    }

    @Test fun captureOffShowsNotRecordingAtOnce() {
        RecordingStatus.updateInputs { it.copy(serverRunning = true, captureEnabled = false, recorderReady = true) }
        RecordingStatus.onStreamStarted("alpha")
        assertEquals(RecordingMarkerState.NOT_RECORDING, state())
    }
}
