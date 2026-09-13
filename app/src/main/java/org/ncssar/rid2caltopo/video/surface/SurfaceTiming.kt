package org.ncssar.rid2caltopo.video.surface

import org.ncssar.rid2caltopo.data.CaltopoClient
import java.util.Locale
import java.util.UUID

/** Sparse stage markers only; no location or source URL is logged. */
internal class SurfaceTiming(operation: String) : AutoCloseable {
    private val id = UUID.randomUUID().toString()
    private val start = System.nanoTime()
    private var last = start
    init { mark("start $operation") }
    fun mark(stage: String) {
        val now = System.nanoTime()
        CaltopoClient.CTDebug("OfflineTiming", "id=$id stage=$stage elapsedSeconds=${String.format(Locale.US,"%.3f",(now-start)/1e9)} stageSeconds=${String.format(Locale.US,"%.3f",(now-last)/1e9)}")
        last = now
    }
    override fun close() { mark("operation-ended") }
}
