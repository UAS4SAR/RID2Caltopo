package org.ncssar.rid2caltopo.airspace

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AirspaceServiceFailure(message: String, val rateLimited: Boolean = false,
    val retryAfter: String? = null) : IOException(message)

class AirspaceRetryPolicy {
    var failures = 0; private set
    var retryAt = Long.MIN_VALUE; private set
    fun permits(now: Long) = now >= retryAt
    fun succeeded() { failures = 0; retryAt = Long.MIN_VALUE }
    fun failed(rateLimited: Boolean, retryAfter: String?, now: Long): Long {
        failures = (failures + 1).coerceAtMost(5)
        val delay = 2_000L * (1L shl (failures - 1))
        val wait = maxOf(delay, serverDelay(retryAfter, now) ?: 0)
        retryAt = if (wait > Long.MAX_VALUE - now) Long.MAX_VALUE else now + wait
        return wait
    }
    companion object {
        const val NORMAL_INTERVAL_MS = 20 * 60 * 1_000L
        fun nextRoutineRefreshAfter(successAt: Long) = successAt + NORMAL_INTERVAL_MS
        fun serverDelay(value: String?, now: Long): Long? {
            val text = value?.trim() ?: return null
            text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { return (it * 1000).toLong() }
            return runCatching { (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull()
        }
    }
}
