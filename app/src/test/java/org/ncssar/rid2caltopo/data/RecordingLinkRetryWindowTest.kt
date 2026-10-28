package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLinkRetryWindowTest {
    @Test fun slowQueuedAndRunningFinalizationDoesNotExhaustLinkRetry() {
        var now = 0L
        val window = RecordingLinkRetryWindow(30_000) { now }
        val activity = RecordingFinalizationActivity()
        val queued = activity.begin("camera/1SAR7DJMN4PR")
        val candidates = arrayOf("unrelated-rid", "1sar7djmn4pr")
        // Covers the observed 61-second finalization and an additional queue wait.
        for (second in 2..96 step 2) {
            now = second * 1000L
            assertTrue(window.shouldRetry(activity.isActive(candidates)))
        }
        queued.close()
        now = 120_000
        assertTrue(window.shouldRetry(activity.isActive(candidates)))
        now = 126_000
        assertFalse(window.shouldRetry(activity.isActive(candidates)))
    }

    @Test fun noRecordingOrUnrelatedRecordingStillTimesOut() {
        var now = 0L
        val window = RecordingLinkRetryWindow(30_000) { now }
        val activity = RecordingFinalizationActivity()
        activity.begin("other-camera").use {
            now = 30_000
            assertFalse(window.shouldRetry(activity.isActive(arrayOf("this-camera"))))
        }
    }

    @Test fun overlappingFinalizationsAndDuplicateCleanupKeepAccurateStatus() {
        val activity = RecordingFinalizationActivity()
        val first = activity.begin("camera")
        val second = activity.begin("camera")
        first.close()
        first.close()
        assertTrue(activity.isActive(arrayOf("CAMERA")))
        second.close()
        assertFalse(activity.isActive(arrayOf("camera")))
    }
}
