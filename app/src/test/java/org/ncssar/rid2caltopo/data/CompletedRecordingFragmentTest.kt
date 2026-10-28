package org.ncssar.rid2caltopo.data

import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CompletedRecordingFragmentTest {
    @Test fun closedFragmentCannotSelectAnotherPublishersLiveRecording() {
        val root = Files.createTempDirectory("completed-recording").toFile()
        try {
            val closed = File(root, "closed.mp4").apply { writeText("closed data") }
            val live = File(root, "live.mp4").apply { writeText("active writer") }
            assertEquals(listOf(closed), completedRecordingFragment(root, closed.path))
            assertEquals("active writer", live.readText())
            closed.delete()
            assertTrue(completedRecordingFragment(root, closed.path).isEmpty())
            assertTrue(live.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun completionOutsideStreamDirectoryIsRejected() {
        val root = Files.createTempDirectory("recording-scope").toFile()
        try {
            val stream = File(root, "stream").apply { mkdirs() }
            val other = File(root, "other.mp4").apply { writeText("preserve") }
            assertThrows(IllegalStateException::class.java) { completedRecordingFragment(stream, other.path) }
            assertEquals("preserve", other.readText())
        } finally { root.deleteRecursively() }
    }
}
