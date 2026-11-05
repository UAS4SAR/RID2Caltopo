package org.ncssar.rid2caltopo.util

/**
 * Strips precise coordinates from diagnostic lines before they are written.
 * Altitude, zoom, and durations stay. Labeled lat/lon and degree pairs do not.
 */
object CoordinateLogSanitizer {
    private val labeled = Regex(
        """(?i)\b([a-z0-9_]*(?:latitude|longitude|lat|lng|lon))\s*[:=]\s*-?\d+\.\d{4,}"""
    )
    private val pair = Regex(
        """(?<![A-Za-z0-9.])-?\d{1,3}\.\d{5,}\s*,\s*-?\d{1,3}\.\d{5,}"""
    )

    @JvmStatic
    fun redact(message: String?): String {
        if (message.isNullOrEmpty()) return message ?: ""
        if (message.none { it.isDigit() }) return message
        val labeledGone = labeled.replace(message) { "${it.groupValues[1]}=[redacted]" }
        return pair.replace(labeledGone, "[redacted]")
    }
}
