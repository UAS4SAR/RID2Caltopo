package org.ncssar.rid2caltopo.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class VerifiedArchiveTreeTest {
    private class Tree(val root: File) : ArchiveTree {
        var failPublication = false
        var allowRename = true
        var failMetadata = false
        var failMutationAt = Int.MAX_VALUE
        var mutations = 0
        val writes = mutableListOf<String>()
        private fun mutation() { if (++mutations == failMutationAt) throw IOException("provider interrupted at mutation $mutations") }
        private fun file(path: String) = File(root, path)
        override fun exists(path: String) = file(path).exists()
        override fun mkdirs(path: String) { file(path).mkdirs() }
        override fun list(path: String) = file(path).listFiles().orEmpty().map { ArchiveTree.Entry(it.name, it.isDirectory, it.length(), it.lastModified()) }
        override fun read(path: String): InputStream = file(path).inputStream()
        override fun write(path: String, input: InputStream) {
            mutation()
            writes.add(path)
            val out = file(path); out.parentFile.mkdirs()
            if (path == "cache/data.r2c-info" && failMetadata) {
                failMetadata = false; out.writeText("{torn"); throw IOException("metadata interrupted")
            }
            if (path == "cache/data" && failPublication) {
                failPublication = false; out.writeText("torn"); throw IOException("provider interrupted")
            }
            out.outputStream().use { input.copyTo(it) }
        }
        override fun rename(from: String, to: String): Boolean {
            mutation()
            return allowRename && !exists(to) && file(from).renameTo(file(to))
        }
        override fun delete(path: String) { mutation(); check(!file(path).exists() || file(path).deleteRecursively()) }
    }
    @Test fun verifiedCopyRetainsOriginalAndConflictsDoNotOverwriteEitherCopy() {
        val root = Files.createTempDirectory("archive-copy").toFile()
        try {
            val tree = Tree(File(root, "archive")); val writer = VerifiedArchiveTree(tree)
            val source = File(root, "source").apply { writeText("original") }
            writer.put(source, "cache/data", replace = false)
            assertEquals("original", tree.read("cache/data").reader().readText())
            source.writeText("different")
            assertThrows(IllegalStateException::class.java) { writer.put(source, "cache/data", replace = false) }
            assertEquals("different", source.readText())
            assertEquals("original", tree.read("cache/data").reader().readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedNonAtomicPublicationRecoversWithoutLosingPreviousOrNewBytes() {
        val root = Files.createTempDirectory("archive-interrupt").toFile()
        try {
            val tree = Tree(File(root, "archive")).apply { allowRename = false }
            val writer = VerifiedArchiveTree(tree)
            val source = File(root, "source").apply { writeText("previous") }
            writer.put(source, "cache/data")
            source.writeText("replacement")
            tree.failPublication = true
            assertThrows(IOException::class.java) { writer.put(source, "cache/data") }
            assertEquals("replacement", source.readText())
            VerifiedArchiveTree(tree).recover()
            assertEquals("replacement", tree.read("cache/data").reader().readText())
            assertTrue(tree.list(VerifiedArchiveTree.JOURNAL).isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationNeverRemovesSourceOrExistingArchiveData() {
        val root = Files.createTempDirectory("archive-cancel").toFile()
        try {
            val tree = Tree(File(root, "archive")); val source = File(root, "source").apply { writeText("keep") }
            VerifiedArchiveTree(tree).put(source, "cache/data")
            val writer = VerifiedArchiveTree(tree) { throw java.util.concurrent.CancellationException() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { writer.put(source, "cache/data") }
            assertEquals("keep", source.readText())
            assertEquals("keep", tree.read("cache/data").reader().readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedFirstPublicationRecoversWithoutAnOlderVersion() {
        val root = Files.createTempDirectory("archive-first").toFile()
        try {
            val tree = Tree(File(root, "archive")).apply { allowRename = false; failPublication = true }
            val source = File(root, "source").apply { writeText("first retained copy") }
            assertThrows(IOException::class.java) { VerifiedArchiveTree(tree).put(source, "cache/data") }
            VerifiedArchiveTree(tree).recover()
            assertEquals(source.readText(), tree.read("cache/data").reader().readText())
            assertTrue(tree.list(VerifiedArchiveTree.JOURNAL).isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun unsafePathsAreRejectedBeforeProviderWrites() {
        for (path in listOf("../data", "/data", "cache/../data", "cache//data", "cache/")) {
            assertThrows(IllegalArgumentException::class.java) { VerifiedArchiveTree.checked(path) }
        }
    }

    @Test fun dataAndMetadataUseOneJournalAndIdenticalRetainsDoNotWriteAgain() {
        val root = Files.createTempDirectory("archive-metadata").toFile()
        try {
            val tree = Tree(File(root, "archive"))
            val source = File(root, "source").apply { writeText("photo") }
            val writer = VerifiedArchiveTree(tree)
            writer.put(source, "cache/data", modified = 1234)
            assertEquals(1, tree.writes.count { it.startsWith(VerifiedArchiveTree.JOURNAL + "/") })
            val metadata = tree.read("cache/data.r2c-info").use { VerifiedArchiveTree.json(it) }
            assertEquals(1234L, metadata.getLong("modified"))
            assertEquals(source.inputStream().use { VerifiedArchiveTree.digest(it) }, metadata.getString("sha256"))
            tree.writes.clear()
            writer.put(source, "cache/data", modified = 1234)
            assertTrue(tree.writes.isEmpty())
            writer.put(source, "cache/data", modified = 5678)
            assertFalse(tree.writes.any { it.contains(".r2c-stage-") }) // Metadata-only repair reuses verified data.
            assertEquals(5678L, tree.read("cache/data.r2c-info").use { VerifiedArchiveTree.json(it) }.getLong("modified"))
            assertEquals("photo", source.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun tornMetadataRecoversForNewFilesReplacementsAndMetadataOnlyUpdates() {
        for (rename in listOf(true, false)) for (mode in 0..2) {
            val root = Files.createTempDirectory("archive-torn-metadata").toFile()
            try {
                val tree = Tree(File(root, "archive")).apply { allowRename = rename }
                val source = File(root, "source").apply { writeText("previous") }
                val writer = VerifiedArchiveTree(tree)
                if (mode != 0) writer.put(source, "cache/data", modified = 100)
                if (mode != 2) source.writeText("replacement")
                tree.failMetadata = true
                assertThrows(IOException::class.java) { writer.put(source, "cache/data", modified = 200) }
                assertTrue(tree.list(VerifiedArchiveTree.JOURNAL).any { it.name.endsWith(".json") })
                VerifiedArchiveTree(tree).recover()
                assertEquals(source.readText(), tree.read("cache/data").reader().readText())
                val metadata = tree.read("cache/data.r2c-info").use { VerifiedArchiveTree.json(it) }
                assertEquals(200L, metadata.getLong("modified"))
                assertEquals(source.inputStream().use { VerifiedArchiveTree.digest(it) }, metadata.getString("sha256"))
                assertTrue(tree.list(VerifiedArchiveTree.JOURNAL).isEmpty())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun recoveryPreservesConsistentDataAndMetadataAtEveryProviderMutationBoundary() {
        for (rename in listOf(true, false)) for (boundary in 1..14) {
            val root = Files.createTempDirectory("archive-boundaries").toFile()
            try {
                val tree = Tree(File(root, "archive")).apply { allowRename = rename }
                val source = File(root, "source").apply { writeText("previous") }
                VerifiedArchiveTree(tree).put(source, "cache/data", modified = 100)
                source.writeText("replacement")
                tree.mutations = 0; tree.failMutationAt = boundary
                try { VerifiedArchiveTree(tree).put(source, "cache/data", modified = 200) } catch (_: IOException) { }
                tree.failMutationAt = Int.MAX_VALUE
                VerifiedArchiveTree(tree).recover()
                val retained = tree.read("cache/data").reader().readText()
                assertTrue(retained == "previous" || retained == "replacement")
                val metadata = tree.read("cache/data.r2c-info").use { VerifiedArchiveTree.json(it) }
                assertEquals(if (retained == "previous") 100L else 200L, metadata.getLong("modified"))
                assertEquals(retained.byteInputStream().use { VerifiedArchiveTree.digest(it) }, metadata.getString("sha256"))
                assertEquals("replacement", source.readText())
                assertTrue(tree.list(VerifiedArchiveTree.JOURNAL).isEmpty())
            } finally { root.deleteRecursively() }
        }
    }
}
