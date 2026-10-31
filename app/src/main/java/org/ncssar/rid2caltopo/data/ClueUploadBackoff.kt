package org.ncssar.rid2caltopo.data

/**
 * Per-clue upload retry spacing. Starts at 2 s and doubles to 60 s.
 * Auth and transport failures share this schedule for that clue so a local auth
 * miss cannot spin; other pending clues are not blocked.
 */
class ClueUploadBackoff(
    private val initialMs: Long = 2_000L,
    private val maxMs: Long = 60_000L,
) {
    private var nextMs = initialMs

    fun nextDelayMs(): Long {
        val delay = nextMs
        nextMs = (nextMs * 2).coerceAtMost(maxMs)
        return delay
    }

    fun reset() {
        nextMs = initialMs
    }

    fun peekMs(): Long = nextMs
}

/** Independent backoff clocks keyed by clue id. */
class ClueUploadBackoffTable(
    private val initialMs: Long = 2_000L,
    private val maxMs: Long = 60_000L,
) {
    private val byId = java.util.concurrent.ConcurrentHashMap<String, ClueUploadBackoff>()
    private val nextAttemptAtElapsedMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun backoff(id: String): ClueUploadBackoff =
        byId.getOrPut(id) { ClueUploadBackoff(initialMs, maxMs) }

    fun nextDelayMs(id: String): Long = backoff(id).nextDelayMs()

    fun reset(id: String) {
        byId[id]?.reset()
        nextAttemptAtElapsedMs.remove(id)
    }

    fun clearAll() {
        byId.clear()
        nextAttemptAtElapsedMs.clear()
    }

    fun markDelayedUntil(id: String, elapsedRealtimeMs: Long) {
        nextAttemptAtElapsedMs[id] = elapsedRealtimeMs
    }

    fun readyAt(id: String): Long = nextAttemptAtElapsedMs[id] ?: 0L

    fun isReady(id: String, nowElapsedMs: Long): Boolean = readyAt(id) <= nowElapsedMs
}
