package org.ncssar.rid2caltopo.video.surface

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SurfaceTransferRetryTest {
    @Test fun interruptedStreamIsRetriedAndEventuallySucceeds() = runBlocking {
        var attempts=0
        val retries=mutableListOf<Int>()
        SurfaceTransferRetry.run(onRetry={ retries+=it },pause={}) {
            attempts++
            if(attempts<3) throw IOException("stream was reset: CANCEL")
        }
        assertEquals(3,attempts)
        assertEquals(listOf(1,2),retries)
    }
    @Test fun exhaustionKeepsFailureAndOperatorCancellationDoesNotRetry() = runBlocking {
        var attempts=0
        try {
            SurfaceTransferRetry.run(onRetry={},pause={}) { attempts++;throw IOException("reset") }
            fail("Expected failed transfer")
        } catch(error: IOException) { assertEquals("reset",error.message) }
        assertEquals(3,attempts)
        attempts=0
        try {
            SurfaceTransferRetry.run(onRetry={fail("Must not retry cancellation")},pause={}) { attempts++;throw CancellationException() }
            fail("Expected cancellation")
        } catch(_: CancellationException) { }
        assertEquals(1,attempts)
    }
}
