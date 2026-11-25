package org.ncssar.rid2caltopo.data

/**
 * Session log size cap (Nov 24 2026 field review: a 10 MB log stopped mid-search and the
 * rest of the evening was lost). Once a log file is attached, a write that would pass the cap
 * closes the file and continues in a new Log_ file. Same rule as the iPad's AppleLog.
 */
object SessionLogRollover {
    @JvmStatic
    fun shouldRollOver(bytesWritten: Long, incomingBytes: Int, maxBytes: Long, fileAttached: Boolean): Boolean =
        fileAttached && bytesWritten > 0 && bytesWritten + incomingBytes > maxBytes

    @JvmStatic
    fun closingLine(maxBytes: Long): String =
        "INFO session log reached $maxBytes bytes; continued in the next Log_ file\n"
}
