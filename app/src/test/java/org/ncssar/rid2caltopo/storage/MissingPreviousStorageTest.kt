package org.ncssar.rid2caltopo.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field 2026-11-18 (A5): the SD card was swapped and every launch crashed on retained state. */
class MissingPreviousStorageTest {
    @After fun reset() = MissingPreviousStorage.clear()

    @Test fun reconnectAndRaceFailuresAreTypedAndTreatedAsUnavailable() {
        val missing = ArchiveDirAvailability.previousStorageMissing(
            "unfinished-recordings", "/storage/4A21-0000/Android/data/org.ncssar.rid2caltopo/cache/operational-work/709c66c989b07197/unfinished-recordings")
        assertEquals("Reconnect the storage holding unfinished-recordings; its original files were retained.", missing.message)
        assertEquals(ArchiveStateProblem.PREVIOUS_STORAGE_MISSING, missing.problem)
        assertEquals("4A21-0000", missing.missingVolume)
        assertTrue(ArchiveDirAvailability.isUnavailable(missing))

        val previous = ArchiveDirAvailability.previousArchiveRequired("queue-state", "/data/user/0/x/cache/operational-work/abc/queue-state")
        assertEquals("Reconnect the previous Archive Dir before moving queue-state to another archive.", previous.message)
        assertNull(previous.missingVolume)
        assertTrue(ArchiveDirAvailability.isUnavailable(previous))

        val race = ArchiveDirAvailability.preparationFailedElsewhere("Recorder state")
        assertEquals("Recorder state is no longer available.", race.message)
        assertTrue(ArchiveDirAvailability.isUnavailable(race))
        assertTrue(race is IllegalStateException)
    }

    @Test fun volumeIsReadFromTheLinkTargetOnly() {
        assertEquals("4A21-0000", ArchiveDirAvailability.removableVolumeOf("/storage/4A21-0000/Android/data/p/cache/x"))
        assertEquals("2C5D-1669", ArchiveDirAvailability.removableVolumeOf("/mnt/media_rw/2C5D-1669/x"))
        assertNull(ArchiveDirAvailability.removableVolumeOf("/storage/emulated/0/Android/data/p/cache/x"))
        assertNull(ArchiveDirAvailability.removableVolumeOf("/storage/self/primary/x"))
        assertNull(ArchiveDirAvailability.removableVolumeOf("/data/user/0/p/cache/x"))
        assertNull(ArchiveDirAvailability.removableVolumeOf(null))
    }

    // Ken, 2026-11-18: a deliberately swapped card is not an error. Unreachable retained state
    // is a quiet Manage Storage line only: no pop-up, no map chip, nothing to re-open.
    @Test fun failuresOnlyAddAreasAndNeverRaiseAPopUp() {
        val link = "/storage/4A21-0000/Android/data/p/cache/operational-work/709c66c989b07197/"
        MissingPreviousStorage.note(ArchiveDirAvailability.previousStorageMissing("queue-state", link + "queue-state"))
        assertNull(MissingPreviousStorage.alert.value)
        assertEquals("4A21-0000", MissingPreviousStorage.notice.value!!.volume)
        repeat(5) { MissingPreviousStorage.note(ArchiveDirAvailability.previousStorageMissing("unfinished-recordings", link + "unfinished-recordings")) }
        assertEquals(setOf("queue-state", "unfinished-recordings"), MissingPreviousStorage.notice.value!!.areas)
        MissingPreviousStorage.reshowAlert()
        assertNull(MissingPreviousStorage.alert.value)
    }

    @Test fun racesAreIgnoredAndClearRemovesTheNoticeImmediately() {
        MissingPreviousStorage.note(ArchiveDirAvailability.preparationFailedElsewhere("Recorder state"))
        assertNull(MissingPreviousStorage.notice.value)
        MissingPreviousStorage.note(ArchiveDirAvailability.previousStorageMissing("queue-state", "/storage/4A21-0000/a/queue-state"))
        assertNotNull(MissingPreviousStorage.notice.value)
        MissingPreviousStorage.clear()
        assertNull("a folder pick or ready storage clears it without a restart", MissingPreviousStorage.notice.value)
    }

    @Test fun alertWordingNamesTheCardAndPromisesNoDeletion() {
        val notice = MissingPreviousStorageNotice("4A21-0000", ArchiveStateProblem.PREVIOUS_STORAGE_MISSING, setOf("unfinished-recordings", "recording-work", "queue-state"))
        assertEquals("Previous SD card is missing", MissingPreviousStorageText.title(notice))
        val message = MissingPreviousStorageText.message(notice)
        assertTrue(message, message.contains("the previous SD card (4A21-0000)"))
        assertTrue(message, message.contains("Nothing was deleted or moved"))
        assertTrue(message, message.contains("Live video still works"))
        assertTrue(message, message.contains("unfinished recordings, pending uploads and flight state"))
        assertFalse(message, message.contains("recording-work"))
        assertTrue(MissingPreviousStorageText.status(notice).contains("Nothing was deleted"))
        val unknown = notice.copy(volume = null, problem = ArchiveStateProblem.PREVIOUS_ARCHIVE_REQUIRED)
        assertEquals("Previous archive folder is missing", MissingPreviousStorageText.title(unknown))
        assertTrue(MissingPreviousStorageText.message(unknown).contains("the previous storage"))
    }
}
