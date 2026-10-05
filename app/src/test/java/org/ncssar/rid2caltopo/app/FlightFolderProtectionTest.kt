package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightFolderProtectionTest {
    private val today = "R2C_2026-10-04"
    private val old = "R2C_2026-10-01"

    @Test
    fun reasonsMatchIosWordingAndPriority() {
        assertEquals("today", FlightFolderProtection.evaluate(today, today, mapOf("diagnostic-log" to today), true, listOf("uploading"))?.label)
        assertEquals("recording in progress", FlightFolderProtection.evaluate(old, today, emptyMap(), true, emptyList())?.label)
        assertEquals("in use by archive upload", FlightFolderProtection.evaluate(old, today, mapOf("archive-upload-1" to old), false, emptyList())?.label)
        assertEquals("in use by diagnostic log", FlightFolderProtection.evaluate(old, today, mapOf("diagnostic-log" to old), false, emptyList())?.label)
        assertEquals("clue upload in progress", FlightFolderProtection.evaluate(old, today, emptyMap(), false, listOf("published", "uploading"))?.label)
    }

    @Test
    fun pendingAndFailedCluesDoNotBlockManualDelete() {
        assertNull(FlightFolderProtection.evaluate(old, today, emptyMap(), false, listOf("failed", "pending")))
        assertEquals(3, FlightFolderProtection.unuploadedClueCount(listOf("failed", "pending", "published", "uploading", "localOnly")))
    }

    @Test
    fun automaticCleanupKeepsQueuedButNotFailedClues() {
        assertTrue(FlightFolderProtection.blocksAutomaticCleanup(listOf("pending")))
        assertTrue(FlightFolderProtection.blocksAutomaticCleanup(listOf("uploading")))
        assertFalse(FlightFolderProtection.blocksAutomaticCleanup(listOf("failed", "published")))
    }

    @Test
    fun ownerLabelsMatchIos() {
        assertEquals("diagnostic log", FlightFolderProtection.ownerLabel("diagnostic-log"))
        assertEquals("video review", FlightFolderProtection.ownerLabel("video-review"))
        assertEquals("archive upload", FlightFolderProtection.ownerLabel("upload-1234"))
        assertEquals("recording finalization", FlightFolderProtection.ownerLabel("finalize-1234"))
        assertEquals("filesystem test", FlightFolderProtection.ownerLabel("filesystem-test"))
    }

    @Test
    fun releasedOwnerNoLongerProtects() {
        FlightStorage.protect("R2C_2019-03-03", "archive-upload-test")
        assertTrue(FlightStorage.isProtected("R2C_2019-03-03"))
        FlightStorage.release("archive-upload-test")
        assertFalse(FlightStorage.isProtected("R2C_2019-03-03"))
    }

    @Test
    fun folderDetailAndDeleteWarningTextMatchIos() {
        val option = ArchiveCleanupDirectoryOption(
            directoryName = old, ageMs = 0, ageLabel = "3 days", totalBytes = 0, sizeLabel = "1.0 GB",
            logFileCount = 0, kmzCount = 0, videoCount = 0, lastModifiedMs = 0, isToday = true,
            protectionReason = "in use by video review",
        )
        assertEquals("Age 3 days • 1.0 GB • protected: in use by video review", archiveCleanupDetail(option))
        assertEquals(
            "Age 3 days • 1.0 GB • 2 clues not uploaded",
            archiveCleanupDetail(option.copy(isToday = false, protectionReason = null, unuploadedClueCount = 2))
        )
        assertEquals(
            "Permanently delete 2 archive folders totaling 2.0 GB? 1 clue was never uploaded to CalTopo and will be lost.",
            archiveDeleteConfirmation(2, "2.0 GB", 1)
        )
        assertEquals("Permanently delete 1 archive folder totaling 5 MB?", archiveDeleteConfirmation(1, "5 MB", 0))
    }
}
