package org.ncssar.rid2caltopo.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.RecordingFinalizationActivity

/** Field 2026-11-23: Close 11 s into a 1.1 GB copy let Android freeze the app mid-copy. */
class RecordingQuitPolicyTest {
    @Test fun closeWaitsWhileARecordingIsFinishingOrCanStillBeOpen() {
        assertTrue(RecordingQuitPolicy.mustFinishBeforeQuit(relayRunning = false, captureEnabled = false, finalizationActive = true))
        assertTrue(RecordingQuitPolicy.mustFinishBeforeQuit(relayRunning = true, captureEnabled = true, finalizationActive = false))
        assertFalse(RecordingQuitPolicy.mustFinishBeforeQuit(relayRunning = true, captureEnabled = false, finalizationActive = false))
        assertFalse(RecordingQuitPolicy.mustFinishBeforeQuit(relayRunning = false, captureEnabled = true, finalizationActive = false))
    }

    @Test fun savingDialogWording() {
        assertEquals("Saving video…", RecordingQuitPolicy.SAVING_TITLE)
        assertEquals("Close now", RecordingQuitPolicy.CLOSE_NOW)
    }

    @Test fun finalizationActivityReportsAnyActiveLease() {
        val activity = RecordingFinalizationActivity.shared
        assertFalse(activity.isAnyActive())
        val lease = activity.begin("1sar7djmtrc4td")
        assertTrue(activity.isAnyActive())
        lease.close()
        assertFalse(activity.isAnyActive())
    }

    private val service = File("src/main/java/org/ncssar/rid2caltopo/app/MediaMTXService.java").readText()
    private val activity = File("src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt").readText()

    @Test fun confirmedCloseFinishesRecordingsBeforeQuitting() {
        val confirm = activity.substringAfter("val confirmAppExit = {").substringBefore("val closeBeforeVideoSaved")
        val gate = confirm.indexOf("NeedsToFinishRecordingsBeforeQuit()")
        val finish = confirm.indexOf("FinishRecordingsBeforeQuit(applicationContext)")
        assertTrue(gate >= 0 && finish > gate)
        // The only Quit inside the waiting branch runs from the completion callback.
        val waiting = confirm.substring(gate, confirm.indexOf("} else {", finish))
        assertTrue(waiting.substringBefore("MediaMTXService.FinishRecordingsBeforeQuit(").contains("savingVideoBeforeQuit = true"))
        assertFalse(waiting.substringBefore("MediaMTXService.FinishRecordingsBeforeQuit(").contains("quitNow("))
    }

    @Test fun serverStopAndSyncRunWhileTheServiceIsStillInTheForeground() {
        val body = service.substringAfter("private boolean finishRecordingsForQuit(").substringBefore("static void awaitRecordingFinalization()")
        assertTrue(body.contains("MediaMTXNative.stop()"))
        assertTrue(body.contains("MediaMTXRecordingSync.syncAll(appContext, null)"))
        assertTrue(body.contains("awaitRecordingFinalization()"))
        assertFalse("foreground is kept until Quit", body.contains("stopForeground"))
        assertTrue(body.contains("finishingForQuit = true"))
    }

    @Test fun relayDoesNotRestartWhileFinishingForQuit() {
        val enqueue = service.substringAfter("private void enqueueNativeControl(").substringBefore("private void drainNativeControlRequests(")
        assertTrue(enqueue.contains("finishingForQuit"))
    }

    @Test fun everyLaunchRecoversLeftoverRecordings() {
        assertTrue(activity.contains("MediaMTXService.RecoverLeftoverRecordingsAtAppStart(applicationContext)"))
        val recover = service.substringAfter("public static void RecoverLeftoverRecordingsAtAppStart(").substringBefore("private void updateForegroundText(")
        assertTrue(recover.contains("syncSettledStagedRecordings"))
        assertTrue("Close waits for the recovery too", recover.contains("RecordingFinalizationActivity.shared.begin("))
    }
}
