package org.ncssar.rid2caltopo.video.mapcache

import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal object MapCacheDebug {
    const val TAG = "MapCacheDebug"
    const val TAG_TILE = "MapCacheTile"
    const val TAG_DEM = "MapCacheDEM"
    const val TAG_ICON = "MapCacheIcon"
    const val TAG_STORE = "MapCacheStore"

    private val eventCount = AtomicLong(0L)
    private val summaryCounts = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile
    private var tagsRegistered = false

    private fun ensureRegistered() {
        if (tagsRegistered) return
        CaltopoClient.RegisterDebugTag(TAG)
        CaltopoClient.RegisterDebugTag(TAG_TILE)
        CaltopoClient.RegisterDebugTag(TAG_DEM)
        CaltopoClient.RegisterDebugTag(TAG_ICON)
        CaltopoClient.RegisterDebugTag(TAG_STORE)
        tagsRegistered = true
    }

    private fun isTagEnabled(tag: String): Boolean {
        ensureRegistered()
        // Allow the umbrella tag to enable all map-cache debug categories.
        return CTDebugEnabled(TAG) || CTDebugEnabled(tag)
    }

    fun isEnabled(): Boolean {
        return isDebugEnabled()
    }

    fun isDebugEnabled(): Boolean {
        ensureRegistered()
        return CTDebugEnabled(TAG) || CTDebugEnabled(TAG_TILE) || CTDebugEnabled(TAG_DEM) ||
            CTDebugEnabled(TAG_ICON) || CTDebugEnabled(TAG_STORE)
    }

    fun isLudicrousEnabled(): Boolean {
        ensureRegistered()
        if (CaltopoClient.DebugLevel < CaltopoClient.DebugLevelInfo) return false
        return isTagEnabled(TAG) || isTagEnabled(TAG_TILE) || isTagEnabled(TAG_DEM) ||
            isTagEnabled(TAG_ICON) || isTagEnabled(TAG_STORE)
    }

    fun setEnabled(value: Boolean) {
        ensureRegistered()
        CTDebug(TAG, "setEnabled($value) deprecated; use logging level + debug tag filters.")
    }

    fun log(message: String) {
        info(TAG, message)
    }

    fun debug(tag: String, message: String) {
        if (!isTagEnabled(tag)) return
        val seq = eventCount.incrementAndGet()
        CTDebug(tag, "#$seq $message")
    }

    fun info(tag: String, message: String) {
        if (!isTagEnabled(tag)) return
        val seq = eventCount.incrementAndGet()
        CTInfo(tag, "#$seq $message")
    }

    fun warn(tag: String, message: String) {
        val seq = eventCount.incrementAndGet()
        CTWarn(tag, "#$seq $message")
    }

    /**
     * Aggregates repetitive DEM diagnostics that previously embedded coordinates (and were
     * fully redacted in exported logs). Emits count+reason without location details.
     */
    fun warnSummary(tag: String, reason: String, detail: String? = null) {
        val key = "$tag|$reason"
        val count = summaryCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        if (count <= 3 || count % 50 == 0) {
            val seq = eventCount.incrementAndGet()
            val suffix = detail?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            CTWarn(tag, "#$seq dem summary reason=$reason count=$count$suffix")
        }
    }

    fun error(tag: String, message: String) {
        val seq = eventCount.incrementAndGet()
        CTError(tag, "#$seq $message")
    }
}
