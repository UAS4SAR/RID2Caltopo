package org.ncssar.rid2caltopo.video

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test

class ClueStorageTest {
    @Test fun closingWhileWorkerIsBusyPublishesNeitherStaleDataNorFailure() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var published = false
        var failed = false
        val job = launch {
            clueStorage {
                entered.countDown()
                release.await()
                currentCoroutineContext().ensureActive()
                "stale thumbnail list"
            }.onSuccess { published = true }.onFailure { failed = true }
        }
        withContext(Dispatchers.IO) { assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        job.cancel()
        release.countDown()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
        assertFalse(failed)
    }

    @Test fun callbackDiskFailureIsRecoverableAndRunsOnWorker() = runBlocking {
        val caller = Thread.currentThread()
        val error = IOException("storage removed after upload")
        val result = clueStorage<Unit> {
            assertNotSame(caller, Thread.currentThread())
            throw error
        }
        assertSame(error, result.exceptionOrNull())
        assertEquals("retry succeeded", clueStorage { "retry succeeded" }.getOrThrow())
    }

    @Test fun cancellationStillCancelsTheOwningCoroutine() {
        assertThrows(CancellationException::class.java) {
            runBlocking { clueStorage<Unit> { throw CancellationException("screen closed") } }
        }
    }
}
