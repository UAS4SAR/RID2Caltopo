package org.ncssar.rid2caltopo.video.mapcache

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the concurrent-read contract expected of [SafBlobCacheStore]:
 * metadata locking must not serialize independent blob reads behind SAF/file I/O.
 *
 * Uses an in-process stand-in that mirrors the store's lock discipline (short metadata
 * lock, blob I/O outside the monitor, striped per-key write gates).
 */
class SafBlobCacheStoreConcurrencyTest {
    @Test
    fun concurrentReadsOfDistinctKeysOverlapInsteadOfSerializingBehindIo() {
        val store = ConcurrentReadProbeStore(ioDelayMs = 80L)
        repeat(8) { idx ->
            store.put("key-$idx", byteArrayOf(idx.toByte()))
        }

        val threads = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { idx ->
                threads.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    val blob = store.get("key-$idx")
                    assertEquals(idx.toByte(), blob?.single())
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            threads.shutdownNow()
        }

        // With 80ms I/O and 8 readers, overlap must exceed 1 if reads are not
        // serialized behind a coarse lock held across I/O.
        assertTrue(
            "expected concurrent readers to overlap; maxOverlap=${store.observedMaxOverlap}",
            store.observedMaxOverlap >= 4
        )
    }

    @Test
    fun safBlobCacheStoreGetReleasesMetadataLockBeforeReadingBytes() {
        val source = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/video/mapcache/SafBlobCacheStore.kt"
        )
        assertTrue(source.contains("concurrent tile readers are not serialized behind SAF I/O"))
        assertTrue(source.contains("recoverRowFromFile(cacheKey)"))
        assertTrue(!source.contains("recoverRowFromFileLocked"))
        // readBytes must not appear inside a synchronized(dbLock) block in get().
        val getMethod = source.substringAfter("override fun get(cacheKey: String")
            .substringBefore("override fun getExpiration")
        val lockBlocks = Regex("""synchronized\(dbLock\)\s*\{[^}]*}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(getMethod)
            .map { it.value }
            .toList()
        assertTrue(lockBlocks.isNotEmpty())
        assertTrue(lockBlocks.none { it.contains("readBytes") })
        assertTrue(getMethod.contains("readBytes(Uri.parse(row.fileUri))"))
    }

    private fun projectSource(relativePath: String): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(workingDirectory, relativePath),
            File(workingDirectory.parentFile ?: workingDirectory, relativePath),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from $workingDirectory"
        }.readText()
    }

    /**
     * Mirrors SafBlobCacheStore's get/put locking: short metadata lock, I/O outside it.
     */
    private class ConcurrentReadProbeStore(private val ioDelayMs: Long) {
        private val dbLock = Any()
        private val keyStripes = Array(64) { Any() }
        private val index = HashMap<String, ByteArray>()
        private val inFlight = AtomicInteger(0)
        private val maxOverlap = AtomicInteger(0)

        val observedMaxOverlap: Int get() = maxOverlap.get()

        fun put(key: String, bytes: ByteArray) {
            val gate = keyStripes[(key.hashCode() ushr 1) % keyStripes.size]
            synchronized(gate) {
                Thread.sleep(1L) // simulate SAF write outside metadata lock below
                synchronized(dbLock) {
                    index[key] = bytes.copyOf()
                }
            }
        }

        fun get(key: String): ByteArray? {
            val row = synchronized(dbLock) { index[key]?.copyOf() } ?: return null
            val flying = inFlight.incrementAndGet()
            maxOverlap.accumulateAndGet(flying) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(ioDelayMs) // simulate SAF readBytes outside dbLock
                return row
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }
}
