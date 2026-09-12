package org.ncssar.rid2caltopo.video

/** Deduplicate queued/completed areas, but allow failed transfers to retry on location updates. */
internal class TerrainPrefetchGate {
    private val scheduled = HashSet<String>()
    private val retryAfter = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()

    @Synchronized
    fun schedule(key: String, nowMs: Long): Boolean {
        if ((retryAfter[key] ?: 0L) > nowMs) return false
        return scheduled.add(key)
    }

    @Synchronized
    fun finish(key: String, complete: Boolean, nowMs: Long) {
        if (complete) {
            failures.remove(key)
            retryAfter.remove(key)
        } else {
            scheduled.remove(key)
            val count = (failures[key] ?: 0) + 1
            failures[key] = count
            retryAfter[key] = nowMs + minOf(900_000L, 30_000L * (1L shl minOf(count - 1, 5)))
        }
    }
}
