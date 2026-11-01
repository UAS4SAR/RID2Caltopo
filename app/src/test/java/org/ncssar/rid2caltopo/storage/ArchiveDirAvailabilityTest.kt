package org.ncssar.rid2caltopo.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDirAvailabilityTest {
    @Test
    fun unavailableArchiveStartupUsesAppPrivateQueueWithoutThrowing() {
        val root = File.createTempFile("archive-dir-avail", "dir").also {
            check(it.delete())
            check(it.mkdirs())
        }
        try {
            assertTrue(ArchiveDirAvailability.useAppPrivateQueueOnly(archiveUriPresent = false))
            assertFalse(ArchiveDirAvailability.useAppPrivateQueueOnly(archiveUriPresent = true))

            val file = ArchiveDirAvailability.operationalStateFile(root, "awaiting-map-flights.json")
            assertEquals(File(root, "operational-state/awaiting-map-flights.json"), file)
            assertTrue(file.parentFile!!.isDirectory)

            // Soft-unavailable path: write journal bytes without any Archive Dir tree URI.
            file.writeText("""[{"id":"flight-1","finished":false}]""")
            assertEquals("""[{"id":"flight-1","finished":false}]""", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unavailableMessagesAndTypedExceptionAreRecognized() {
        assertTrue(
            ArchiveDirAvailability.isUnavailable(
                ArchiveDirUnavailableException(ArchiveDirAvailability.CHOOSE_OR_RECONNECT)
            )
        )
        assertTrue(
            ArchiveDirAvailability.isUnavailableMessage(
                "Choose or reconnect Archive Dir to use retained operational data."
            )
        )
        assertTrue(
            ArchiveDirAvailability.isUnavailableMessage(
                "Choose or reconnect Archive Dir to use retained operational state."
            )
        )
        assertTrue(ArchiveDirAvailability.isUnavailableMessage("Archive Dir is unavailable."))
        assertTrue(
            ArchiveDirAvailability.isUnavailableMessage(
                "Archive Dir became unavailable during preparation; reconnect its storage."
            )
        )
        assertFalse(ArchiveDirAvailability.isUnavailableMessage("Existing operational projection changed during preparation"))
        assertFalse(ArchiveDirAvailability.isUnavailable(IllegalStateException("Missing file")))
    }
}
