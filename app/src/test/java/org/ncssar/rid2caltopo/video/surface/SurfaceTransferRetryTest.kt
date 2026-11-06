package org.ncssar.rid2caltopo.video.surface

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test fun connectionRetriesKeepTheShortBackoff() = runBlocking {
        val pauses = mutableListOf<Long>()
        var attempts = 0
        SurfaceTransferRetry.run(onRetry = { _, _ -> }, pause = { pauses += it }) {
            attempts++
            if (attempts < 3) throw IOException("reset")
        }
        assertEquals(listOf(500L, 1000L), pauses)
        assertEquals(3, attempts)
    }

    @Test fun transientStatusBackoffWaitsLongerThanTheShortCatalogSchedule() {
        val c = SurfaceTransferRetry
        assertEquals(120_000L, c.transientStatusDelayMillis(429, 0, "600"))
        assertEquals(120_000L, c.transientStatusDelayMillis(429, 0, "120"))
        assertEquals(45_000L, c.transientStatusDelayMillis(429, 0, "45"))
        assertEquals(0L, c.transientStatusDelayMillis(429, 0, "0"))
        assertEquals(2_000L, c.transientStatusDelayMillis(429, 0, " 2 "))
        assertEquals(10_000L, c.transientStatusDelayMillis(429, 0, null))
        assertEquals(10_000L, c.transientStatusDelayMillis(429, 0, "later"))
        assertEquals(10_000L, c.transientStatusDelayMillis(429, 0, "-1"))
        assertEquals(10_000L, c.transientStatusDelayMillis(500, 0, "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(30_000L, c.transientStatusDelayMillis(503, 1, null))
        assertEquals(60_000L, c.transientStatusDelayMillis(408, 2, null))
        assertEquals(120_000L, c.transientStatusDelayMillis(599, 3, null))
        assertEquals(1_000L, c.transientStatusDelayMillis(599, 0, "1"))
        assertNull(c.transientStatusDelayMillis(429, 4, "1"))
        assertNull(c.transientStatusDelayMillis(404, 0, "1"))
        assertNull(c.transientStatusDelayMillis(403, 0, null))
        assertNull(c.transientStatusDelayMillis(200, 0, null))
        assertTrue(c.transientStatusDelayMillis(429, 0, null)!! > 2_000L)
        assertEquals(
            "Retrying AOL lidar 1/4 after the server asked to wait (retry 1/4)",
            c.retryMessage(1, 4, 1, SurfaceTransferFailure.TRANSIENT_HTTP)
        )
    }

    @Test fun http429RetriesWithRetryAfterThenFailsNamingHostOnly() = runBlocking {
        val pauses = mutableListOf<Long>()
        val failures = mutableListOf<SurfaceTransferFailure>()
        var attempts = 0
        try {
            SurfaceTransferRetry.run(onRetry = { _, failure -> failures += failure }, pause = { pauses += it }) {
                attempts++
                val retryAfter = if (attempts == 1) "120" else null
                throw SurfaceHttpStatusException(429, retryAfter, "rockyweb.usgs.gov")
            }
            fail("Expected HTTP 429")
        } catch (error: SurfaceHttpStatusException) {
            assertEquals(429, error.code)
            assertEquals("Lidar download HTTP 429 (rockyweb.usgs.gov)", error.message)
            assertFalse(error.message!!.contains("/"))
            assertFalse(error.message!!.contains("?"))
            assertFalse(error.message!!.contains("bbox"))
            assertEquals("USGS returned HTTP 429", OfflinePrepProgressText.describeFailure(error, "USGS"))
        }
        assertEquals(5, attempts)
        assertEquals(List(4) { SurfaceTransferFailure.TRANSIENT_HTTP }, failures)
        // First wait honors Retry-After, capped at 120s. Later waits use the longer lidar schedule.
        assertEquals(listOf(120_000L, 30_000L, 60_000L, 120_000L), pauses)
    }

    @Test fun cancellationDuringStatusBackoffDoesNotRetry() = runBlocking {
        var attempts = 0
        try {
            SurfaceTransferRetry.run(onRetry = { _, _ -> }, pause = { throw CancellationException() }) {
                attempts++
                throw SurfaceHttpStatusException(429, "0", "rockyweb.usgs.gov")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }

    @Test fun lidarFileGetSendsUserAgentAndRetries429() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("busy"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("laz!"))
            val pauses = mutableListOf<Long>()
            var body = ""
            val url = server.url("/vdelivery/Datasets/file.laz").newBuilder().addQueryParameter("bbox", "10,20,30,40").build()
            SurfaceTransferRetry.run(onRetry = { _, failure ->
                assertEquals(SurfaceTransferFailure.TRANSIENT_HTTP, failure)
            }, pause = { pauses += it }) {
                OkHttpClient().newCall(lidarFileRequest(url.toString())).execute().use { response ->
                    response.failIfLidarHttpStatus()
                    body = response.body!!.string()
                }
            }
            assertEquals("laz!", body)
            assertEquals(listOf(0L), pauses)
            assertEquals(2, server.requestCount)
            repeat(2) {
                val recorded = server.takeRequest()
                assertEquals(USGS_CLIENT_USER_AGENT, recorded.getHeader("User-Agent"))
                assertEquals("identity", recorded.getHeader("Accept-Encoding"))
                assertEquals("10,20,30,40", recorded.requestUrl!!.queryParameter("bbox"))
            }
        }
    }

    @Test fun exhaustedLidar429NamesHostAndStatusWithoutTheUrl() = runBlocking {
        MockWebServer().use { server ->
            repeat(5) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "45")) }
            val url = server.url("/secret/file.laz").newBuilder().addQueryParameter("bbox", "-121,39,-120,40").build()
            val pauses = mutableListOf<Long>()
            try {
                SurfaceTransferRetry.run(onRetry = { _, _ -> }, pause = { pauses += it }) {
                    OkHttpClient().newCall(lidarFileRequest(url.toString())).execute().use { it.failIfLidarHttpStatus() }
                }
                fail("Expected HTTP 429")
            } catch (error: SurfaceHttpStatusException) {
                assertEquals("Lidar download HTTP 429 (${url.host})", error.message)
                assertFalse(error.message!!.contains("bbox"))
                assertFalse(error.message!!.contains("file.laz"))
                assertFalse(error.message!!.contains("secret"))
                assertFalse(error.message!!.contains("?"))
            }
            assertEquals(listOf(45_000L, 45_000L, 45_000L, 45_000L), pauses)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun permanentLidarHttpStatusIsNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("no"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("should-not-be-read"))
            var attempts = 0
            try {
                SurfaceTransferRetry.run(onRetry = { _, _ -> fail("Must not retry a permanent status") }, pause = { fail("Must not wait") }) {
                    attempts++
                    OkHttpClient().newCall(lidarFileRequest(server.url("/x").toString())).execute().use { it.failIfLidarHttpStatus() }
                }
                fail("Expected HTTP 403")
            } catch (error: SurfaceHttpStatusException) {
                assertTrue(error.message!!.startsWith("Lidar download HTTP 403 ("))
                assertFalse(error.message!!.contains("/x"))
            }
            assertEquals(1, attempts)
            assertEquals(1, server.requestCount)
        }
    }
}
