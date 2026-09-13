package org.ncssar.rid2caltopo.video.surface

import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Metadata only: bounded retries never initiate source-file preparation. */
internal object SurfaceCatalog {
    suspend fun data(
        request: Request,
        client: OkHttpClient,
        onCall: (Call, Boolean) -> Unit = { _, _ -> },
        pause: suspend (Long) -> Unit = { delay(it) }
    ): ByteArray = withContext(Dispatchers.IO) {
        val catalogClient = client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
        val catalogRequest = request.newBuilder()
            .header("User-Agent", "RID2Caltopo/Android (contact: kjt@uas4sar.com)")
            .header("Accept", "application/json")
            .cacheControl(CacheControl.FORCE_NETWORK).build()
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val call = catalogClient.newCall(catalogRequest)
            onCall(call, true)
            var waitMillis: Long
            try {
                val response = suspendCancellableCoroutine<Response> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            continuation.resume(response) { _, value, _ -> value.close() }
                        }
                    })
                }
                response.use {
                    if (it.isSuccessful) {
                        val output = ByteArrayOutputStream()
                        it.body!!.byteStream().use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = stream.read(buffer)
                                if (count < 0) break
                                check(output.size() + count <= 4_000_000) { "USGS lidar catalog response is too large; select a smaller region" }
                                output.write(buffer, 0, count)
                            }
                        }
                        return@withContext output.toByteArray()
                    }
                    val temporary = it.code == 408 || it.code == 429 || it.code in 500..599
                    check(temporary && attempt < 3) {
                        "USGS lidar catalog returned HTTP ${it.code}. Try the catalog again; if it persists, try another network or a smaller region."
                    }
                    waitMillis = it.header("Retry-After")?.trim()?.toDoubleOrNull()
                        ?.takeIf { seconds -> seconds.isFinite() && seconds >= 0 }
                        ?.let { seconds -> (seconds.coerceAtMost(30.0) * 1000).toLong() }
                        ?: listOf(500L, 1000L, 2000L)[attempt]
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= 3 || error is javax.net.ssl.SSLException || error is java.net.ProtocolException) {
                    throw IllegalStateException("USGS lidar catalog: ${error.message ?: "connection failed"}. Check the connection and try the catalog again.", error)
                }
                waitMillis = listOf(500L, 1000L, 2000L)[attempt]
            } finally { onCall(call, false) }
            attempt++
            pause(waitMillis)
        }
        @Suppress("UNREACHABLE_CODE") error("Catalog loop ended unexpectedly")
    }
}

internal enum class SurfaceCatalogAction { Preserve, Clear, Lookup }

internal fun surfaceCatalogAction(
    running: Boolean, visible: Boolean, enabled: Boolean,
    prepared: SurfaceBounds?, selected: SurfaceBounds?
): SurfaceCatalogAction = when {
    running -> SurfaceCatalogAction.Preserve
    !visible || !enabled || selected == null -> SurfaceCatalogAction.Clear
    prepared == selected -> SurfaceCatalogAction.Preserve
    else -> SurfaceCatalogAction.Lookup
}
