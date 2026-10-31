package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaltopoRecoveryWorkTest {
    @Test fun slowStorageKeepsOnlyTheLatestWaitingCheckpointForEachFlight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstFlight = Any()
        val secondFlight = Any()
        val values = mutableListOf<Int>()
        try {
            CaltopoRecoveryWork.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(10_000) { value -> CaltopoRecoveryWork.checkpoint(firstFlight) { values.add(value) } }
            CaltopoRecoveryWork.checkpoint(secondFlight) { values.add(20_000) }
            release.countDown()
            CaltopoRecoveryWork.worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(9_999, 20_000), values)
        } finally { release.countDown() }
    }

    @Test fun updateDuringAWriteRunsAfterItWithoutLosingTheFinalCheckpoint() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val key = Any()
        val value = AtomicInteger()
        try {
            CaltopoRecoveryWork.checkpoint(key) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                value.set(1)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { CaltopoRecoveryWork.checkpoint(key) { value.set(2) } }
            release.countDown()
            CaltopoRecoveryWork.worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(2, value.get())
        } finally { release.countDown() }
    }

    @Test fun archiveLaneDoesNotBlockLiveTrackStart() {
        val archiveEntered = CountDownLatch(1)
        val archiveRelease = CountDownLatch(1)
        val startRan = CountDownLatch(1)
        try {
            CaltopoRecoveryWork.executeArchive {
                archiveEntered.countDown()
                archiveRelease.await(5, TimeUnit.SECONDS)
            }
            assertTrue(archiveEntered.await(2, TimeUnit.SECONDS))
            CaltopoRecoveryWork.executeStart { startRan.countDown() }
            assertTrue(
                "LiveTrack start stayed queued behind a stalled archive",
                startRan.await(2, TimeUnit.SECONDS),
            )
        } finally {
            archiveRelease.countDown()
            CaltopoRecoveryWork.startWorker.submit {}.get(2, TimeUnit.SECONDS)
            CaltopoRecoveryWork.archiveWorker.submit {}.get(2, TimeUnit.SECONDS)
        }
    }
}
