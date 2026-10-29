package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Failure belongs to the owning callback, even when it resumes on Main. */
internal suspend fun <T> clueStorage(operation: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try { Result.success(operation()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
}
