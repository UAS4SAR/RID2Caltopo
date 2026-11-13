package org.ncssar.rid2caltopo.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagedCardLogTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Card file stand-in: counts open handles and can simulate an unmounted card. */
    private class FakeCard(val file: File) : StagedCardLog.CardTarget {
        var mounted = true
        var failAfterBytes = -1 // >= 0: throw after writing this many bytes in a batch
        var opens = 0
        var openHandles = 0
        override fun length(): Long = if (!mounted) -1 else if (file.isFile) file.length() else -1
        override fun openAppend(): OutputStream {
            if (!mounted) throw IOException("card unmounted")
            opens++; openHandles++
            val delegate = FileOutputStream(file, true)
            val limit = failAfterBytes
            return object : OutputStream() {
                var written = 0
                override fun write(b: Int) {
                    if (limit in 0..written) throw IOException("card vanished mid-batch")
                    delegate.write(b); written++
                }
                override fun write(b: ByteArray, off: Int, len: Int) { for (i in 0 until len) write(b[off + i].toInt()) }
                override fun flush() = delegate.flush()
                override fun close() { delegate.close(); openHandles-- }
            }
        }
    }

    private fun newCard(): FakeCard = FakeCard(File(tmp.newFolder(), "Log_card.txt").apply { createNewFile() })

    private fun OutputStream.line(text: String) { write(text.toByteArray()); flush() }

    @Test
    fun batchedAppendCopiesExactBytesWithOneOpenPerBatch() {
        val card = newCard()
        val log = StagedCardLog.create(tmp.newFolder("staging"), "Log_a.txt", "content://card/a", card)
        val out = log.openPrivateStream()
        out.line("first\n  ")
        assertEquals(0L, card.file.length()) // nothing reaches the card until a batch runs
        assertEquals(StagedCardLog.Result.APPENDED, log.syncToCard(0))
        out.line("second\n  "); out.line("third\n  ")
        assertEquals(StagedCardLog.Result.APPENDED, log.syncToCard(1))
        assertEquals(StagedCardLog.Result.UP_TO_DATE, log.syncToCard(2))
        out.close()
        assertEquals(2, card.opens)
        assertEquals("first\n  second\n  third\n  ", card.file.readText())
        assertArrayEquals(log.privateFile().readBytes(), card.file.readBytes())
        assertEquals(0L, log.pendingBytes())
    }

    @Test
    fun noCardHandleIsHeldBetweenBatchesEvenWhenABatchFails() {
        val card = newCard()
        val log = StagedCardLog.create(tmp.newFolder("staging"), "Log_b.txt", "k", card)
        log.openPrivateStream().use { out ->
            repeat(5) { i ->
                out.line("line $i\n")
                log.syncToCard(i.toLong())
                assertEquals(0, card.openHandles)
            }
            card.failAfterBytes = 2
            out.line("will fail\n")
            assertEquals(StagedCardLog.Result.FAILED, log.syncToCard(10))
            assertEquals(0, card.openHandles)
        }
        assertEquals(5 + 1, card.opens)
    }

    @Test
    fun cardMissingKeepsDataThenCatchesUpWithoutDuplicates() {
        val card = newCard()
        val log = StagedCardLog.create(tmp.newFolder("staging"), "Log_c.txt", "k", card)
        val out = log.openPrivateStream()
        out.line("before unmount\n")
        assertEquals(StagedCardLog.Result.APPENDED, log.syncToCard(0))

        card.mounted = false
        out.line("while card is gone\n")
        assertEquals(StagedCardLog.Result.FAILED, log.syncToCard(1_000))
        assertTrue(log.isFailing())
        assertFalse(log.shouldAttempt(1_000 + StagedCardLog.MIN_RETRY_MSEC - 1)) // backoff
        assertEquals(StagedCardLog.Result.FAILED, log.syncToCard(10_000))
        assertFalse(log.shouldAttempt(10_000 + 2 * StagedCardLog.MIN_RETRY_MSEC - 1))

        // Card back, but a batch dies part way through: the card length is re-read next time.
        card.mounted = true
        card.failAfterBytes = 6
        out.line("more\n")
        assertEquals(StagedCardLog.Result.FAILED, log.syncToCard(100_000))
        card.failAfterBytes = -1
        assertEquals(StagedCardLog.Result.APPENDED, log.syncToCard(200_000))
        assertFalse(log.isFailing())
        out.close()
        assertEquals("before unmount\nwhile card is gone\nmore\n", card.file.readText())
        assertArrayEquals(log.privateFile().readBytes(), card.file.readBytes())
        assertTrue(log.discardPrivateIfCaughtUp())
        assertFalse(log.privateFile().exists())
    }

    @Test
    fun leftoverPrivateLogFromKilledRunIsFlushedOnRestart() {
        val staging = tmp.newFolder("staging")
        val card = newCard()
        val killed = StagedCardLog.create(staging, "Log_old.txt", "content://card/old", card)
        killed.openPrivateStream().use { out ->
            out.line("copied before kill\n")
            killed.syncToCard(0)
            out.line("written after last batch, then SIGINT\n") // no finish(): process killed
        }
        val liveCard = newCard()
        val live = StagedCardLog.create(staging, "Log_new.txt", "content://card/new", liveCard)
        live.openPrivateStream().use { it.line("live session\n") }

        val report = StagedCardLog.flushLeftovers(staging, setOf("Log_new.txt"),
            { key -> if (key == "content://card/old") card else liveCard }, 1_000L, Long.MAX_VALUE)

        assertEquals(listOf("Log_old.txt"), report.flushed)
        assertEquals("copied before kill\nwritten after last batch, then SIGINT\n", card.file.readText())
        assertFalse(File(staging, "Log_old.txt").exists())
        assertFalse(File(staging, "Log_old.txt" + StagedCardLog.TARGET_SUFFIX).exists())
        // The live session is untouched.
        assertTrue(File(staging, "Log_new.txt").exists())
        assertEquals(0L, liveCard.file.length())
    }

    @Test
    fun leftoverIsKeptWhileCardMissingAndExpiresWhenOld() {
        val staging = tmp.newFolder("staging")
        val card = newCard()
        val killed = StagedCardLog.create(staging, "Log_k.txt", "content://card/k", card)
        killed.openPrivateStream().use { it.line("unsent\n") }
        card.mounted = false
        val now = System.currentTimeMillis()

        val kept = StagedCardLog.flushLeftovers(staging, emptySet(), { card }, now, 60_000L)
        assertEquals(listOf("Log_k.txt"), kept.kept)
        assertTrue(File(staging, "Log_k.txt").exists())

        card.mounted = true
        val caught = StagedCardLog.flushLeftovers(staging, emptySet(), { card }, now, 60_000L)
        assertEquals(listOf("Log_k.txt"), caught.flushed)
        assertEquals("unsent\n", card.file.readText())

        val other = StagedCardLog.create(staging, "Log_x.txt", "content://card/x", card)
        other.openPrivateStream().use { it.line("never deliverable\n") }
        val expired = StagedCardLog.flushLeftovers(staging, emptySet(), { null },
            other.privateFile().lastModified() + 120_000L, 60_000L)
        assertEquals(listOf("Log_x.txt"), expired.expired)
        assertFalse(other.privateFile().exists())
    }

    @Test
    fun cardFileThatIsNotAPrefixIsNeverWritten() {
        val staging = tmp.newFolder("staging")
        val card = newCard()
        card.file.writeText("someone else's much longer content\n")
        val log = StagedCardLog.create(staging, "Log_m.txt", "k", card)
        log.openPrivateStream().use { it.line("short\n") }
        card.mounted = false
        log.syncToCard(0) // forces a resync on the next batch
        card.mounted = true
        assertEquals(StagedCardLog.Result.MISMATCH, log.syncToCard(100_000))
        assertEquals("someone else's much longer content\n", card.file.readText())
        assertFalse(log.discardPrivateIfCaughtUp())
    }

    @Test
    fun logWriterNeverOpensTheArchiveDirFileDirectly() {
        val source = projectSource("app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoClient.java")
        val init = source.substring(source.indexOf("private static void InitArchiveDirLocked()"),
            source.indexOf("static String BuildDebugLogFilename("))
        assertTrue(init.contains("SessionLogCardSync.start(ctxt, filepath, logPath)"))
        assertFalse(init.contains("resolver.openOutputStream(logPath)"))
        val sync = projectSource("app/src/main/java/org/ncssar/rid2caltopo/data/SessionLogCardSync.java")
        assertTrue(sync.contains("openOutputStream(uri, \"wa\")"))
        assertTrue(sync.contains("getNoBackupFilesDir()"))
    }

    private fun projectSource(relativePath: String): String {
        val wd = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(wd, relativePath), File(wd.parentFile ?: wd, relativePath))
            .first(File::isFile).readText()
    }
}
