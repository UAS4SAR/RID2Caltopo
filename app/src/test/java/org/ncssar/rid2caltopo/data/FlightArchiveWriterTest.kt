package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FlightArchiveWriterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val good = FlightKmz.archive("A", listOf(FlightKmzPoint(39.0, -121.0, 1.0)), emptyList())
    private val better = FlightKmz.archive("B", listOf(FlightKmzPoint(39.1, -121.1, 1.0)), emptyList())

    @Test fun plainWriterReplacesVerifiesAndSweeps() {
        val root = temporaryFolder.newFolder("archive")
        val day = File(root, "tracks-06Oct2026").apply { mkdirs() }
        val target = File(day, "RID-1.kmz")
        PlainFileSafeWriter.replace(target, good)
        assertArrayEquals(good, target.readBytes())
        assertFalse(File(day, "RID-1.kmz.partial").exists())
        // Invalid content never replaces the existing file.
        try { PlainFileSafeWriter.replace(target, "torn".toByteArray()); fail() } catch (_: IOException) {}
        assertArrayEquals(good, target.readBytes())
        PlainFileSafeWriter.replace(target, better)
        assertArrayEquals(better, target.readBytes())
        // Launch sweep: a complete partial is finished, a torn one is discarded.
        File(day, "RID-2.kmz.partial").writeBytes(good)
        File(day, "RID-3.json.partial").writeText("{\"type\":")
        File(root, "RID-4.json.partial").writeText("{\"ok\":true}")
        val actions = PlainFileSafeWriter.sweep(root).sorted()
        assertEquals(listOf("completed RID-4.json.partial", "completed tracks-06Oct2026/RID-2.kmz.partial",
            "discarded tracks-06Oct2026/RID-3.json.partial"), actions)
        assertArrayEquals(good, File(day, "RID-2.kmz").readBytes())
        assertFalse(File(day, "RID-3.json").exists())
        assertFalse(File(day, "RID-3.json.partial").exists())
        assertTrue(File(root, "RID-4.json").isFile)
    }

    /** In-memory SAF folder: no rename over an existing name, optional injected failures. */
    private class FakeFolder : ArchiveFolder {
        val files = linkedMapOf<String, ByteArray>()
        var failRenameTo: String? = null
        var failWrite = false
        override fun exists(name: String) = files.containsKey(name)
        override fun read(name: String) = files[name]?.copyOf()
        override fun write(name: String, mimeType: String, bytes: ByteArray) {
            if (failWrite) { files[name] = bytes.copyOf(bytes.size / 2); throw IOException("disk full") }
            files[name] = bytes.copyOf()
        }
        override fun rename(from: String, to: String): Boolean {
            if (to == failRenameTo) { failRenameTo = null; return false }
            if (!files.containsKey(from) || files.containsKey(to)) return false
            files[to] = files.remove(from)!!
            return true
        }
        override fun delete(name: String): Boolean { files.remove(name); return true }
    }

    private fun journal() = SafRewriteJournal(File(temporaryFolder.root, "journal-${System.nanoTime()}.json"))

    @Test fun safWriterUsesPartialBackupAndRenameWithAJournal() {
        val folder = FakeFolder()
        val journal = journal()
        SafArchiveWriter.replace(folder, "tracks-06Oct2026", "RID-1.kmz", FlightArchiveStore_KMZ, good, journal)
        assertEquals(setOf("RID-1.kmz"), folder.files.keys)
        SafArchiveWriter.replace(folder, "tracks-06Oct2026", "RID-1.kmz", FlightArchiveStore_KMZ, better, journal)
        assertArrayEquals(better, folder.files["RID-1.kmz"])
        assertEquals(setOf("RID-1.kmz"), folder.files.keys)
        assertTrue(journal.entries().isEmpty())
        // A torn write leaves the old file in place.
        folder.failWrite = true
        try { SafArchiveWriter.replace(folder, "d", "RID-1.kmz", FlightArchiveStore_KMZ, good, journal); fail() } catch (_: IOException) {}
        assertArrayEquals(better, folder.files["RID-1.kmz"])
        assertEquals(setOf("RID-1.kmz"), folder.files.keys)
        folder.failWrite = false
        // The final rename failing (once) restores the backup.
        folder.failRenameTo = "RID-1.kmz"
        try { SafArchiveWriter.replace(folder, "d", "RID-1.kmz", FlightArchiveStore_KMZ, good, journal); fail() } catch (_: IOException) {}
        folder.failRenameTo = null
        assertArrayEquals(better, folder.files["RID-1.kmz"])
    }

    @Test fun safRecoveryFinishesOrRollsBackEveryCrashPoint() {
        // Crash after the partial was written and verified, before the old file moved aside.
        FakeFolder().apply {
            files["F.kmz"] = good; files["F.kmz.partial"] = better
            assertEquals(SafArchiveWriter.Recovery.COMPLETED, SafArchiveWriter.recover(this, "F.kmz"))
            assertArrayEquals(better, files["F.kmz"]); assertEquals(setOf("F.kmz"), files.keys)
        }
        // Crash after the old file became the backup, before the partial was renamed.
        FakeFolder().apply {
            files["F.kmz.bak"] = good; files["F.kmz.partial"] = better
            assertEquals(SafArchiveWriter.Recovery.COMPLETED, SafArchiveWriter.recover(this, "F.kmz"))
            assertArrayEquals(better, files["F.kmz"]); assertEquals(setOf("F.kmz"), files.keys)
        }
        // Crash after the rename, before the backup was deleted.
        FakeFolder().apply {
            files["F.kmz"] = better; files["F.kmz.bak"] = good
            assertEquals(SafArchiveWriter.Recovery.KEPT, SafArchiveWriter.recover(this, "F.kmz"))
            assertEquals(setOf("F.kmz"), files.keys)
        }
        // Torn partial with the old file moved aside: roll back.
        FakeFolder().apply {
            files["F.kmz.bak"] = good; files["F.kmz.partial"] = "torn".toByteArray()
            assertEquals(SafArchiveWriter.Recovery.ROLLED_BACK, SafArchiveWriter.recover(this, "F.kmz"))
            assertArrayEquals(good, files["F.kmz"]); assertEquals(setOf("F.kmz"), files.keys)
        }
        // Launch sweep walks the journal and clears it.
        val journal = journal()
        val folder = FakeFolder().apply { files["G.json.bak"] = "{\"a\":1}".toByteArray(); files["G.json.partial"] = "{".toByteArray() }
        journal.add(SafRewriteJournal.Entry("tracks-06Oct2026", "G.json"))
        journal.add(SafRewriteJournal.Entry("tracks-missing", "H.json"))
        val actions = SafArchiveWriter.sweep(journal) { day -> if (day == "tracks-06Oct2026") folder else null }
        assertEquals(listOf("rolled_back tracks-06Oct2026/G.json", "unresolved tracks-missing/H.json"), actions)
        assertEquals("{\"a\":1}", String(folder.files.getValue("G.json")))
        assertEquals(listOf(SafRewriteJournal.Entry("tracks-missing", "H.json")), journal.entries())
    }

    @Test fun rewriteQueueIsDurableAndDeduplicated() {
        val file = File(temporaryFolder.root, "flight-kmz-rewrites.json")
        val queue = FlightArchiveRewriteQueue(file)
        val job = FlightArchiveRewriteJob("RID-1", "tracks-06Oct2026", "RID-1_a.json", "RID-1_a.kmz")
        queue.enqueue(job)
        queue.enqueue(job.copy(attempts = 3))
        assertEquals(listOf(job), FlightArchiveRewriteQueue(file).jobs())
        queue.recordFailure(job.id, "folder unavailable")
        val failed = FlightArchiveRewriteQueue(file).jobs().single()
        assertEquals(1, failed.attempts)
        assertEquals("folder unavailable", failed.lastError)
        queue.complete(job.id)
        assertTrue(FlightArchiveRewriteQueue(file).jobs().isEmpty())
        assertFalse(File(temporaryFolder.root, "flight-kmz-rewrites.json.partial").exists())
        assertNull(FlightArchiveRewriteJob.fromJson(org.json.JSONObject()))
    }

    private companion object {
        const val FlightArchiveStore_KMZ = "application/vnd.google-earth.kmz"
    }
}
