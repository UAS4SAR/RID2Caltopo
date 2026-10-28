package org.ncssar.rid2caltopo.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class VerifiedProjectionFileTest {
    private fun hash(text: String) = text.byteInputStream().use { VerifiedArchiveTree.digest(it) }

    @Test fun unchangedVerifiedWorkingCopyDoesNotReadOrCopyRetainedBulkAgain() {
        val root = Files.createTempDirectory("projection-reuse").toFile()
        try {
            val target = File(root, "video.mp4").apply { writeText("verified original") }
            val modified = target.lastModified()
            assertTrue(VerifiedProjectionFile.restore(target, hash("verified original"), root,
                openRetained = { error("Redundant archive read") }, modifiedForHash = { error("Redundant metadata read") }))
            assertEquals("verified original", target.readText())
            assertEquals(modified, target.lastModified())
            assertEquals(listOf("video.mp4"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun missingWorkingCopyIsVerifiedAndRestoredWithOriginalTimestamp() {
        val root = Files.createTempDirectory("projection-restore").toFile()
        try {
            val target = File(root, "video.mp4"); var reads = 0
            assertFalse(VerifiedProjectionFile.restore(target, null, root,
                openRetained = { reads++; "retained original".byteInputStream() },
                modifiedForHash = { actual -> assertEquals(hash("retained original"), actual); 1234000L }))
            assertEquals(2, reads)
            assertEquals("retained original", target.readText())
            assertEquals(1234000L, target.lastModified())
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun changedOrUnverifiedWorkingOriginalIsNeverOverwritten() {
        for (expected in listOf(null, hash("previous"))) {
            val root = Files.createTempDirectory("projection-conflict").toFile()
            try {
                val target = File(root, "video.mp4").apply { writeText("uncommitted original") }
                assertThrows(IllegalStateException::class.java) {
                    VerifiedProjectionFile.restore(target, expected, root,
                        openRetained = { error("Must preserve original before reading a replacement") }, modifiedForHash = { 0 })
                }
                assertEquals("uncommitted original", target.readText())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun providerFailureAndChangingArchiveLeaveNoPublishedPartialOrScratch() {
        for (fail in listOf(true, false)) {
            val root = Files.createTempDirectory("projection-provider").toFile()
            try {
                val target = File(root, "video.mp4"); var reads = 0
                assertThrows(if (fail) IOException::class.java else IllegalStateException::class.java) {
                    VerifiedProjectionFile.restore(target, null, root, openRetained = {
                        if (++reads == 2 && fail) throw IOException("provider disconnected")
                        (if (reads == 1) "old archive" else "changed archive").byteInputStream()
                    }, modifiedForHash = { 0 })
                }
                assertFalse(target.exists()); assertTrue(root.listFiles()!!.isEmpty())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun cancellationAndConcurrentTargetCreationPreserveOriginalsAndCleanScratch() {
        for (cancel in listOf(true, false)) {
            val root = Files.createTempDirectory("projection-stale").toFile()
            try {
                val target = File(root, "video.mp4")
                assertThrows(if (cancel) CancellationException::class.java else java.nio.file.FileAlreadyExistsException::class.java) {
                    VerifiedProjectionFile.restore(target, null, root, openRetained = { "original".byteInputStream() },
                        modifiedForHash = {
                            if (cancel) throw CancellationException("selection changed")
                            target.writeText("concurrent original"); 0
                        })
                }
                if (cancel) assertFalse(target.exists()) else assertEquals("concurrent original", target.readText())
                assertTrue(root.listFiles()!!.none { it.name.startsWith("hydrate-") })
            } finally { root.deleteRecursively() }
        }
    }
}
