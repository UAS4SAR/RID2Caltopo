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
}

/** The lidar server could not be reached at all; the message is shown to the operator as is. */
internal class SurfaceServerUnreachableException(val host: String, message: String, cause: Throwable) : IOException(message, cause)

/** iOS mirrors the classification and wording in OperationalSurfaceTransferFailure (R2CCore). */
internal object SurfaceTransferRetry {
    const val MAX_RETRIES = 2

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
        val reason = if (failure == SurfaceTransferFailure.INTERRUPTED) "interrupted transfer" else "connection problem"
        return "Retrying AOL lidar $file/$files after $reason (retry $retry/$MAX_RETRIES)"
    }

    /**
     * Runs [transfer] with up to [MAX_RETRIES] retries for broken connections and interrupted transfers.
     * A server that never accepts a connection fails at once with [SurfaceServerUnreachableException];
     * TLS and protocol errors are thrown as they are. [bytesReceived] reports the current attempt's bytes.
     */
    suspend fun run(
        onRetry: suspend (Int, SurfaceTransferFailure) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
        bytesReceived: () -> Long = { 0L },
        host: String = "",
        transfer: suspend () -> Unit
    ) {
        for(attempt in 0..MAX_RETRIES) {
            currentCoroutineContext().ensureActive()
            try {
                transfer()
                return
            } catch(error: IOException) {
                currentCoroutineContext().ensureActive()
                when (val failure = classify(error, bytesReceived())) {
                    SurfaceTransferFailure.FATAL -> throw error
                    SurfaceTransferFailure.UNREACHABLE -> throw SurfaceServerUnreachableException(host, unreachableMessage(host, error), error)
                    else -> {
                        if(attempt==MAX_RETRIES) throw error
                        onRetry(attempt+1, failure)
                        pause(500L*(attempt+1))
                    }
                }
            }
        }
    }

    private fun seconds(ms: Long): String =
        if (ms % 1000L == 0L) "${ms / 1000L} s" else String.format(Locale.US, "%.1f s", ms / 1000.0)
}
