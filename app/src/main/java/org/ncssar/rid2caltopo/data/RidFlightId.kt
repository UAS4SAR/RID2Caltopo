package org.ncssar.rid2caltopo.data

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable flight id, identical to iOS `RidFlightID.make` (apple/Sources/R2CCore/AwaitingMapFlight.swift):
 * a name-based UUID (version 5 / RFC 4122 variant bits) over the first 16 bytes of
 * SHA-256("<canonical aircraft id>|<flight start, Unix ms>"), lowercase. The same drone and start
 * time give the same id on both platforms. It is the awaiting-map journal / CalTopo live-track id,
 * the clue binding flightId and the archive's `properties.r2c_flight_id`.
 */
object RidFlightId {
    /** iOS `RidTrackStore.canonicalAircraftID`: uppercase, ASCII letters and digits only. */
    fun canonicalAircraftId(value: String): String =
        value.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }

    @JvmStatic
    fun make(aircraftId: String, startedAtMs: Long): String {
        val name = "${canonicalAircraftId(aircraftId)}|$startedAtMs"
        val bytes = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte() // name-based UUID
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // RFC 4122 variant
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
