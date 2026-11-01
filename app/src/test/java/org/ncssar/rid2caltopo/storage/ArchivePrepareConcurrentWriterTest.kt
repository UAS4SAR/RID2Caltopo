package org.ncssar.rid2caltopo.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class ArchivePrepareConcurrentWriterTest {
    private class Tree(val root: File, private val mutateSource: File? = null) : ArchiveTree {
        private fun file(path: String) = File(root, path)
        override fun exists(path: String) = file(path).exists()
        override fun mkdirs(path: String) { file(path).mkdirs() }
        override fun list(path: String) =
            file(path).listFiles().orEmpty().map {
                ArchiveTree.Entry(it.name, it.isDirectory, it.length(), it.lastModified())
            }
        override fun read(path: String): InputStream = file(path).inputStream()
        override fun write(path: String, input: InputStream) {
            val bytes = input.readBytes()
            // Simulate a live journal rewrite after the staged copy was taken from source.
            if (mutateSource != null && path.contains(".r2c-stage-")) {
                mutateSource.writeText("live-writer-changed-${System.nanoTime()}")
            }
            val out = file(path)
            out.parentFile.mkdirs()
            out.writeBytes(bytes)
        }
        override fun rename(from: String, to: String): Boolean =
            !exists(to) && file(from).renameTo(file(to))
        override fun delete(path: String) {
            check(!file(path).exists() || file(path).deleteRecursively())
        }
    }

    @Test
    fun liveWriterRaceIsTransientPrepareFailure() {
        assertTrue(
            OperationalStorage.isTransientPrepareFailure(
                IllegalStateException(
                    "File changed or archive verification failed: caltopo-interrupted-tracks.json. Original files were retained.",
                ),
            ),
        )
        assertTrue(
            OperationalStorage.isTransientPrepareFailure(
                IllegalStateException(
                    "File changed or archive verification failed: clues.json. Original files were retained.",
                ),
            ),
        )
        assertFalse(
            OperationalStorage.isTransientPrepareFailure(
                IllegalStateException("Archive Dir is unavailable; reconnect its storage."),
            ),
        )
    }

    @Test
    fun verifiedPutFailsWhenSourceMutatesDuringArchiveCopy() {
        val root = Files.createTempDirectory("archive-race").toFile()
        try {
            val source = File(root, "caltopo-interrupted-tracks.json").apply { writeText("""{"version":1,"entries":[]}""") }
            val tree = Tree(File(root, "archive"), mutateSource = source)
            val error = assertThrows(IllegalStateException::class.java) {
                VerifiedArchiveTree(tree).put(source, "application-logs/operational/queue-state/caltopo-interrupted-tracks.json")
            }
            assertTrue(error.message!!.contains("File changed or archive verification failed"))
            assertTrue(OperationalStorage.isTransientPrepareFailure(error))
            // App-private original remains; nothing durable was deleted.
            assertTrue(source.isFile)
            assertTrue(source.length() > 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stableSnapshotAllowsArchivePutWhileLiveFileKeepsChanging() {
        val root = Files.createTempDirectory("archive-snapshot").toFile()
        try {
            val live = File(root, "caltopo-interrupted-tracks.json").apply {
                writeText("""{"version":1,"entries":[{"liveTrackId":"a"}]}""")
            }
            val snapshot = File(root, "stable.bin").apply {
                live.inputStream().use { input -> outputStream().use { input.copyTo(it) } }
            }
            val original = snapshot.readText()
            val tree = Tree(File(root, "archive"))
            // Mutate the live journal after the snapshot was taken.
            live.writeText("""{"version":1,"entries":[{"liveTrackId":"b"}]}""")
            VerifiedArchiveTree(tree).put(
                snapshot,
                "application-logs/operational/queue-state/caltopo-interrupted-tracks.json",
            )
            assertEquals(
                original,
                tree.read("application-logs/operational/queue-state/caltopo-interrupted-tracks.json")
                    .reader().readText(),
            )
            // Live writer kept its newer app-private bytes (no data loss).
            assertTrue(live.readText().contains("\"liveTrackId\":\"b\""))
        } finally {
            root.deleteRecursively()
        }
    }
}
