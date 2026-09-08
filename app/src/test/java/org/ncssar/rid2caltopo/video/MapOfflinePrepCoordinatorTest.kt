package org.ncssar.rid2caltopo.video

import java.io.Closeable
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOfflinePrepCoordinatorTest {
    @After
    fun tearDown() {
        MapOfflinePrepRuntime.resetForTesting()
    }

    @Test
    fun runtimeCancelInvokesDurableOwner() {
        var cancelled = false
        MapOfflinePrepRuntime.begin { hideDialog -> cancelled = hideDialog }

        assertTrue(MapOfflinePrepRuntime.isActive())
        MapOfflinePrepRuntime.cancelActive()

        assertTrue(cancelled)
    }

    @Test
    fun runtimeNotifiesIdlePolicyAtStartAndFinish() {
        var notifications = 0
        MapOfflinePrepRuntime.begin(
            onIdlePolicyChanged = { notifications++ },
            onCancel = {},
        )
        val startedAt = MapOfflinePrepRuntime.lastActivityAtMsec()

        MapOfflinePrepRuntime.finish()

        assertEquals(2, notifications)
        assertTrue(startedAt > 0L)
        assertTrue(MapOfflinePrepRuntime.lastActivityAtMsec() >= startedAt)
        assertFalse(MapOfflinePrepRuntime.isActive())
    }

    @Test
    fun stallRecoveryCanBeClaimedOncePerThreshold() {
        MapOfflinePrepRuntime.begin(onCancel = {})
        val startedAt = MapOfflinePrepRuntime.lastActivityAtMsec()

        assertFalse(MapOfflinePrepRuntime.claimStallRecovery(startedAt + 44_999L, 45_000L))
        assertTrue(MapOfflinePrepRuntime.claimStallRecovery(startedAt + 45_000L, 45_000L))
        assertFalse(MapOfflinePrepRuntime.claimStallRecovery(startedAt + 45_001L, 45_000L))
    }

    @Test
    fun offlineWorkerLeaseOutlivesUiResourcesAndClosesAtDownloadFinish() {
        var closeCount = 0
        val lease = OfflinePrepWorkerLease {
            Closeable { closeCount++ }
        }

        lease.acquire()
        Closeable { }.close() // Simulates disposal of an unrelated MapPane resource.

        assertEquals(0, closeCount)

        lease.release()
        assertEquals(1, closeCount)
    }

    @Test
    fun mapPaneKeepsOfflineWorkersSeparateFromDisposableLiveMapWorkers() {
        val source = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt"
        )

        assertTrue(source.contains("offlinePrepCoordinator.begin(context)"))
        assertTrue(source.contains("offlinePrepTileFetchScheduler.lowPriority"))
        assertTrue(source.contains("latestTileFetchPriorityScheduler.close()"))
        assertFalse(source.contains("tileFetchPriorityScheduler.lowPriority"))
    }

    @Test
    fun menuStatusShowsPreparingAndPercentOnlyWhileActive() {
        assertNull(offlinePrepMenuStatus(false, OfflinePrepProgress()))
        assertEquals(
            "Preparing",
            offlinePrepMenuStatus(true, OfflinePrepProgress(phase = "Preparing"))
        )
        assertEquals(
            "37%",
            offlinePrepMenuStatus(
                true,
                OfflinePrepProgress(
                    phase = "Downloading map tiles",
                    total = 100,
                    completed = 37,
                    completedBytes = 370_000L,
                    totalBytes = 1_000_000L,
                )
            )
        )
        MapOfflinePrepRuntime.finish()
        assertFalse(MapOfflinePrepRuntime.isActive())
    }

    @Test
    fun weightedProgressTracksBytesInsideLargeDemDownload() {
        assertEquals(
            100_000_000L,
            weightedTransferredBytes(
                estimatedBytes = 400_000_000L,
                transferredBytes = 100_000_000L,
                expectedBytes = 400_000_000L,
            )
        )
        assertEquals(
            400_000_000L,
            weightedTransferredBytes(
                estimatedBytes = 400_000_000L,
                transferredBytes = 600_000_000L,
                expectedBytes = 400_000_000L,
            )
        )
    }

    private fun projectSource(relativePath: String): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(workingDirectory, relativePath),
            File(workingDirectory.parentFile ?: workingDirectory, relativePath),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from $workingDirectory"
        }.readText()
    }
}
