package org.ncssar.rid2caltopo.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IndependentRecorderStateTest {
    private open class Tree(private val root: File) : ArchiveTree {
        fun file(path: String) = File(root, VerifiedArchiveTree.checked(path))
        override fun list(path: String) = file(path).listFiles().orEmpty().map { ArchiveTree.Entry(it.name, it.isDirectory, it.length(), it.lastModified()) }
        override fun exists(path: String) = file(path).exists()
        override fun read(path: String): InputStream = file(path).inputStream()
        override fun write(path: String, input: InputStream) { val f = file(path); f.parentFile!!.mkdirs(); f.outputStream().use { input.copyTo(it) } }
        override fun mkdirs(path: String) { file(path).mkdirs() }
        override fun rename(from: String, to: String): Boolean { file(to).parentFile!!.mkdirs(); return file(from).renameTo(file(to)) }
        override fun delete(path: String) { check(file(path).deleteRecursively()) }
    }
    private val journal = "${VerifiedArchiveTree.JOURNAL}/recorder-state"
    private val target = "application-logs/operational/unfinished-recordings/drone/flight.mp4.r2c-chunks/0000000000000000_chunk"

    @Test fun recordingCheckpointAndMarkerCanCommitWhileMapVerificationIsBlocked() {
        val root = Files.createTempDirectory("recorder-priority").toFile()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val archive = File(root, "archive")
            val bulkTree = object : Tree(archive) {
                override fun read(path: String): InputStream {
                    if (path.startsWith("cache/layer.r2c-stage-")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return super.read(path)
                }
            }
            val source = File(root, "frame-data").apply { writeBytes(ByteArray(8192) { (it % 251).toByte() }) }
            val bulk = pool.submit { VerifiedArchiveTree(bulkTree).put(source, "cache/layer") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pool.submit { VerifiedArchiveTree(Tree(archive), journal).put(source, target) }.get(1, TimeUnit.SECONDS)
            pool.submit { VerifiedArchiveTree(Tree(archive), "${VerifiedArchiveTree.JOURNAL}/queue-state").put(source, "application-logs/operational/queue-state/marker") }.get(1, TimeUnit.SECONDS)
            assertArrayEquals(source.readBytes(), Tree(archive).read(target).use { it.readBytes() })
            assertFalse(bulk.isDone)
            release.countDown(); bulk.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun incompleteRecordingCheckpointHasIndependentDurableRecovery() {
        val root = Files.createTempDirectory("recorder-recovery").toFile()
        val headerTarget = "application-logs/operational/unfinished-recordings/drone/flight.mp4.r2c-header"
        try {
            val archive = File(root, "archive")
            val interrupted = object : Tree(archive) {
                override fun rename(from: String, to: String): Boolean {
                    val renamed = super.rename(from, to)
                    if (renamed && to == headerTarget) throw IOException("simulated process loss after header publication")
                    return renamed
                }
            }
            val source = File(root, "data").apply { writeText("unfinished but durable") }
            assertThrows(IOException::class.java) { VerifiedArchiveTree(interrupted, journal).put(source, headerTarget) }
            VerifiedArchiveTree(Tree(archive)).recover()
            assertTrue(Tree(archive).list(journal).any { it.name.endsWith(".json") })
            VerifiedArchiveTree(Tree(archive), journal).recover()
            assertTrue(Tree(archive).list(journal).isEmpty())
            assertEquals("unfinished but durable", Tree(archive).read(headerTarget).reader().readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun changedArchiveSelectionCannotOverwriteTheLastRecordingCheckpoint() {
        val root = Files.createTempDirectory("recorder-selection").toFile()
        try {
            val tree = Tree(File(root, "archive")); var selected = true
            val writer = VerifiedArchiveTree(tree, journal, cancelled = { check(selected) })
            val source = File(root, "data").apply { writeText("saved prefix") }
            writer.put(source, target)
            selected = false; source.writeText("new prefix")
            assertThrows(IllegalStateException::class.java) { writer.put(source, target) }
            assertEquals("saved prefix", tree.read(target).reader().readText())
            assertEquals("new prefix", source.readText())
        } finally { root.deleteRecursively() }
    }
}
