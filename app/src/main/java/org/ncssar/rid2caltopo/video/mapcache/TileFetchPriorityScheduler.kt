package org.ncssar.rid2caltopo.video.mapcache

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal class TileFetchPriorityScheduler(
    private val highExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        namedThreadFactory("r2c-tile-high")
    ),
    private val lowExecutor: ExecutorService = Executors.newFixedThreadPool(
        MapTileUsagePolicy.OFFLINE_FETCH_THREAD_POOL,
        namedThreadFactory("r2c-tile-low")
    )
) : Closeable {
    private val highDispatcher = highExecutor.asCoroutineDispatcher()
    private val lowDispatcher = lowExecutor.asCoroutineDispatcher()

    fun <T> submitHighPriority(task: Callable<T>): Future<T> = highExecutor.submit(task)

    fun <T> submitLowPriority(task: Callable<T>): Future<T> = lowExecutor.submit(task)

    suspend fun <T> highPriority(block: () -> T): T = withContext(highDispatcher) { block() }

    suspend fun <T> lowPriority(block: () -> T): T = withContext(lowDispatcher) { block() }

    override fun close() {
        highDispatcher.close()
        lowDispatcher.close()
        highExecutor.shutdownNow()
        lowExecutor.shutdownNow()
    }

    companion object {
        private fun namedThreadFactory(prefix: String): ThreadFactory {
            val counter = AtomicInteger(0)
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }
}
