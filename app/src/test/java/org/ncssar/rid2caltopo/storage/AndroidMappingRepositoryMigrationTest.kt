package org.ncssar.rid2caltopo.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Random

class AndroidMappingRepositoryMigrationTest {
    private class Tree(val root: File) : ArchiveTree {
        var failWriteAt = Int.MAX_VALUE
        var failDeleteAt = Int.MAX_VALUE
        var writes = 0
        var deletes = 0
        private fun file(path: String) = File(root, path)
        override fun exists(path: String) = file(path).exists()
        override fun mkdirs(path: String) { file(path).mkdirs() }
        override fun list(path: String) = file(path).listFiles().orEmpty().map { ArchiveTree.Entry(it.name, it.isDirectory, it.length(), it.lastModified()) }
        override fun read(path: String): InputStream = file(path).inputStream()
        override fun write(path: String, input: InputStream) {
            val out = file(path); out.parentFile.mkdirs()
            if (++writes == failWriteAt) {
                // Torn destination, as a provider that died mid-copy would leave.
                out.writeBytes(ByteArray(7).also { input.read(it) }); throw IOException("provider interrupted")
            }
            out.outputStream().use { input.copyTo(it) }
        }
        override fun rename(from: String, to: String) = !exists(to) && file(from).renameTo(file(to))
        override fun delete(path: String) {
            if (++deletes == failDeleteAt) throw IOException("delete interrupted")
            check(!file(path).exists() || file(path).deleteRecursively())
        }
    }

    private val migration = AndroidMappingRepositoryMigration
    private val mission = "2e30e0b4"

    private fun withTree(block: (Tree, File) -> Unit) {
        val base = Files.createTempDirectory("mapping-repo").toFile()
        try { block(Tree(File(base, "archive")), base) } finally { base.deleteRecursively() }
    }

    private fun put(tree: Tree, path: String, bytes: ByteArray) {
        val f = File(tree.root, path); f.parentFile.mkdirs(); f.writeBytes(bytes)
    }
    private fun put(tree: Tree, path: String, text: String) = put(tree, path, text.toByteArray())

    /** Sierra Buttes style mission: 134 originals of differing sizes plus derived data in each area. */
    private fun seedSierraButtes(tree: Tree, active: Boolean = false): Map<String, ByteArray> {
        val random = Random(42)
        val originals = linkedMapOf<String, ByteArray>()
        for (i in 0 until 134) {
            val bytes = ByteArray(1_000 + i * 37).also(random::nextBytes)
            val name = "originals/DJI_%04d.jpg".format(i + 1)
            originals[name] = bytes
            put(tree, "cache/local-maps/mapping-missions/$mission/$name", bytes)
        }
        put(tree, "cache/local-maps/mapping-missions/$mission/mission.json", """{"id":"$mission"}""")
        put(tree, "cache/local-maps/mapping-missions/$mission/processing-status.json", """{"active":$active,"stage":"Map ready"}""")
        put(tree, "cache/local-maps/mapping-layers/layer-1/tiles/0/0.png", "tile")
        put(tree, "cache/local-maps/mapping-processing/$mission/checkpoint.bin", "checkpoint")
        put(tree, "cache/local-maps/mapping-storage-stats/stats.json", "{}")
        return originals
    }

    private fun assertOriginalsAtNewPath(tree: Tree, originals: Map<String, ByteArray>) {
        for ((name, bytes) in originals) {
            val moved = File(tree.root, "mapping/missions/$mission/$name")
            assertTrue("missing $name", moved.isFile)
            assertEquals("size $name", bytes.size.toLong(), moved.length())
            assertArrayEquals("bytes $name", bytes, moved.readBytes())
        }
        assertEquals(134, File(tree.root, "mapping/missions/$mission/originals").listFiles()!!.count { it.name.endsWith(".jpg") })
    }

    @Test fun sierraButtesOriginalsSurviveMigrationAndLegacyIsRemovedAfterCommit() = withTree { tree, _ ->
        val originals = seedSierraButtes(tree)
        assertFalse(migration.usesCurrentLayout(tree))
        var markerWhenCommitted = false
        var legacyWhenCommitted = false
        val result = migration.migrate(tree, onCommitted = {
            markerWhenCommitted = tree.exists(MappingRepositoryLayout.MARKER)
            legacyWhenCommitted = tree.exists("cache/local-maps/mapping-missions/$mission/originals/DJI_0001.jpg")
        })
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED, result.outcome)
        assertTrue("marker must be durable before removal", markerWhenCommitted)
        assertTrue("originals must still exist at commit time", legacyWhenCommitted)
        assertOriginalsAtNewPath(tree, originals)
        assertEquals("tile", File(tree.root, "mapping/layers/layer-1/tiles/0/0.png").readText())
        assertEquals("checkpoint", File(tree.root, "mapping/processing/$mission/checkpoint.bin").readText())
        assertEquals("{}", File(tree.root, "mapping/stats/stats.json").readText())
        assertTrue(File(tree.root, "mapping/.nomedia").isFile)
        assertTrue(tree.exists(MappingRepositoryLayout.MARKER))
        assertFalse(tree.exists("cache/local-maps"))
        assertTrue(migration.usesCurrentLayout(tree))
        assertEquals(134 + 5, result.files)
        assertEquals(result.files, result.removed)
    }

    @Test fun defersWhileAnyMissionStatusIsActiveAndLosesNothing() = withTree { tree, _ ->
        val originals = seedSierraButtes(tree, active = true)
        val result = migration.migrate(tree)
        assertEquals(AndroidMappingRepositoryMigration.Outcome.DEFERRED_ACTIVE, result.outcome)
        assertFalse(tree.exists(MappingRepositoryLayout.MARKER))
        assertFalse(tree.exists("mapping/missions"))
        for ((name, bytes) in originals) assertArrayEquals(bytes, File(tree.root, "cache/local-maps/mapping-missions/$mission/$name").readBytes())
        assertFalse(migration.usesCurrentLayout(tree))

        // A second mission flipping active later also blocks (ANY active status).
        put(tree, "cache/local-maps/mapping-missions/00000000/flight-a/processing-status.json", """{"active":true}""")
        File(tree.root, "cache/local-maps/mapping-missions/$mission/processing-status.json").writeText("""{"active":false}""")
        assertEquals(AndroidMappingRepositoryMigration.Outcome.DEFERRED_ACTIVE, migration.migrate(tree).outcome)

        File(tree.root, "cache/local-maps/mapping-missions/00000000/flight-a/processing-status.json").writeText("""{"active":false}""")
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED, migration.migrate(tree).outcome)
        assertOriginalsAtNewPath(tree, originals)
    }

    @Test fun defersWhenLocalProjectionOrInProcessReconstructionIsActive() = withTree { tree, base ->
        val originals = seedSierraButtes(tree)
        val local = File(base, "files/mapping-missions/$mission").apply { mkdirs() }
        File(local, "processing-status.json").writeText("""{"active":true}""")
        assertEquals(AndroidMappingRepositoryMigration.Outcome.DEFERRED_ACTIVE,
            migration.migrate(tree, localMissionRoots = listOf(File(base, "files/mapping-missions"))).outcome)
        File(local, "processing-status.json").writeText("""{"active":false}""")
        assertEquals(AndroidMappingRepositoryMigration.Outcome.DEFERRED_ACTIVE,
            migration.migrate(tree, processingActive = { true }).outcome)
        assertFalse(tree.exists("mapping/missions"))
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED,
            migration.migrate(tree, localMissionRoots = listOf(File(base, "files/mapping-missions"))).outcome)
        assertOriginalsAtNewPath(tree, originals)
    }

    @Test fun rerunIsIdempotentAndTouchesNothing() = withTree { tree, _ ->
        val originals = seedSierraButtes(tree)
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED, migration.migrate(tree).outcome)
        val marker = File(tree.root, MappingRepositoryLayout.MARKER).readText()
        val writesAfterFirst = tree.writes
        val again = migration.migrate(tree)
        assertEquals(AndroidMappingRepositoryMigration.Outcome.NOTHING_TO_DO, again.outcome)
        assertEquals(writesAfterFirst, tree.writes)
        assertEquals(marker, File(tree.root, MappingRepositoryLayout.MARKER).readText())
        assertOriginalsAtNewPath(tree, originals)
    }

    @Test fun interruptedCopyKeepsEveryOriginalAndResumes() = withTree { tree, _ ->
        val originals = seedSierraButtes(tree)
        tree.failWriteAt = 60
        assertThrows(IOException::class.java) { migration.migrate(tree) }
        assertFalse(tree.exists(MappingRepositoryLayout.MARKER))
        for ((name, bytes) in originals) assertArrayEquals(bytes, File(tree.root, "cache/local-maps/mapping-missions/$mission/$name").readBytes())
        assertFalse(migration.usesCurrentLayout(tree))
        tree.failWriteAt = Int.MAX_VALUE
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED, migration.migrate(tree).outcome)
        assertOriginalsAtNewPath(tree, originals)
        assertFalse(tree.exists("cache/local-maps"))
    }

    @Test fun interruptedRemovalAfterCommitIsFinishedByNextRun() = withTree { tree, _ ->
        val originals = seedSierraButtes(tree)
        tree.failDeleteAt = 1
        assertThrows(IOException::class.java) { migration.migrate(tree) }
        // Committed and fully copied; legacy still intact, so nothing was lost.
        assertTrue(tree.exists(MappingRepositoryLayout.MARKER))
        assertTrue(migration.usesCurrentLayout(tree))
        assertOriginalsAtNewPath(tree, originals)
        assertTrue(tree.exists("cache/local-maps/mapping-missions/$mission/originals/DJI_0001.jpg"))
        tree.failDeleteAt = Int.MAX_VALUE
        val result = migration.migrate(tree)
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED, result.outcome)
        assertFalse(tree.exists("cache/local-maps"))
        assertOriginalsAtNewPath(tree, originals)
    }

    @Test fun tornDestinationFromEarlierCrashIsReplacedBeforeCommit() = withTree { tree, _ ->
        val originals = seedSierraButtes(tree)
        put(tree, "mapping/missions/$mission/originals/DJI_0001.jpg", "torn")
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED, migration.migrate(tree).outcome)
        assertOriginalsAtNewPath(tree, originals)
    }

    @Test fun fileAddedToLegacyDuringCommitIsRetainedThenSweptNextRun() = withTree { tree, _ ->
        val originals = seedSierraButtes(tree)
        val late = "cache/local-maps/mapping-missions/$mission/originals/DJI_9999.jpg"
        val result = migration.migrate(tree, onCommitted = { put(tree, late, "late original") })
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED_WITH_LEFTOVERS, result.outcome)
        assertEquals("late original", File(tree.root, late).readText())
        assertOriginalsAtNewPath(tree, originals)
        // Next session: marker present, sweep copies the leftover and removes legacy.
        assertTrue(migration.usesCurrentLayout(tree))
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED, migration.migrate(tree).outcome)
        assertEquals("late original", File(tree.root, "mapping/missions/$mission/originals/DJI_9999.jpg").readText())
        assertFalse(tree.exists("cache/local-maps"))
    }

    @Test fun sweepNeverOverwritesNewerDestinationAndKeepsConflictingLegacy() = withTree { tree, _ ->
        put(tree, MappingRepositoryLayout.MARKER, "{}")
        put(tree, "mapping/missions/$mission/mission.json", "newer")
        put(tree, "cache/local-maps/mapping-missions/$mission/mission.json", "older")
        put(tree, "cache/local-maps/mapping-missions/$mission/originals/a.jpg", "a")
        val result = migration.migrate(tree)
        assertEquals(AndroidMappingRepositoryMigration.Outcome.COMPLETED_WITH_LEFTOVERS, result.outcome)
        assertEquals("newer", File(tree.root, "mapping/missions/$mission/mission.json").readText())
        assertEquals("older", File(tree.root, "cache/local-maps/mapping-missions/$mission/mission.json").readText())
        assertEquals("a", File(tree.root, "mapping/missions/$mission/originals/a.jpg").readText())
        assertFalse(tree.exists("cache/local-maps/mapping-missions/$mission/originals/a.jpg"))
    }

    @Test fun freshArchiveUsesCurrentLayoutAndGetsMarker() = withTree { tree, _ ->
        tree.mkdirs("cache")
        assertTrue(migration.usesCurrentLayout(tree))
        assertTrue(migration.needsWork(tree))
        assertEquals(AndroidMappingRepositoryMigration.Outcome.NOTHING_TO_DO, migration.migrate(tree).outcome)
        assertTrue(tree.exists(MappingRepositoryLayout.MARKER))
        assertFalse(migration.needsWork(tree))
    }

    @Test fun categoriesCountMappingAsProjectsNotCacheImagery() {
        assertEquals(StorageCategory.PROJECTS, storageCategory("mapping/missions/$mission/originals/DJI_0001.jpg"))
        assertEquals(StorageCategory.PROJECTS, storageCategory("cache/local-maps/mapping-missions/$mission/originals/DJI_0001.jpg"))
        assertEquals(StorageCategory.LAYERS, storageCategory("mapping/layers/l/tiles/0/0.png"))
        assertEquals(StorageCategory.LAYERS, storageCategory("cache/local-maps/mapping-layers/l/model.bin"))
        assertEquals(StorageCategory.CHECKPOINTS, storageCategory("mapping/processing/$mission/c.bin"))
        assertEquals(StorageCategory.CHECKPOINTS, storageCategory("cache/local-maps/mapping-processing/$mission/c.bin"))
        assertEquals(StorageCategory.METADATA, storageCategory("mapping/stats/stats.json"))
        assertEquals(StorageCategory.METADATA, storageCategory("cache/local-maps/mapping-storage-stats/stats.json"))
        assertEquals(StorageCategory.METADATA, storageCategory("mapping/.mapping-repo-migration-v1-done"))
        assertEquals(StorageCategory.METADATA, storageCategory("mapping/.nomedia"))
        assertEquals(StorageCategory.IMAGERY, storageCategory("cache/tile_cache_v1/a.db"))
    }

    @Test fun cacheCodeRecognizesMappingPaths() {
        assertTrue(MappingRepositoryLayout.isMappingPath("mapping/missions/x/a.jpg"))
        assertTrue(MappingRepositoryLayout.isMappingPath("/storage/emulated/0/Archive/mapping/layers/l"))
        assertTrue(MappingRepositoryLayout.isMappingPath("content://p/tree/primary%3AArchive/document/primary%3AArchive%2Fmapping%2Fmissions%2Fx%2Fa.jpg"))
        assertTrue(MappingRepositoryLayout.isMappingPath("cache/local-maps/mapping-processing/x"))
        assertFalse(MappingRepositoryLayout.isMappingPath("cache/dem/a.tif"))
        assertFalse(MappingRepositoryLayout.isMappingPath("cache/surface_v1/a.png"))
        assertFalse(MappingRepositoryLayout.isMappingPath("cache/tile_cache_v1/mapping.db"))
    }
}
