package org.ncssar.rid2caltopo.mapping

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object MappingTimestamps {
    fun generated(value: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
        Instant.parse(value).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss z"))
    }.getOrDefault("Time unavailable")
    fun captured(value: String): String = MappingLibraryEntry.localCapture(value)?.let {
        it.format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss"))
    } ?: "Time unavailable"
}
