package org.ncssar.rid2caltopo.data

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.RecordingArchiveCopy.Placement

/** Field 2026-11-23: a copy frozen at 44 MB of 1.1 GB left an unplayable file under the final name. */
class RecordingArchiveCopyTest {
    private val name = "1sar7djmtrc4td_23Nov2026_131404_PST.mp4"

    private fun dir(): File = Files.createTempDirectory("archive").toFile().apply { deleteOnExit() }
    private fun source(bytes: Int): File = File.createTempFile("merged", ".tmp.mp4").apply {
        deleteOnExit()
        writeBytes(ByteArray(bytes) { (it % 251).toByte() })
    }
    private fun dashName(folder: File): (String) -> String = { preferred ->
        generateSequence(2) { it + 1 }.map { preferred.removeSuffix(".mp4") + "-$it.mp4" }
            .first { !File(folder, it).exists() }
    }

    @Test fun placementReplacesOnlyShorterFilesAndSkipsFinishedOnes() {
        assertEquals(Placement.CREATE, RecordingArchiveCopy.placement(null, 10))
        assertEquals(Placement.REPLACE_SHORTER, RecordingArchiveCopy.placement(4, 10))
        assertEquals(Placement.ALREADY_COMPLETE, RecordingArchiveCopy.placement(10, 10))
        assertEquals(Placement.NEW_NAME, RecordingArchiveCopy.placement(11, 10))
    }

    @Test fun newRecordingIsWrittenUnderItsNameWithNoPartialLeft() {
        val folder = dir(); val src = source(4096)
        val result = RecordingArchiveCopy.copy(src, FileRecordingArchiveFolder(folder), name, dashName(folder))
        assertEquals(Placement.CREATE, result.placement)
        assertEquals(name, result.name)
        assertArrayEquals(src.readBytes(), File(folder, name).readBytes())
        assertEquals(listOf(name), folder.list()!!.toList())
    }

    @Test fun shorterCutOffCopyIsReplacedInsteadOfAddingADashTwoFile() {
        val folder = dir(); val src = source(4096)
        File(folder, name).writeBytes(src.readBytes().copyOf(1000))
        val result = RecordingArchiveCopy.copy(src, FileRecordingArchiveFolder(folder), name, dashName(folder))
        assertEquals(Placement.REPLACE_SHORTER, result.placement)
        assertArrayEquals(src.readBytes(), File(folder, name).readBytes())
        assertEquals(listOf(name), folder.list()!!.toList())
    }

    @Test fun finishedCopyIsLeftAloneAndNothingIsWritten() {
        val folder = dir(); val src = source(4096)
        File(folder, name).writeBytes(src.readBytes())
        val before = File(folder, name).lastModified()
        val result = RecordingArchiveCopy.copy(src, FileRecordingArchiveFolder(folder), name, dashName(folder))
        assertEquals(Placement.ALREADY_COMPLETE, result.placement)
        assertEquals(0L, result.bytesCopied)
        assertEquals(before, File(folder, name).lastModified())
        assertEquals(listOf(name), folder.list()!!.toList())
    }

    @Test fun longerFileWithTheNameIsAnotherRecordingSoADashNameIsUsed() {
        val folder = dir(); val src = source(4096)
        File(folder, name).writeBytes(ByteArray(8000))
        val result = RecordingArchiveCopy.copy(src, FileRecordingArchiveFolder(folder), name, dashName(folder))
        assertEquals(Placement.NEW_NAME, result.placement)
        assertEquals("1sar7djmtrc4td_23Nov2026_131404_PST-2.mp4", result.name)
        assertEquals(8000L, File(folder, name).length())
    }

    @Test fun copyCutOffMidWriteNeverLeavesAFileUnderTheFinalName() {
        val folder = dir(); val src = source(64 * 1024)
        val failing = object : RecordingArchiveFolder by FileRecordingArchiveFolder(folder) {
            override fun openOutput(name: String): OutputStream {
                var written = 0
                return object : FilterOutputStream(File(folder, name).outputStream()) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (written + len > 20_000) throw IOException("frozen mid-copy")
                        written += len; out.write(b, off, len)
                    }
                }
            }
        }
        assertThrows(IOException::class.java) {
            RecordingArchiveCopy.copy(src, failing, name, dashName(folder))
        }
        assertFalse(File(folder, name).exists())
        assertTrue("partial removed: ${folder.list()!!.toList()}", folder.list()!!.isEmpty())
    }

    @Test fun shortWriteIsRejectedAndTheOldFileKept() {
        val folder = dir(); val src = source(4096)
        File(folder, name).writeBytes(ByteArray(100))
        val short = object : RecordingArchiveFolder by FileRecordingArchiveFolder(folder) {
            override fun openOutput(name: String): OutputStream =
                object : FilterOutputStream(File(folder, name).outputStream()) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, minOf(len, 10))
                }
        }
        val error = assertThrows(IllegalStateException::class.java) {
            RecordingArchiveCopy.copy(src, short, name, dashName(folder))
        }
        assertTrue(error.message!!.contains("incomplete"))
        assertEquals(100L, File(folder, name).length())
        assertEquals(listOf(name), folder.list()!!.toList())
    }

    @Test fun partialLeftByAnEarlierFrozenCopyIsDiscardedFirst() {
        val folder = dir(); val src = source(4096)
        File(folder, RecordingArchiveCopy.partialName(name)).writeBytes(ByteArray(999))
        RecordingArchiveCopy.copy(src, FileRecordingArchiveFolder(folder), name, dashName(folder))
        assertEquals(listOf(name), folder.list()!!.toList())
        assertEquals(4096L, File(folder, name).length())
    }

    @Test fun partialNameIsHidden() {
        assertEquals(".$name.partial", RecordingArchiveCopy.partialName(name))
    }

    @Test fun archiveCopyNeverOpensTheFinalNameForWriting() {
        val src = File("src/main/java/org/ncssar/rid2caltopo/data/MediaMTXRecordingSync.kt").readText()
        val body = src.substringAfter("private fun copyFileInto(").substringBefore("private fun deleteEmptyDirectories(")
        assertTrue(body.contains("RecordingArchiveCopy.copy("))
        assertFalse("no direct write to the final name", body.contains("createFile(mimeTypeFor"))
    }
}
