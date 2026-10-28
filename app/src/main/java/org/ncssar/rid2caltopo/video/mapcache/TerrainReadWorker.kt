package org.ncssar.rid2caltopo.video.mapcache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Waiters suspend before entering IO; cancelled slider updates never start another raster read. */
internal class TerrainReadWorker(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val gate = Mutex()

    suspend fun <T> read(block: (checkCancelled: () -> Unit) -> T): T = gate.withLock {
        withContext(dispatcher) {
            val context = currentCoroutineContext()
            context.ensureActive()
            val result = block { context.ensureActive() }
            context.ensureActive()
            result
        }
    }
}

/** Stable resolution ordering preserves the first valid best-resolution source on overlaps. */
internal fun <T, V> firstValidTerrainSample(
    candidates: List<T>,
    resolution: (T) -> Double,
    checkCancelled: () -> Unit,
    sample: (T) -> V?,
): V? {
    for (candidate in candidates.sortedBy(resolution)) {
        checkCancelled()
        sample(candidate)?.let { return it }
    }
    return null
}
