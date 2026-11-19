package org.ncssar.rid2caltopo.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveWritabilityTextTest {
    private val card = ArchiveWritabilityVerdict(false, ArchiveWriteProblem.READ_ONLY, "EROFS", 0L, removable = true, generation = 1)

    @Test fun alertSaysReadOnlyLiveVideoWorksAndTabletFallback() {
        val message = ArchiveWritabilityText.alertMessage(card, fallbackAvailable = true)
        assertTrue(message.startsWith("SD card is read-only (likely failing)."))
        assertTrue(message.contains("live video still works"))
        assertTrue(message.contains("kept in tablet storage"))
        assertTrue(message.contains("copied to your archive folder"))
        assertEquals("SD card is read-only", ArchiveWritabilityText.alertTitle(card))
        assertEquals("Use tablet storage", ArchiveWritabilityText.USE_TABLET_STORAGE)
        assertEquals("Manage Storage", ArchiveWritabilityText.MANAGE_STORAGE)
    }

    @Test fun withoutTabletRoomRecordingIsPaused() {
        val message = ArchiveWritabilityText.alertMessage(card, fallbackAvailable = false)
        assertTrue(message.contains("Recording is paused; live video still works."))
        assertFalse(message.contains("kept in tablet storage"))
        assertEquals("SD card read-only · recording paused", ArchiveWritabilityText.chip(card, false))
    }

    @Test fun internalArchiveAndIoErrorsUseNeutralWording() {
        val internal = card.copy(removable = false, problem = ArchiveWriteProblem.IO_ERROR)
        assertEquals("Archive storage has I/O errors", ArchiveWritabilityText.alertTitle(internal))
        assertEquals("Archive storage I/O errors · saving to tablet", ArchiveWritabilityText.chip(internal, true))
        val status = ArchiveWritabilityText.status(internal, true) { "7:01 AM" }
        assertTrue(status.startsWith("Archive storage has I/O errors (likely failing) since 7:01 AM."))
        assertTrue(status.contains("every 5 minutes"))
    }

    // The chip and alert are for the active archive folder only: its card is not in the tablet.
    @Test fun activeArchiveCardMissingSaysReinsertOrChooseAnother() {
        val missing = card.copy(problem = ArchiveWriteProblem.MISSING)
        assertEquals("SD card is missing", ArchiveWritabilityText.alertTitle(missing))
        assertEquals("SD card missing · saving to tablet", ArchiveWritabilityText.chip(missing, true))
        val message = ArchiveWritabilityText.alertMessage(missing, fallbackAvailable = true)
        assertTrue(message, message.startsWith("The SD card with your archive folder is not in this tablet."))
        assertTrue(message, message.contains("Reinsert that card, or choose another archive folder in Manage Storage"))
        assertFalse(message, message.contains("previous", ignoreCase = true))
        assertTrue(ArchiveWritabilityText.alertMessage(missing, false).contains("Tablet storage is also low"))
        assertEquals(ArchiveWriteProblem.MISSING,
            ArchiveWritabilityTracker.classify(java.io.IOException(ArchiveWritabilityTracker.missingCardMessage("2C5D-1669"))))
    }
}
