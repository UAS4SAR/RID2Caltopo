package org.ncssar.rid2caltopo.video.mapcache

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerrainReadWorkerTest {
    @Test fun cancelledSliderUpdatesNeverEnterTheRasterWorker() = runBlocking {
        val worker = TerrainReadWorker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        try {
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                worker.read { _ ->
                    val count = active.incrementAndGet()
                    maximumActive.accumulateAndGet(count, ::maxOf)
                    reads.incrementAndGet()
                    entered.countDown()
                    try { check(release.await(3, TimeUnit.SECONDS)) }
                    finally { active.decrementAndGet() }
                }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            val replaced = List(100) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    worker.read { _ -> reads.incrementAndGet() }
                }
            }
            replaced.forEach { it.cancel() }
            val latest = launch(start = CoroutineStart.UNDISPATCHED) {
                worker.read { _ ->
                    maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    reads.incrementAndGet()
                    active.decrementAndGet()
                }
            }
            assertEquals(1, reads.get())
            release.countDown()
            withTimeout(3_000) { (replaced + first + latest).joinAll() }
            assertEquals(2, reads.get())
            assertEquals(1, maximumActive.get())
        } finally { release.countDown() }
    }

    @Test fun cancellationDuringBlockingReadDiscardsItsResult() = runBlocking {
        val worker = TerrainReadWorker(Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = java.util.concurrent.atomic.AtomicBoolean()
        try {
            val job = launch(Dispatchers.IO) {
                worker.read { _ ->
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    "stale terrain result"
                }
                returned.set(true)
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            job.cancel()
            release.countDown()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(!returned.get())
        } finally { release.countDown() }
    }

    private data class Source(val name: String, val resolution: Double, val elevation: Double?)

    @Test fun overlappingRastersDecodeOnlyTheBestValidResolution() {
        val decoded = mutableListOf<String>()
        val sources = listOf(Source("coarse", 30.0, 500.0), Source("best", 1.0, 510.0),
            Source("duplicate", 1.0, 520.0), Source("medium", 3.0, 505.0))
        val value = firstValidTerrainSample(sources, { it.resolution }, {}) {
            decoded += it.name
            it.elevation
        }
        assertEquals(510.0, value)
        assertEquals(listOf("best"), decoded)
    }

    @Test fun noDataFallsBackInStableResolutionOrder() {
        val decoded = mutableListOf<String>()
        val sources = listOf(Source("coarse", 30.0, 500.0), Source("no-data", 1.0, null),
            Source("next-best", 1.0, 520.0), Source("medium", 3.0, 505.0))
        val value = firstValidTerrainSample(sources, { it.resolution }, {}) {
            decoded += it.name
            it.elevation
        }
        assertEquals(520.0, value)
        assertEquals(listOf("no-data", "next-best"), decoded)
    }
}
