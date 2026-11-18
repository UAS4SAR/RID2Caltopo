package org.ncssar.rid2caltopo.storage

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field 2026-11-18: an exception on "mediamtx-recording-sync" killed the app on every launch. */
class StorageJobGuardTest {
    private val original = StorageJobGuard.report
    @After fun restore() { StorageJobGuard.report = original }

    @Test fun aFailingStorageJobIsReportedAndTheLaneAndProcessKeepRunning() {
        val reported = AtomicReference<String>()
        StorageJobGuard.report = { tag, what, error -> reported.set("$tag|$what|${error.message}") }
        val uncaught = AtomicReference<Throwable>()
        val lane = Executors.newSingleThreadExecutor { r -> Thread(r, "guard-test").apply { setUncaughtExceptionHandler { _, e -> uncaught.set(e) } } }
        try {
            lane.execute(StorageJobGuard.guard("Test", "Recorder preparation") {
                throw ArchiveDirAvailability.previousStorageMissing("unfinished-recordings", "/storage/4A21-0000/x")
            })
            val next = CountDownLatch(1)
            lane.execute { next.countDown() }
            assertTrue("the lane runs the next job", next.await(5, TimeUnit.SECONDS))
            assertEquals(null, uncaught.get())
            assertEquals("Test|Recorder preparation|Reconnect the storage holding unfinished-recordings; its original files were retained.", reported.get())
        } finally {
            lane.shutdownNow()
        }
    }

    @Test fun storageWorkersOnPlainExecutorsAreGuarded() {
        fun source(path: String) = File("src/main/java/org/ncssar/rid2caltopo/$path").readText()
        val service = source("app/MediaMTXService.java")
        assertFalse("every recording-sync job is guarded", Regex("""recordingSyncExecutor\.execute\(\s*\(\)""").containsMatchIn(service))
        assertTrue(service.contains("guardedStorageJob(\"Recorder preparation\""))
        assertTrue(service.contains("guardedStorageJob(\"Orphaned recording sync\""))
        assertTrue(service.contains("guardedStorageJob(\"Completed recording sync\""))
        assertTrue(service.contains("guardedStorageJob(\"Recording sync at service stop\""))
        val recovery = source("data/CaltopoRecoveryWork.java")
        assertFalse(Regex("""\.execute\(task\)""").containsMatchIn(recovery))
        assertTrue(source("data/DiagnosticLogWork.java").contains("StorageJobGuard.guard(\"DiagnosticLogWork\""))
        assertTrue(source("data/CaltopoClient.java").contains("StorageJobGuard.guard(TAG, \"InitArchiveDir\""))
        assertTrue(source("app/FlightStorage.kt").contains("StorageJobGuard.guard(\"FlightStorage\", \"Recording start check\")"))
        val operational = source("storage/OperationalStorage.kt")
        assertFalse(operational.contains("error(\"Reconnect the storage holding"))
        assertFalse(operational.contains("error(\"Reconnect the previous Archive Dir"))
        assertFalse(Regex("""error\("[A-Za-z ]+ is no longer available\."\)""").containsMatchIn(operational))
        val sync = source("data/MediaMTXRecordingSync.kt")
        assertTrue(sync.substringAfter("fun recordingStagingDirOrTemporary(").substringBefore("fun recordingStagingDirWithoutPreparing").contains("catch (error: Exception)"))
    }
}
