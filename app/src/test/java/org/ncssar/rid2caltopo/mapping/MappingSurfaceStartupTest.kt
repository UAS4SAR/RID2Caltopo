package org.ncssar.rid2caltopo.mapping

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MappingSurfaceStartupTest {
    private fun root() = Files.createTempDirectory("surface-startup").toFile()
    private fun collection(root: File, paths: List<String>) {
        val file = File(root, "surface/collection.json"); file.parentFile!!.mkdirs()
        file.writeText("""{"regions":[${paths.joinToString(",") { "{\"path\":\"$it\"}" }}]}""")
    }
    @Test fun legacySurfaceAndLayerMetadataArePreparedOnWorker() = runBlocking {
        val root = root()
        try {
            File(root, "layer.json").writeText("{}")
            val caller = Thread.currentThread()
            val prepared = MappingSurfaceStartup.load { assertNotSame(caller, Thread.currentThread()); root }
            assertTrue(prepared.hasLayer); assertTrue(prepared.paths.isEmpty()); assertEquals(root, prepared.root)
        } finally { root.deleteRecursively() }
    }
    @Test fun collectionMaintainsOrderedValidatedRegions() = runBlocking {
        val root = root()
        try {
            val paths = (0 until 200).map { "regions/$it/surface.glb" }; collection(root, paths)
            val prepared = MappingSurfaceStartup.load { root }
            assertEquals(paths, prepared.paths); assertFalse(prepared.hasLayer)
        } finally { root.deleteRecursively() }
    }
    @Test fun invalidOrTraversingRegionPathsFailBeforeRendering() = runBlocking {
        val root = root()
        try {
            for (path in listOf("../surface.glb", "regions/1/surface.glb", "https://example.com/model")) {
                collection(root, listOf(path))
                assertTrue(runCatching { MappingSurfaceStartup.load { root } }.isFailure)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun emptyOversizedAndMalformedCollectionsFailCleanly() = runBlocking {
        val root = root()
        try {
            for (count in listOf(0, 201)) {
                collection(root, (0 until count).map { "regions/$it/surface.glb" })
                assertTrue(runCatching { MappingSurfaceStartup.load { root } }.isFailure)
            }
            File(root, "surface/collection.json").writeText("bad JSON")
            assertTrue(runCatching { MappingSurfaceStartup.load { root } }.isFailure)
            RandomAccessFile(File(root, "surface/collection.json"), "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
            assertTrue(runCatching { MappingSurfaceStartup.load { root } }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun slowRootAndFilePreparationLeaveCallerAvailable() = runBlocking {
        val root = root(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        try {
            val load = async { MappingSurfaceStartup.load { entered.complete(Unit); release.await(); root } }
            withTimeout(2_000) { entered.await() }
            assertFalse(load.isCompleted) // caller is still running while the real worker is blocked
            release.countDown(); assertEquals(root, load.await().root)
        } finally { release.countDown(); root.deleteRecursively() }
    }
    @Test fun cancelledPreparationCannotDeliverALateModel() = runBlocking {
        val root = root(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        try {
            var delivered = false
            val load = async { MappingSurfaceStartup.load { entered.complete(Unit); release.await(); root }.also { delivered = true } }
            withTimeout(2_000) { entered.await() }; load.cancel(); release.countDown()
            try { load.await(); fail("Expected cancellation") } catch (_: CancellationException) {}
            assertFalse(delivered)
        } finally { release.countDown(); root.deleteRecursively() }
    }
}
