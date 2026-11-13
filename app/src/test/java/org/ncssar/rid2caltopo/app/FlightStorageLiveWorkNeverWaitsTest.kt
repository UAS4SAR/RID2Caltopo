package org.ncssar.rid2caltopo.app

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field 2026-11-12: a 70 s purge scan held the storage lock that clue saves also took. */
class FlightStorageLiveWorkNeverWaitsTest {
    private val source = File("src/main/java/org/ncssar/rid2caltopo/app/FlightStorage.kt").readText()

    @Test fun clueSnapshotSavesNeverScanOrTakeTheStorageLock() {
        val body = source.substringAfter("fun prepareAppPrivateWrite(").substringBefore("private var transientCheckAttempt")
        for (blocking in listOf("lock.", "withLock", "synchronized(lock", "scanOnWorker", "storageMetadata", "storageIo", "scan(")) {
            assertFalse("clue save uses $blocking", body.contains(blocking))
        }
        assertTrue(body.contains("requestCheck(context, true)"))
    }

    @Test fun recordingStartNeverTakesTheStorageLock() {
        val body = source.substringAfter("fun recordingStartEstimate(").substringBefore("internal fun recordingStartIssue(")
        assertFalse(body.contains("lock.") || body.contains("withLock") || body.contains("scanOnWorker"))
    }

    @Test fun busyLockReturnsTheLastResultImmediately() {
        val lock = ReentrantLock()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { lock.lock(); held.countDown(); release.await(); lock.unlock() }.apply { start() }
        held.await(1, TimeUnit.SECONDS)
        val started = System.nanoTime()
        val value = FlightStorage.readUnlessBusy(lock, "cached") { "fresh" }
        assertEquals("cached", value)
        assertTrue((System.nanoTime() - started) < TimeUnit.MILLISECONDS.toNanos(100))
        release.countDown(); holder.join()
        assertEquals("fresh", FlightStorage.readUnlessBusy(lock, "cached") { "fresh" })
        assertFalse(lock.isLocked)
    }

    @Test fun firstReadWithoutCacheWaitsForTheResult() {
        val lock = ReentrantLock()
        lock.lock()
        val result = arrayOfNulls<String>(1)
        val reader = Thread { result[0] = FlightStorage.readUnlessBusy<String>(lock, null) { "fresh" } }.apply { start() }
        Thread.sleep(50)
        assertEquals(null, result[0])
        lock.unlock(); reader.join(1000)
        assertEquals("fresh", result[0])
    }
}
