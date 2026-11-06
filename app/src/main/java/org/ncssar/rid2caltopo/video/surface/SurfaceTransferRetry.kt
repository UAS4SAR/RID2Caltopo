package org.ncssar.rid2caltopo.video.surface

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.Response

/** Same identifying User-Agent the USGS catalog request sends. */
internal const val USGS_CLIENT_USER_AGENT = "RID2Caltopo/Android (contact: kjt@uas4sar.com)"

/** Why one lidar transfer attempt failed; decides whether it is retried and how the retry is worded. */
internal enum class SurfaceTransferFailure {
    /** The server never accepted a connection (connect timeout, name lookup, refused, no route): fail at once. */
    UNREACHABLE,
    /** Connected, but no lidar bytes arrived before a timeout or reset: retried. */
    CONNECTION_PROBLEM,
    /** Lidar bytes had arrived when the transfer broke: retried. */
    INTERRUPTED,
    /** TLS or protocol failure: retrying the same request will not help. */
    FATAL,
    /** Transient HTTP status (408, 429, 5xx). Retried with Retry-After, otherwise a longer capped wait. */
    TRANSIENT_HTTP,
}

/**
 * A lidar file GET that returned a non-success status. Not an [IOException], so a connection
 * retry cannot swallow it. The message is host and status only — never the URL, which can carry a bbox.
 */
internal class SurfaceHttpStatusException(val code: Int, val retryAfter: String?, host: String) :
    Exception("Lidar download HTTP $code (${host.ifBlank { "unknown host" }})")

/** One lidar file GET. Downloads stay serial; this only builds the request. */
internal fun lidarFileRequest(url: String): Request = Request.Builder().url(url)
    .header("Accept-Encoding", "identity")
    .header("User-Agent", USGS_CLIENT_USER_AGENT)
    .build()

/** Throws [SurfaceHttpStatusException] for any non-success status. The caller retries transient ones. */
internal fun Response.failIfLidarHttpStatus() {
    if (isSuccessful) return
    throw SurfaceHttpStatusException(code, header("Retry-After"), request.url.host)
}

/** The lidar server could not be reached at all; the message is shown to the operator as is. */
internal class SurfaceServerUnreachableException(val host: String, message: String, cause: Throwable) : IOException(message, cause)

/** iOS mirrors the classification and wording in OperationalSurfaceTransferFailure (R2CCore). */
internal object SurfaceTransferRetry {
    const val MAX_RETRIES = 2
    /**
     * Four waits (attempts 0..3), then the fifth transient status is final.
     * Longer than the catalog's 0.5/1/2 s retries. Downloads stay one file at a time.
     */
    const val MAX_TRANSIENT_STATUS_RETRIES = 4
    private val TRANSIENT_STATUS_DELAYS_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L)
    private const val RETRY_AFTER_CAP_SECONDS = 120.0

    /**
     * Milliseconds to wait before repeating a lidar file GET, or null when [statusCode] is permanent
     * or [attempt] has used the retry budget. Retry-After is seconds, capped at 120;
     * a missing or non-numeric value waits 10, 30, 60, then 120 seconds.
     * Catalog requests keep their own short schedule.
     */
    fun transientStatusDelayMillis(statusCode: Int, attempt: Int, retryAfterHeader: String?): Long? {
        if (statusCode != 408 && statusCode != 429 && statusCode !in 500..599) return null
        if (attempt !in TRANSIENT_STATUS_DELAYS_MS.indices) return null
        val seconds = retryAfterHeader?.trim()?.toDoubleOrNull()
        if (seconds != null && seconds.isFinite() && seconds >= 0.0) {
            return (seconds.coerceAtMost(RETRY_AFTER_CAP_SECONDS) * 1000.0).toLong()
        }
        return TRANSIENT_STATUS_DELAYS_MS[attempt]
    }

    fun classify(error: Throwable, bytesReceived: Long): SurfaceTransferFailure {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        if (chain.any { it is SSLException || it is ProtocolException }) return SurfaceTransferFailure.FATAL
        if (bytesReceived > 0) return SurfaceTransferFailure.INTERRUPTED
        if (chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }) return SurfaceTransferFailure.UNREACHABLE
        // OkHttp: "failed to connect to host/ip (port 443) … after 30000ms"; a read timeout says "timeout" or "Read timed out".
        if (chain.any { it is SocketTimeoutException && it.message.orEmpty().contains("connect", ignoreCase = true) }) return SurfaceTransferFailure.UNREACHABLE
        return SurfaceTransferFailure.CONNECTION_PROBLEM
    }

    /** e.g. "USGS lidar server unreachable (rockyweb.usgs.gov, timed out after 30 s)". */
    fun unreachableMessage(host: String, error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" ")
        val timeoutMs = Regex("""after ([0-9][0-9,]*) ?ms""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        val detail = when {
            chain.any { it is UnknownHostException } -> "no network or name lookup failed"
            chain.any { it is SocketTimeoutException } -> "timed out" + (timeoutMs?.let { " after ${seconds(it)}" } ?: "")
            text.contains("refused", ignoreCase = true) -> "connection refused"
            chain.any { it is NoRouteToHostException } || text.contains("unreachable", ignoreCase = true) -> "no route to server"
            else -> null
        }
        val name = host.ifBlank { "unknown host" }
        return "USGS lidar server unreachable ($name" + (detail?.let { ", $it" } ?: "") + ")"
    }

    /** Progress line before a retry; "interrupted transfer" only when lidar bytes had already arrived. */
    fun retryMessage(file: Int, files: Int, retry: Int, failure: SurfaceTransferFailure): String {
        val reason = when (failure) {
            SurfaceTransferFailure.INTERRUPTED -> "interrupted transfer"
            SurfaceTransferFailure.TRANSIENT_HTTP -> "the server asked to wait"
            else -> "connection problem"
        }
        val limit = if (failure == SurfaceTransferFailure.TRANSIENT_HTTP) MAX_TRANSIENT_STATUS_RETRIES else MAX_RETRIES
        return "Retrying AOL lidar $file/$files after $reason (retry $retry/$limit)"
    }

    /**
     * Runs [transfer] with up to [MAX_RETRIES] retries for broken connections and interrupted transfers,
     * and up to [MAX_TRANSIENT_STATUS_RETRIES] retries for HTTP 408, 429, and 5xx.
     * A server that never accepts a connection fails at once with [SurfaceServerUnreachableException];
     * TLS, protocol errors, and permanent HTTP statuses are thrown as they are.
     * [bytesReceived] reports the current attempt's bytes. Downloads stay one file at a time.
     */
    suspend fun run(
        onRetry: suspend (Int, SurfaceTransferFailure) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
        bytesReceived: () -> Long = { 0L },
        host: String = "",
        transfer: suspend () -> Unit
    ) {
        var connectionAttempt = 0
        var statusAttempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                transfer()
                return
            } catch (error: SurfaceHttpStatusException) {
                currentCoroutineContext().ensureActive()
                val wait = transientStatusDelayMillis(error.code, statusAttempt, error.retryAfter)
                if (wait == null) throw error
                statusAttempt++
                onRetry(statusAttempt, SurfaceTransferFailure.TRANSIENT_HTTP)
                pause(wait)
            } catch(error: IOException) {
                currentCoroutineContext().ensureActive()
                when (val failure = classify(error, bytesReceived())) {
                    SurfaceTransferFailure.FATAL -> throw error
                    SurfaceTransferFailure.UNREACHABLE -> throw SurfaceServerUnreachableException(host, unreachableMessage(host, error), error)
                    else -> {
                        if(connectionAttempt==MAX_RETRIES) throw error
                        connectionAttempt++
                        onRetry(connectionAttempt, failure)
                        pause(500L*connectionAttempt)
                    }
                }
            }
        }
    }

    private fun seconds(ms: Long): String =
        if (ms % 1000L == 0L) "${ms / 1000L} s" else String.format(Locale.US, "%.1f s", ms / 1000.0)
}
