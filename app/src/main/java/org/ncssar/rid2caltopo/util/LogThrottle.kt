package org.ncssar.rid2caltopo.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

/**
 * In-memory log dedup / rate-limit helper.
 *
 * Holds only brief ConcurrentHashMap bucket locks while updating counters — never
 * disk IO, never a lock across slow work, safe to call from Main.
 */
object LogThrottle {
    private class Entry(
        @Volatile var lastMessage: String,
        @Volatile var lastEmitMs: Long,
        @Volatile var repeatCount: Int,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val rateLimitAtMs = ConcurrentHashMap<String, AtomicLong>()

    /** Injectable clock for unit tests. */
    @JvmField
    @Volatile
    var clockMs: LongSupplier = LongSupplier { System.currentTimeMillis() }

    /**
     * Returns [message] (optionally with a `(repeated N×)` suffix) when it should be
     * logged, or null when suppressed.
     *
     * - [onChangeOnly]: identical messages are suppressed until they change, unless
     *   [minIntervalMs] forces a periodic summary.
     * - [minIntervalMs] > 0: identical (or all, when not onChangeOnly) emissions are
     *   gated to at most one per interval; suppressed repeats may be summarized.
     */
    @JvmStatic
    @JvmOverloads
    fun emit(
        key: String,
        message: String,
        minIntervalMs: Long = 0L,
        onChangeOnly: Boolean = false,
    ): String? {
        val now = clockMs.asLong
        var outcome: String? = null
        entries.compute(key) { _, existing ->
            if (existing == null) {
                outcome = message
                Entry(message, now, 0)
            } else {
                val same = existing.lastMessage == message
                when {
                    same && onChangeOnly -> {
                        if (minIntervalMs > 0L && now - existing.lastEmitMs >= minIntervalMs) {
                            val repeats = existing.repeatCount
                            existing.repeatCount = 0
                            existing.lastEmitMs = now
                            outcome = if (repeats > 0) {
                                "$message (repeated ${repeats}×)"
                            } else {
                                message
                            }
                        } else {
                            existing.repeatCount++
                            outcome = null
                        }
                        existing
                    }
                    same && minIntervalMs > 0L -> {
                        if (now - existing.lastEmitMs < minIntervalMs) {
                            existing.repeatCount++
                            outcome = null
                        } else {
                            val repeats = existing.repeatCount
                            existing.repeatCount = 0
                            existing.lastEmitMs = now
                            outcome = if (repeats > 0) {
                                "$message (repeated ${repeats}×)"
                            } else {
                                message
                            }
                        }
                        existing
                    }
                    same -> {
                        // No gate: always emit.
                        existing.lastEmitMs = now
                        existing.repeatCount = 0
                        outcome = message
                        existing
                    }
                    else -> {
                        // Message changed — emit immediately; prior repeats are dropped.
                        existing.lastMessage = message
                        existing.lastEmitMs = now
                        existing.repeatCount = 0
                        outcome = message
                        existing
                    }
                }
            }
        }
        return outcome
    }

    /** Simple interval gate: true at most once per [intervalMs] for [key]. */
    @JvmStatic
    fun shouldLog(key: String, intervalMs: Long): Boolean {
        if (intervalMs <= 0L) return true
        val now = clockMs.asLong
        var allowed = false
        rateLimitAtMs.compute(key) { _, existing ->
            val holder = existing ?: AtomicLong(0L)
            val last = holder.get()
            if (last == 0L || now - last >= intervalMs) {
                holder.set(now)
                allowed = true
            }
            holder
        }
        return allowed
    }

    @JvmStatic
    fun resetForTest() {
        entries.clear()
        rateLimitAtMs.clear()
        clockMs = LongSupplier { System.currentTimeMillis() }
    }
}
