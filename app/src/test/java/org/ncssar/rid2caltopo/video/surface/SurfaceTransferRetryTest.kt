package org.ncssar.rid2caltopo.video.surface

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.video.OfflinePrepProgressText

class SurfaceTransferRetryTest {
    private val connectTimeout = SocketTimeoutException(
        "failed to connect to rockyweb.usgs.gov/137.227.224.52 (port 443) from /192.168.68.67 (port 56224) after 30000ms"
    )

    @Test fun interruptedStreamIsRetriedAndEventuallySucceeds() = runBlocking {
        var attempts=0
        val retries=mutableListOf<Int>()
        SurfaceTransferRetry.run(onRetry={ n, _ -> retries+=n },pause={}) {
            attempts++
            if(attempts<3) throw IOException("stream was reset: CANCEL")
        }
        assertEquals(3,attempts)
        assertEquals(listOf(1,2),retries)
    }
    @Test fun exhaustionKeepsFailureAndOperatorCancellationDoesNotRetry() = runBlocking {
        var attempts=0
        try {
            SurfaceTransferRetry.run(onRetry={ _, _ -> },pause={}) { attempts++;throw IOException("reset") }
            fail("Expected failed transfer")
        } catch(error: IOException) { assertEquals("reset",error.message) }
        assertEquals(3,attempts)
        attempts=0
        try {
            SurfaceTransferRetry.run(onRetry={ _, _ -> fail("Must not retry cancellation")},pause={}) { attempts++;throw CancellationException() }
            fail("Expected cancellation")
        } catch(_: CancellationException) { }
        assertEquals(1,attempts)
    }

    @Test fun serverThatNeverAcceptsAConnectionFailsAfterOneAttemptWithTheHost() = runBlocking {
        var attempts=0
        try {
            SurfaceTransferRetry.run(onRetry={ _, _ -> fail("Must not retry an unreachable server") },pause={ fail("Must not wait") },host="rockyweb.usgs.gov") {
                attempts++;throw connectTimeout
            }
            fail("Expected unreachable server")
        } catch(error: SurfaceServerUnreachableException) {
            assertEquals("USGS lidar server unreachable (rockyweb.usgs.gov, timed out after 30 s)",error.message)
            assertEquals("rockyweb.usgs.gov",error.host)
            assertSame(connectTimeout,error.cause)
            // The Download Map failure line shows the same words.
            assertEquals("USGS lidar server unreachable (rockyweb.usgs.gov, timed out after 30 s)",OfflinePrepProgressText.describeFailure(error,"USGS"))
        }
        assertEquals(1,attempts)
    }

    @Test fun readTimeoutsAndResetsAreRetriedWithAccurateWording() = runBlocking {
        val failures=mutableListOf<SurfaceTransferFailure>()
        var attempts=0;var received=0L
        SurfaceTransferRetry.run(onRetry={ _, f -> failures+=f },pause={},bytesReceived={ received },host="rockyweb.usgs.gov") {
            attempts++
            when(attempts) {
                1 -> { received=0L;throw SocketTimeoutException("timeout") }        // connected, nothing arrived
                2 -> { received=4_000_000L;throw SocketException("Connection reset") } // broke mid-file
            }
        }
        assertEquals(3,attempts)
        assertEquals(listOf(SurfaceTransferFailure.CONNECTION_PROBLEM,SurfaceTransferFailure.INTERRUPTED),failures)
        assertEquals("Retrying AOL lidar 2/5 after connection problem (retry 1/2)",
            SurfaceTransferRetry.retryMessage(2,5,1,SurfaceTransferFailure.CONNECTION_PROBLEM))
        assertEquals("Retrying AOL lidar 2/5 after interrupted transfer (retry 2/2)",
            SurfaceTransferRetry.retryMessage(2,5,2,SurfaceTransferFailure.INTERRUPTED))
    }

    @Test fun classificationSeparatesConnectFailuresFromMidTransferBreaks() {
        val c=SurfaceTransferRetry
        assertEquals(SurfaceTransferFailure.UNREACHABLE,c.classify(connectTimeout,0))
        assertEquals(SurfaceTransferFailure.UNREACHABLE,c.classify(UnknownHostException("rockyweb.usgs.gov"),0))
        assertEquals(SurfaceTransferFailure.UNREACHABLE,c.classify(ConnectException("Failed to connect to rockyweb.usgs.gov/137.227.224.52:443"),0))
        assertEquals(SurfaceTransferFailure.UNREACHABLE,c.classify(IOException("wrapped",ConnectException("Connection refused")),0))
        assertEquals(SurfaceTransferFailure.CONNECTION_PROBLEM,c.classify(SocketTimeoutException("timeout"),0))
        assertEquals(SurfaceTransferFailure.CONNECTION_PROBLEM,c.classify(SocketException("Connection reset"),0))
        // Once lidar bytes arrive, any break is an interrupted transfer, never "unreachable".
        assertEquals(SurfaceTransferFailure.INTERRUPTED,c.classify(SocketTimeoutException("timeout"),1))
        assertEquals(SurfaceTransferFailure.INTERRUPTED,c.classify(connectTimeout,10))
        assertEquals(SurfaceTransferFailure.FATAL,c.classify(SSLHandshakeException("bad certificate"),0))
    }

    @Test fun unreachableMessagesNameTheHostAndTheReason() {
        val c=SurfaceTransferRetry
        assertEquals("USGS lidar server unreachable (rockyweb.usgs.gov, no network or name lookup failed)",
            c.unreachableMessage("rockyweb.usgs.gov",UnknownHostException("Unable to resolve host")))
        assertEquals("USGS lidar server unreachable (rockyweb.usgs.gov, connection refused)",
            c.unreachableMessage("rockyweb.usgs.gov",ConnectException("Failed to connect: Connection refused")))
        assertEquals("USGS lidar server unreachable (rockyweb.usgs.gov, no route to server)",
            c.unreachableMessage("rockyweb.usgs.gov",ConnectException("Network is unreachable")))
        assertEquals("USGS lidar server unreachable (rockyweb.usgs.gov)",
            c.unreachableMessage("rockyweb.usgs.gov",ConnectException("Failed to connect")))
        assertEquals("USGS lidar server unreachable (unknown host, timed out)",
            c.unreachableMessage("",SocketTimeoutException("connect timed out")))
    }
}
