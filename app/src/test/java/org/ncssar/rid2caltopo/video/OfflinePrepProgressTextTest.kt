package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Wording must match OperationalOfflineProgressTextTests on iOS.
class OfflinePrepProgressTextTest {
    private val rate: (Long) -> String = { "${it / 1_000_000} MB" }
    private val base = OfflinePrepProgress(
        phase = "Downloading map + DEM", total = 47, completed = 30,
        tileTotal = 42, tileCompleted = 28, hits = 20, fetched = 8, failed = 0,
        demTotal = 2, demCompleted = 2, demHits = 2, demFetched = 0, demFailed = 0,
        completedBytes = 40, totalBytes = 100, bytesPerSec = 8_000_000.0, etaSeconds = 65,
        tileLayers = listOf("Imagery", "OSM", "Contours"), includesDem = true
    )
    private val withAol = base.copy(includesAol = true, aolFilesTotal = 46, aolTilesTotal = 3, total = 50, etaSeconds = null)

    private fun state(p: OfflinePrepProgress, inFlight: Boolean, cancel: Boolean = false) =
        OfflinePrepProgressText.runState(p, inFlight, cancel)

    @Test fun inProgressMapAndTerrain() {
        val s = state(base, true)
        assertEquals(OfflinePrepRunState.Running, s)
        assertEquals("Downloading map tiles…", OfflinePrepProgressText.headline(base, s))
        assertEquals(listOf(
            "Map tiles (Imagery, OSM, Contours): 28/42 — 20 cached, 8 downloaded, 0 failed",
            "Terrain (DEM): 2/2 — 2 cached, 0 downloaded, 0 failed"
        ), OfflinePrepProgressText.partLines(base, s))
        assertEquals("Overall: 40% · 30/47 items · 8 MB/s · ETA 01:05", OfflinePrepProgressText.overallLine(base, s, rate))
        assertNull(OfflinePrepProgressText.noteLine(base, s))
    }

    @Test fun onlySelectedPartsAreShown() {
        val demOnly = OfflinePrepProgress(phase = "Downloading DEM tiles", total = 2, completed = 1, demTotal = 2, demCompleted = 1, demFetched = 1, includesDem = true)
        val lines = OfflinePrepProgressText.partLines(demOnly, OfflinePrepRunState.Running)
        assertEquals(listOf("Terrain (DEM): 1/2 — 0 cached, 1 downloaded, 0 failed"), lines)
        assertEquals("Downloading terrain…", OfflinePrepProgressText.headline(demOnly, OfflinePrepRunState.Running))
    }

    @Test fun aolLinesFollowStages() {
        val running = OfflinePrepRunState.Running
        assertEquals("AOL: waiting for map and terrain — 46 lidar files, 3 1 m tiles", OfflinePrepProgressText.aolLine(withAol, running))
        val files = withAol.copy(aolStage = OfflinePrepAolStage.Files, aolFilesCompleted = 12, phase = "AOL lidar 13/46: 4/40 MB",
            tileCompleted = 42, completed = 44)
        assertEquals("AOL: lidar files 12/46", OfflinePrepProgressText.aolLine(files, running))
        assertEquals("AOL: lidar files 44/46 — 44 kept from last attempt",
            OfflinePrepProgressText.aolLine(files.copy(aolFilesCompleted = 44, aolFilesKept = 44), running))
        assertEquals("Preparing AOL…", OfflinePrepProgressText.headline(files, running))
        assertEquals("Now: AOL lidar 13/46: 4/40 MB", OfflinePrepProgressText.noteLine(files, running))
        assertEquals("Overall: 44/50 items — map and terrain first, then AOL; time remaining varies with lidar size",
            OfflinePrepProgressText.overallLine(files, running, rate))
        val tiles = files.copy(aolStage = OfflinePrepAolStage.Tiles, aolFilesCompleted = 46, aolTilesCompleted = 1)
        assertEquals("AOL: building 1 m tiles 1/3", OfflinePrepProgressText.aolLine(tiles, running))
        assertEquals("AOL: already prepared — using cached tiles",
            OfflinePrepProgressText.aolLine(withAol.copy(aolStage = OfflinePrepAolStage.Reused), OfflinePrepRunState.Finished))
        assertEquals("AOL: not started", OfflinePrepProgressText.aolLine(withAol, OfflinePrepRunState.Cancelled))
    }

    @Test fun allComplete() {
        val done = withAol.copy(phase = "Complete", completed = 47 + 3, tileCompleted = 42, hits = 42, fetched = 0,
            aolStage = OfflinePrepAolStage.Complete, aolFilesCompleted = 46, aolTilesCompleted = 3, completedBytes = 99)
        val s = state(done, false)
        assertEquals(OfflinePrepRunState.Finished, s)
        assertEquals("Map, terrain and AOL complete", OfflinePrepProgressText.headline(done, s))
        assertFalse(OfflinePrepProgressText.hasFailure(done, s))
        assertEquals("AOL: complete — 3 1 m tiles from 46 lidar files", OfflinePrepProgressText.aolLine(done, s))
        assertEquals("Overall: 100% · 50/50 items", OfflinePrepProgressText.overallLine(done, s, rate))
    }

    @Test fun aolFailureAfterMapAndTerrainComplete() {
        val failed = withAol.copy(phase = OfflinePrepProgressText.FAILED_AOL_PHASE, completed = 44, tileCompleted = 42, hits = 42, fetched = 0,
            aolStage = OfflinePrepAolStage.Failed, aolFilesCompleted = 44, completedBytes = 56,
            aolFailure = "couldn't reach USGS (timed out after 30 s)")
        val s = state(failed, false)
        assertEquals(OfflinePrepRunState.Finished, s)
        assertEquals("Map and terrain complete; AOL failed — retry available", OfflinePrepProgressText.headline(failed, s))
        assertTrue(OfflinePrepProgressText.hasFailure(failed, s))
        assertEquals("AOL failed: couldn't reach USGS (timed out after 30 s) — stopped at lidar files 44/46",
            OfflinePrepProgressText.aolLine(failed, s))
        // No rate or ETA after a failure, and the percent is the overall job's.
        assertEquals("Overall: 56% · 44/50 items", OfflinePrepProgressText.overallLine(failed, s, rate))
    }

    @Test fun tileFailuresAndWholeJobFailure() {
        val partial = base.copy(phase = "Complete with failures", tileCompleted = 42, failed = 3, completed = 44)
        assertEquals("Terrain complete; map tiles had failures — retry available",
            OfflinePrepProgressText.headline(partial, state(partial, false)))
        val stopped = base.copy(phase = "Failed: boom", failure = "couldn't reach the download server")
        val s = state(stopped, false)
        assertEquals(OfflinePrepRunState.Failed, s)
        assertEquals("Download failed — retry available", OfflinePrepProgressText.headline(stopped, s))
        assertEquals("Stopped: couldn't reach the download server", OfflinePrepProgressText.noteLine(stopped, s))
        assertEquals(OfflinePrepRunState.Cancelling, state(base, true, cancel = true))
        assertEquals("Download cancelled", OfflinePrepProgressText.headline(base.copy(phase = "Cancelled"), state(base.copy(phase = "Cancelled"), false)))
    }

    @Test fun describesFailuresInPlainWords() {
        val okhttp = SocketTimeoutException("failed to connect to rockyweb.usgs.gov/1.2.3.4 (port 443) from /10.0.0.2 (port 5555) after 30000ms")
        assertEquals("couldn't reach USGS (timed out after 30 s)", OfflinePrepProgressText.describeFailure(okhttp, "USGS"))
        assertEquals("couldn't reach USGS (timed out after 30 s)",
            OfflinePrepProgressText.describeFailure(RuntimeException("failed to connect to rockyweb.usgs.gov after 30,000ms"), "USGS"))
        assertEquals("USGS stopped responding (timed out)", OfflinePrepProgressText.describeFailure(SocketTimeoutException("timeout"), "USGS"))
        assertEquals("couldn't reach USGS (no network or name lookup failed)",
            OfflinePrepProgressText.describeFailure(java.io.IOException("wrap", UnknownHostException("rockyweb.usgs.gov")), "USGS"))
        assertEquals("USGS returned HTTP 503", OfflinePrepProgressText.describeFailure(IllegalStateException("Lidar download HTTP 503"), "USGS"))
        assertEquals("Not enough free working space for AOL; select a smaller region or free storage",
            OfflinePrepProgressText.describeFailure(IllegalStateException("Not enough free working space for AOL; select a smaller region or free storage"), "USGS"))
    }

    @Test fun selectionChangedNoticeIsNotTitledAsFailure() {
        assertEquals("Download not started", offlinePrepNoticeTitle(neutral = true))
        assertEquals("Download failed", offlinePrepNoticeTitle(neutral = false))
    }

    @Test fun autoCloseOnlyAfterCleanRunWhileShowing() {
        // Successful run, including one with AOL processing, closes the visible window.
        assertTrue(OfflinePrepProgressText.shouldAutoClose("Complete", 0, aolFailed = false, dialogShown = true))
        // Hidden during the run: nothing to close, and a later reopen must not close by itself.
        assertFalse(OfflinePrepProgressText.shouldAutoClose("Complete", 0, aolFailed = false, dialogShown = false))
        // Failures stay open for Retry.
        assertFalse(OfflinePrepProgressText.shouldAutoClose("Complete with failures", 2, aolFailed = false, dialogShown = true))
        assertFalse(OfflinePrepProgressText.shouldAutoClose(OfflinePrepProgressText.FAILED_AOL_PHASE, 0, aolFailed = true, dialogShown = true))
        assertFalse(OfflinePrepProgressText.shouldAutoClose("Cancelled", 0, aolFailed = false, dialogShown = true))
        assertEquals("Hide", OfflinePrepProgressText.HIDE_LABEL)
        assertEquals("Cancel download", OfflinePrepProgressText.CANCEL_DOWNLOAD_LABEL)
    }

    @Test fun activeDownloadStatusOutsideTheWindow() {
        assertEquals("Downloading map… 40%", OfflinePrepProgressText.activeDownloadStatus(base, inFlight = true, cancelRequested = false))
        assertEquals("Downloading map…", OfflinePrepProgressText.activeDownloadStatus(base.copy(totalBytes = 0L), inFlight = true, cancelRequested = false))
        assertEquals("Cancelling map download…", OfflinePrepProgressText.activeDownloadStatus(base, inFlight = true, cancelRequested = true))
        assertNull(OfflinePrepProgressText.activeDownloadStatus(base, inFlight = false, cancelRequested = false))
    }
}
