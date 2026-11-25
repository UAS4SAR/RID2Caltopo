package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the iPad's RecordingMarkerPolicyTests. */
class RecordingMarkerPolicyTest {
    private val ready = RecordingInputs(serverRunning = true, captureEnabled = true, storageBlocked = false, recorderReady = true, archiveWritable = true)

    private fun marker(recording: Boolean = false, inputs: RecordingInputs = ready, waitingMs: Long = 60_000, live: Boolean = true, local: Boolean = false) =
        RecordingMarkerPolicy.marker(live, local, recording, inputs, waitingMs)

    @Test fun hiddenWhenNotLiveOrLocalPlayback() {
        assertEquals(RecordingMarkerState.HIDDEN, marker(live = false).state)
        assertEquals(RecordingMarkerState.HIDDEN, marker(local = true).state)
    }

    @Test fun redRecWhenTheRecorderIsWriting() {
        val m = marker(recording = true)
        assertEquals(RecordingMarkerState.RECORDING, m.state)
        assertEquals("● REC", m.label)
    }

    @Test fun recorderStateWinsOverTheSetting() {
        // The marker follows the recorder, not the setting: a recorder still writing shows REC.
        assertEquals(RecordingMarkerState.RECORDING, marker(recording = true, inputs = ready.copy(captureEnabled = false)).state)
    }

    @Test fun captureOffIsNotRecordingImmediately() {
        val m = marker(inputs = ready.copy(captureEnabled = false), waitingMs = 0)
        assertEquals(RecordingMarkerState.NOT_RECORDING, m.state)
        assertEquals(NotRecordingReason.CAPTURE_OFF, m.reason)
        assertEquals("NOT RECORDING", m.label)
        assertTrue(RecordingMarkerPolicy.tapTurnsOnCapture(m))
    }

    @Test fun storageReasons() {
        assertEquals(NotRecordingReason.STORAGE_BLOCKED, marker(inputs = ready.copy(storageBlocked = true), waitingMs = 0).reason)
        assertEquals(NotRecordingReason.ARCHIVE_NOT_WRITABLE, marker(inputs = ready.copy(storageBlocked = true, archiveWritable = false), waitingMs = 0).reason)
        assertFalse(RecordingMarkerPolicy.tapTurnsOnCapture(marker(inputs = ready.copy(storageBlocked = true))))
    }

    @Test fun startingDuringGraceThenNotRecording() {
        assertEquals(RecordingMarkerState.STARTING, marker(waitingMs = 2_000).state)
        assertEquals(RecordingMarkerState.STARTING, marker(waitingMs = RecordingMarkerPolicy.START_GRACE_MS - 1).state)
        val late = marker(waitingMs = RecordingMarkerPolicy.START_GRACE_MS)
        assertEquals(RecordingMarkerState.NOT_RECORDING, late.state)
        assertEquals(NotRecordingReason.RECORDER_NOT_STARTED, late.reason)
        assertEquals(NotRecordingReason.RECORDER_NOT_READY, marker(inputs = ready.copy(recorderReady = false)).reason)
    }

    @Test fun reasonTextsNeverEmpty() {
        NotRecordingReason.entries.forEach { assertTrue(RecordingMarkerPolicy.reasonText(it).isNotBlank()) }
        assertTrue(RecordingMarkerPolicy.promptText("Alpha", NotRecordingReason.CAPTURE_OFF).startsWith("Alpha: "))
    }

    @Test fun connectWarningFiresOncePerPublishAndWaitsForGrace() {
        val policy = RecordingConnectWarningPolicy()
        val starting = RecordingMarker(RecordingMarkerState.STARTING, null)
        val off = RecordingMarker(RecordingMarkerState.NOT_RECORDING, NotRecordingReason.CAPTURE_OFF)
        assertTrue(policy.evaluate(listOf(RecordingConnectWarningPolicy.Stream("a#1", "Alpha", starting))).isEmpty())
        val warned = policy.evaluate(listOf(RecordingConnectWarningPolicy.Stream("a#1", "Alpha", off)))
        assertEquals(listOf(RecordingConnectWarningPolicy.Warning("a#1", "Alpha", NotRecordingReason.CAPTURE_OFF)), warned)
        assertTrue(policy.evaluate(listOf(RecordingConnectWarningPolicy.Stream("a#1", "Alpha", off))).isEmpty())
        // A new publish (new connection) warns again.
        assertEquals(1, policy.evaluate(listOf(RecordingConnectWarningPolicy.Stream("a#2", "Alpha", off))).size)
    }

    @Test fun connectWarningSilentWhenRecordingStartsFirst() {
        val policy = RecordingConnectWarningPolicy()
        val rec = RecordingMarker(RecordingMarkerState.RECORDING, null)
        val off = RecordingMarker(RecordingMarkerState.NOT_RECORDING, NotRecordingReason.RECORDER_NOT_STARTED)
        assertTrue(policy.evaluate(listOf(RecordingConnectWarningPolicy.Stream("a#1", "Alpha", rec))).isEmpty())
        // Later stops on the same publish are shown by the marker, not re-announced.
        assertTrue(policy.evaluate(listOf(RecordingConnectWarningPolicy.Stream("a#1", "Alpha", off))).isEmpty())
    }
}
