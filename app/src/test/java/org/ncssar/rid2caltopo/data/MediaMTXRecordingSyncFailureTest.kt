package org.ncssar.rid2caltopo.data

import android.content.ContextWrapper
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaMTXRecordingSyncFailureTest {
    @Test fun unavailableStagingDoesNotKillTheServiceShutdownWorker() {
        val context = ContextWrapper(null)
        // The host has no UI Looper/storage service: staging preparation fails.
        // Establish that failure, then exercise the same entry point used on teardown.
        assertThrows(Exception::class.java) { MediaMTXRecordingSync.getRecordingStagingDir(context) }
        MediaMTXRecordingSync.syncAll(context)
    }
}
