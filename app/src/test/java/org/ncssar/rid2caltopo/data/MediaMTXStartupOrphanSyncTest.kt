package org.ncssar.rid2caltopo.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMTXStartupOrphanSyncTest {
    private fun tempRoot(): File = Files.createTempDirectory("staging").toFile().apply { deleteOnExit() }

    @Test fun snapshotListsStagedFragmentsPerStreamPath() {
        val root = tempRoot()
        File(root, "1sar7djmn4pr").mkdirs()
        File(root, "1sar7djmn4pr/a_2026-11-12_18-44-50.mp4").writeText("x")
        File(root, "1sar7djmn4pr/b_2026-11-12_18-50-50.mp4").writeText("x")
        File(root, "1sar7djmn4pr/notes.txt").writeText("x")
        File(root, "live/mactest").mkdirs()
        File(root, "live/mactest/seg.ts").writeText("x")
        File(root, "empty").mkdirs()

        val orphans = MediaMTXRecordingSync.snapshotOrphanedRecordings(root)

        assertEquals(listOf("1sar7djmn4pr", "live/mactest"), orphans.map { it.streamPath })
        assertEquals(
            listOf("a_2026-11-12_18-44-50.mp4", "b_2026-11-12_18-50-50.mp4"),
            orphans[0].fragments.map { it.name },
        )
        assertEquals(listOf("seg.ts"), orphans[1].fragments.map { it.name })
    }

    @Test fun snapshotOfMissingOrEmptyStagingIsEmpty() {
        assertTrue(MediaMTXRecordingSync.snapshotOrphanedRecordings(File(tempRoot(), "missing")).isEmpty())
        assertTrue(MediaMTXRecordingSync.snapshotOrphanedRecordings(tempRoot()).isEmpty())
    }

    @Test fun fragmentsWrittenAfterTheSnapshotAreNotClaimed() {
        val root = tempRoot()
        File(root, "p").mkdirs()
        File(root, "p/old.mp4").writeText("x")
        val orphans = MediaMTXRecordingSync.snapshotOrphanedRecordings(root)
        File(root, "p/live-now.mp4").writeText("x") // the new server's recording
        assertEquals(listOf("old.mp4"), orphans.single().fragments.map { it.name })
    }

    /** Field 2026-11-12: a 3 min orphan remux held the RTMP listener closed after an update. */
    @Test fun serverStartsBeforeOrphanedRecordingsAreRemuxed() {
        val src = File("src/main/java/org/ncssar/rid2caltopo/app/MediaMTXService.java").readText()
        val body = src.substringAfter("private void startNativeServer()").substringBefore("private File extractAsset(")
        val start = body.indexOf("MediaMTXNative.start(")
        val sync = body.indexOf("syncOrphanedRecordings(")
        assertTrue("server start missing", start >= 0)
        assertTrue("orphan sync must run after MediaMTXNative.start", sync > start)
        assertTrue("startup must not remux the whole staging tree first", !body.contains("syncAll("))
        assertTrue("orphan sync must run off the native control thread", body.contains("recordingSyncExecutor.execute"))
    }
}
