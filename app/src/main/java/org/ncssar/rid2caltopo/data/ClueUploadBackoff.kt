package org.ncssar.rid2caltopo.data

/**
 * Single-owner clue upload retry spacing. Starts at 2 s and doubles to 60 s.
 * Auth and transport failures share this schedule so a local auth miss cannot spin.
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
