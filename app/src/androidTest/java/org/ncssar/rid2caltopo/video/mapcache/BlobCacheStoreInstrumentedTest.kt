package org.ncssar.rid2caltopo.video.mapcache

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlobCacheStoreInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun blobSqlDiskCache_keepsWritesFastAndEvictsDuringExplicitMaintenance() {
        val cache = BlobSqlDiskCache(
            cacheDir = createTempDir("blobsql"),
            dbName = "test_cache.db",
            maxBytes = 10L,
            defaultTtlMs = 60_000L,
        )

        cache.put("a", byteArrayOf(1, 2, 3, 4, 5, 6), expiresAtMs = cache.defaultExpiry())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), cache.get("a")?.bytes)

        cache.put("b", byteArrayOf(7, 8, 9, 10, 11, 12), expiresAtMs = cache.defaultExpiry())

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), cache.get("a")?.bytes)
        cache.runMaintenance(maxEntryAgeCutoffMs = 0L, trimToBytes = 9L)

        assertNull(cache.get("a", countHitMiss = false))
        assertArrayEquals(byteArrayOf(7, 8, 9, 10, 11, 12), cache.get("b")?.bytes)
        assertTrue(cache.snapshot().evictions >= 1L)
    }

    @Test
    fun safBlobCacheStore_roundTripsBytesAndSupportsRemove() {
        val root = DocumentFile.fromFile(createTempDir("safroot"))
        val cache = SafBlobCacheStore(
            context = context,
            rootDir = root,
            namespace = "tile_cache_v1_test_${UUID.randomUUID()}",
            maxBytes = 1_024L * 1_024L,
            defaultTtlMs = 60_000L,
        )

        val payload = "hello cache".encodeToByteArray()
        cache.put("v1|osm|12|656|1582", payload, expiresAtMs = cache.defaultExpiry())

        val blob = cache.get("v1|osm|12|656|1582")
        assertNotNull(blob)
        assertArrayEquals(payload, blob?.bytes)
        assertTrue(cache.exists("v1|osm|12|656|1582"))

        assertTrue(cache.remove("v1|osm|12|656|1582"))
        assertFalse(cache.exists("v1|osm|12|656|1582"))
    }

    @Test
    fun safBlobCacheStore_multipleInstancesShareIndexWithoutLocking() {
        val root = DocumentFile.fromFile(createTempDir("safshared"))
        val namespaceA = "tile_cache_v1_icon_${UUID.randomUUID()}"
        val namespaceB = "dem_point_v1_${UUID.randomUUID()}"
        val storeA = SafBlobCacheStore(
            context = context,
            rootDir = root,
            namespace = namespaceA,
            maxBytes = 1_024L * 1_024L,
            defaultTtlMs = 60_000L,
        )
        val storeB = SafBlobCacheStore(
            context = context,
            rootDir = root,
            namespace = namespaceB,
            maxBytes = 1_024L * 1_024L,
            defaultTtlMs = 60_000L,
        )

        val executor = Executors.newFixedThreadPool(2)
        val startGate = CountDownLatch(1)
        try {
            val futureA = executor.submit(Callable {
                startGate.await(5, TimeUnit.SECONDS)
                repeat(10) { idx ->
                    val key = "v1|src|12|100|$idx"
                    val bytes = byteArrayOf(idx.toByte())
                    storeA.put(key, bytes, expiresAtMs = storeA.defaultExpiry())
                    assertEquals(idx.toByte(), storeA.get(key)?.bytes?.single())
                }
                true
            })
            val futureB = executor.submit(Callable {
                startGate.await(5, TimeUnit.SECONDS)
                repeat(10) { idx ->
                    val key = "v1|200|300|m"
                    val bytes = byteArrayOf((idx + 20).toByte())
                    storeB.put(key, bytes, expiresAtMs = storeB.defaultExpiry())
                    assertNotNull(storeB.get(key))
                }
                true
            })

            startGate.countDown()
            assertTrue(futureA.get(15, TimeUnit.SECONDS))
            assertTrue(futureB.get(15, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun safBlobCacheStore_prewarmIndexesExistingReadableTileFiles() {
        val root = DocumentFile.fromFile(createTempDir("safprewarm"))
        val namespace = "tile_cache_v1_test_${UUID.randomUUID()}"
        val namespaceDir = root.createDirectory(namespace)!!
        val sourceDir = namespaceDir.createDirectory("source")!!
        val zoomDir = sourceDir.createDirectory("12")!!
        val xDir = zoomDir.createDirectory("100")!!
        val tile = xDir.createFile("application/octet-stream", "200.bin")!!
        context.contentResolver.openOutputStream(tile.uri, "w")!!.use {
            it.write(byteArrayOf(4, 5, 6))
        }
        val cache = SafBlobCacheStore(
            context = context,
            rootDir = root,
            namespace = namespace,
            maxBytes = 1_024L * 1_024L,
            defaultTtlMs = 60_000L,
        )

        cache.prewarm()

        assertTrue(cache.exists("v1|source|12|100|200"))
        assertArrayEquals(byteArrayOf(4, 5, 6), cache.get("v1|source|12|100|200")?.bytes)
    }

    @Test
    fun safBlobCacheStore_prewarmRejectsStaleDatabaseResidency() {
        val root = DocumentFile.fromFile(createTempDir("safstale"))
        val namespace = "tile_cache_v1_test_${UUID.randomUUID()}"
        val cache = SafBlobCacheStore(
            context = context,
            rootDir = root,
            namespace = namespace,
            maxBytes = 1_024L * 1_024L,
            defaultTtlMs = 60_000L,
        )
        val key = "v1|source|12|100|200"
        cache.put(key, byteArrayOf(4, 5, 6), expiresAtMs = cache.defaultExpiry())
        root.findFile(namespace)!!
            .findFile("source")!!
            .findFile("12")!!
            .findFile("100")!!
            .findFile("200.bin")!!
            .delete()

        cache.prewarm()

        assertFalse(cache.exists(key))
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(context.cacheDir, "$prefix-${UUID.randomUUID()}")
        dir.mkdirs()
        return dir
    }
}
