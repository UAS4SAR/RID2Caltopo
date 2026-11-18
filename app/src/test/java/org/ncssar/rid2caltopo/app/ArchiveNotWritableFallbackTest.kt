package org.ncssar.rid2caltopo.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.MediaMTXRecordingSync
import org.ncssar.rid2caltopo.storage.OperationalStorage

/** While the archive is read-only: record and log to tablet storage, back off, copy later. */
class ArchiveNotWritableFallbackTest {
    private fun source(path: String) = File("src/main/java/org/ncssar/rid2caltopo/$path").readText()

    @Test fun settledRecordingsSkipFragmentsStillBeingWritten() {
        val dir = kotlin.io.path.createTempDirectory("staged").toFile()
        try {
            val now = 10_000_000L
            val old = File(dir, "a.mp4").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 60_000) }
            val growing = File(dir, "b.mp4").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 1_000) }
            val result = MediaMTXRecordingSync.settledRecordings(listOf(MediaMTXRecordingSync.OrphanedRecording("rc/drone", listOf(old, growing))), now)
            assertEquals(listOf(old), result.single().fragments)
            assertTrue(MediaMTXRecordingSync.settledRecordings(listOf(MediaMTXRecordingSync.OrphanedRecording("rc/x", listOf(growing))), now).isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun archivePrepareRetriesBackOffToFiveMinutes() {
        assertEquals(2_000L, OperationalStorage.prepareRetryDelayMs(0))
        assertEquals(4_000L, OperationalStorage.prepareRetryDelayMs(1))
        assertEquals(256_000L, OperationalStorage.prepareRetryDelayMs(7))
        assertEquals(300_000L, OperationalStorage.prepareRetryDelayMs(8))
        assertEquals(300_000L, OperationalStorage.prepareRetryDelayMs(50))
    }

    @Test fun flightStorageNeverLetsEstimatesOrCanWriteClearTheVerdict() {
        val storage = source("app/FlightStorage.kt")
        val estimate = storage.substringAfter("fun recordingStartEstimate(").substringBefore("val estimated = recordingStartIssue(")
        assertTrue(estimate.contains("!ArchiveWritability.writable"))
        val scan = storage.substringAfter("private fun scanOnWorker(").substringBefore("val started =")
        assertTrue(scan.contains("!ArchiveWritability.writable) return applyNotWritable(context)"))
        // Read-only failures never raise the per-check pop-up; other failures show it once.
        val failure = storage.substringAfter("} else if (ArchiveWritability.noteFailure(error, \"flight storage check\"))").substringBefore("val callback")
        assertTrue(failure.contains("applyNotWritable(context)"))
        assertTrue(failure.contains("showPressureOnce(CHECK_FAILED_MESSAGE)"))
        assertFalse(failure.contains("pressure.value = \"Storage could not be checked"))
        val notWritable = storage.substringAfter("private fun applyNotWritable(").substringBefore("@JvmStatic")
        assertTrue(notWritable.contains("FlightStorageIssue.DEVICE_LOW"))
        assertFalse(notWritable.contains("ARCHIVE_WRITE"))
    }

    @Test fun repeatingTasksBackOffWhileNotWritable() {
        assertTrue(source("video/ManagedVideoSessionRecordingCatalog.kt").contains("if (!org.ncssar.rid2caltopo.app.ArchiveWritability.writable) return@withContext lastPresence"))
        assertTrue(source("video/MapPaneOfflinePrepHelpers.kt").contains("Archive not writable; terrain download skipped until it is"))
        val operational = source("storage/OperationalStorage.kt")
        assertTrue(operational.substringAfter("fun tryRequireReady(").substringBefore("return runCatching").contains("if (!org.ncssar.rid2caltopo.app.ArchiveWritability.writable) return false"))
        assertTrue(operational.substringAfter("private fun schedulePrepareRetry(").substringBefore("fun retry(").contains("if (!org.ncssar.rid2caltopo.app.ArchiveWritability.writable) return@execute"))
        val logs = source("data/SessionLogCardSync.java")
        assertTrue(logs.substringAfter("private static void periodicBatch()").substringBefore("private static void runBatch").contains("if (!archiveWritable()) return;"))
    }

    @Test fun recordingsAndLogsFallBackToTabletStorage() {
        val sync = source("data/MediaMTXRecordingSync.kt")
        assertTrue(sync.substringAfter("fun getRecordingStagingDir(").substringBefore("fun recordingStagingDirWithoutPreparing").contains("if (!org.ncssar.rid2caltopo.app.ArchiveWritability.writable) return durable"))
        assertTrue(sync.contains("Archive not writable; recording kept in tablet storage"))
        val client = source("data/CaltopoClient.java")
        assertTrue(client.substringAfter("private static void InitArchiveDirLocked()").substringBefore("static String BuildDebugLogFilename").contains("StartDeferredSessionLog("))
        val monitor = source("app/ArchiveWritability.kt")
        assertTrue(monitor.contains("CaltopoClient.CatchUpStagedSessionLogs(context)"))
        assertTrue(monitor.contains("MediaMTXRecordingSync.syncSettledStagedRecordings(context)"))
    }
}
