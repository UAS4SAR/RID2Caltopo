package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact encoding of per-point receive-time and drone-clock flags on archived flight GeoJSON.
 *
 * Writers emit only the compact keys below. Readers still accept the bulky Oct-2026 arrays
 * (`r2c_point_received_ms`, `r2c_point_drone_clock`) so existing archives keep working.
 *
 * Compact receive times:
 * - `r2c_point_recv_base_ms`: one absolute epoch-ms base (first non-null receive time)
 * - `r2c_point_recv_off_ms`: aligned integer ms offsets from that base (`null` where unknown)
 *   Both keys are omitted when every receive time is null (same meaning as a pre-Oct-2026 file).
 *
 * Compact drone-clock flags:
 * - `r2c_point_no_drone_clock`: sorted indices where the point used receive-time fallback
 *   Omitted when every point is true (the common case).
 */
object FlightArchivePointTiming {
    const val LEGACY_RECEIVED_MS = "r2c_point_received_ms"
    const val LEGACY_DRONE_CLOCK = "r2c_point_drone_clock"
    const val RECV_BASE_MS = "r2c_point_recv_base_ms"
    const val RECV_OFF_MS = "r2c_point_recv_off_ms"
    const val NO_DRONE_CLOCK = "r2c_point_no_drone_clock"

    /** Writes compact keys only; never emits the legacy arrays. */
    @JvmStatic
    fun write(properties: JSONObject, receivedAtMs: List<Long?>, droneClock: List<Boolean>, pointCount: Int) {
        val first = (0 until pointCount).firstOrNull { index ->
            index < receivedAtMs.size && receivedAtMs[index] != null
        }
        if (first != null) {
            val base = receivedAtMs[first]!!
            val offsets = JSONArray()
            for (index in 0 until pointCount) {
                val value = if (index < receivedAtMs.size) receivedAtMs[index] else null
                offsets.put(if (value == null) JSONObject.NULL else value - base)
            }
            properties.put(RECV_BASE_MS, base)
            properties.put(RECV_OFF_MS, offsets)
        }
        val missing = JSONArray()
        for (index in 0 until pointCount) {
            val isDrone = index >= droneClock.size || droneClock[index]
            if (!isDrone) missing.put(index)
        }
        if (missing.length() > 0) properties.put(NO_DRONE_CLOCK, missing)
    }

    fun readReceivedAtMs(properties: JSONObject, pointCount: Int): List<Long?> {
        properties.optJSONArray(LEGACY_RECEIVED_MS)?.let { legacy ->
            return (0 until pointCount).map { index ->
                if (index >= legacy.length() || legacy.isNull(index)) null
                else legacy.optString(index).toDoubleOrNull()?.toLong()
            }
        }
        if (!properties.has(RECV_BASE_MS) || !properties.has(RECV_OFF_MS)) {
            return List(pointCount) { null }
        }
        val base = properties.optLong(RECV_BASE_MS)
        val offsets = properties.optJSONArray(RECV_OFF_MS) ?: return List(pointCount) { null }
        return (0 until pointCount).map { index ->
            if (index >= offsets.length() || offsets.isNull(index)) null
            else offsets.optString(index).toDoubleOrNull()?.toLong()?.let { base + it }
        }
    }

    fun readDroneClock(properties: JSONObject, pointCount: Int): List<Boolean> {
        properties.optJSONArray(LEGACY_DRONE_CLOCK)?.let { legacy ->
            return (0 until pointCount).map { index ->
                if (index >= legacy.length() || legacy.isNull(index)) true
                else legacy.optBoolean(index, true)
            }
        }
        val flags = BooleanArray(pointCount) { true }
        val missing = properties.optJSONArray(NO_DRONE_CLOCK) ?: return flags.toList()
        for (i in 0 until missing.length()) {
            val index = missing.optString(i).toDoubleOrNull()?.toInt() ?: continue
            if (index in 0 until pointCount) flags[index] = false
        }
        return flags.toList()
    }
}
