package org.ncssar.rid2caltopo.video.surface

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SurfaceCatalogTest {
    @Test fun gatewayTimeoutAndRateLimitRecover() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(504).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("{\"items\":[]}"))
            val result = SurfaceCatalog.data(Request.Builder().url(server.url("/catalog")).build(), OkHttpClient(), pause = {})
            assertEquals("{\"items\":[]}", String(result))
            assertEquals(3, server.requestCount)
            val request = server.takeRequest()
            assertEquals("application/json", request.getHeader("Accept"))
            assertTrue(request.getHeader("Cache-Control")!!.contains("no-cache"))
        }
    }
    @Test fun retriesAreBoundedAndPermanentFailuresKeepStatus() = runBlocking {
        for (status in listOf(403, 504)) MockWebServer().use { server ->
            val count = if(status == 403) 1 else 4
            repeat(count) { server.enqueue(MockResponse().setResponseCode(status)) }
            try {
                SurfaceCatalog.data(Request.Builder().url(server.url("/catalog")).build(), OkHttpClient(), pause = {})
                fail("Expected HTTP failure")
            } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("HTTP $status")) }
            assertEquals(count, server.requestCount)
        }
    }
    @Test fun cancellationDuringBackoffStopsRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(504))
            try {
                SurfaceCatalog.data(Request.Builder().url(server.url("/catalog")).build(), OkHttpClient(), pause = { throw CancellationException() })
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(1, server.requestCount)
        }
    }
}
