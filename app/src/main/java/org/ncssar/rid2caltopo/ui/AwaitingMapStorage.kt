package org.ncssar.rid2caltopo.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Storage failure is panel state; cancellation still ends the owning UI operation. */
internal suspend fun <T> awaitingMapStorage(load: () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(load())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
