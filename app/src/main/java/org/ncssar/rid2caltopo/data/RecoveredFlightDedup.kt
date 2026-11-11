/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A leftover waypoint journal is the same flight as a saved archive when the drone serial matches
 * and the point times overlap, even when the file names differ. The journal is retired only after
 * the saved file holds every journal point. Missing points are merged into that file.
 */
object RecoveredFlightDedup {
    enum class Kind { WRITE_NEW, RETIRE, MERGE }

    class SavedFlight(
        val remoteId: String,
        val dayDirectory: String,
        val fileName: String,
        val pointTimesMs: LongArray,
    )

    class Decision(
        val kind: Kind,
        val dayDirectory: String,
        val fileName: String,
        val missingTimesMs: LongArray,
    )

    interface Lookup {
        fun flights(): List<SavedFlight>
        /** True only when [fileName] then contains every journal point time. Never creates a second file. */
        fun mergeInto(dayDirectory: String, fileName: String, journalPoints: JSONArray): Boolean
    }

    @JvmStatic
    fun canonical(remoteId: String): String =
        remoteId.uppercase().filter { it.isLetterOrDigit() }

    @JvmStatic
    fun decide(remoteId: String, journalTimesMs: LongArray, saved: List<SavedFlight>): Decision {
        val serial = canonical(remoteId)
        val journal = journalTimesMs.filter { it > 0L }.toLongArray()
        if (serial.isEmpty() || journal.isEmpty()) return writeNew()
        val journalMin = journal.min()
        val journalMax = journal.max()
        var best: SavedFlight? = null
        var bestExact = -1
        var bestInside = -1
        for (flight in saved) {
            if (canonical(flight.remoteId) != serial) continue
            val savedTimes = flight.pointTimesMs.filter { it > 0L }.toLongArray()
            if (savedTimes.isEmpty()) continue
            val savedMin = savedTimes.min()
            val savedMax = savedTimes.max()
            if (journalMin > savedMax || savedMin > journalMax) continue
            val inside = journal.count { it in savedMin..savedMax }
            if (inside * 2 < journal.size) continue
            val savedInside = savedTimes.count { it in journalMin..journalMax }
            if (savedInside * 2 < savedTimes.size) continue
            val exact = journal.count { it in savedTimes.toSet() }
            if (exact > bestExact || (exact == bestExact && inside > bestInside)) {
                best = flight
                bestExact = exact
                bestInside = inside
            }
        }
        val match = best ?: return writeNew()
        val have = match.pointTimesMs.toSet()
        val missing = journal.filter { it !in have }.toLongArray()
        val kind = if (missing.isEmpty()) Kind.RETIRE else Kind.MERGE
        return Decision(kind, match.dayDirectory, match.fileName, missing)
    }

    /**
     * Inserts journal coordinates whose time is not already in the GeoJSON, in time order.
     * Returns the original bytes when nothing is missing, or null when the file cannot be read.
     */
    @JvmStatic
    fun mergeGeoJson(geoJson: ByteArray, journalPoints: JSONArray): ByteArray? {
        val root = runCatching { JSONObject(String(geoJson, Charsets.UTF_8)) }.getOrNull() ?: return null
        val feature = root.optJSONArray("features")?.optJSONObject(0) ?: return null
        val properties = feature.optJSONObject("properties") ?: JSONObject().also { feature.put("properties", it) }
        val geometry = feature.optJSONObject("geometry") ?: return null
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        val received = FlightArchivePointTiming.readReceivedAtMs(properties, coordinates.length()).toMutableList()
        val droneClock = FlightArchivePointTiming.readDroneClock(properties, coordinates.length()).toMutableList()
        val rows = ArrayList<Row>(coordinates.length() + journalPoints.length())
        val have = HashSet<Long>()
        for (index in 0 until coordinates.length()) {
            val point = coordinates.optJSONArray(index) ?: continue
            val time = timeOf(point)
            rows.add(Row(copy(point), received.getOrNull(index), droneClock.getOrElse(index) { true }, time))
            if (time > 0L) have.add(time)
        }
        var added = false
        for (index in 0 until journalPoints.length()) {
            val point = journalPoints.optJSONArray(index) ?: continue
            val time = timeOf(point)
            if (time <= 0L || time in have) continue
            rows.add(Row(copy(point), time, true, time))
            have.add(time)
            added = true
        }
        if (!added) return geoJson
        rows.sortBy { it.timeMs }
        val next = JSONArray()
        val nextReceived = ArrayList<Long?>(rows.size)
        val nextClock = ArrayList<Boolean>(rows.size)
        for (row in rows) {
            next.put(row.point)
            nextReceived.add(row.receivedAtMs)
            nextClock.add(row.droneClock)
        }
        geometry.put("coordinates", next)
        properties.remove(FlightArchivePointTiming.LEGACY_RECEIVED_MS)
        properties.remove(FlightArchivePointTiming.LEGACY_DRONE_CLOCK)
        properties.remove(FlightArchivePointTiming.RECV_BASE_MS)
        properties.remove(FlightArchivePointTiming.RECV_OFF_MS)
        properties.remove(FlightArchivePointTiming.NO_DRONE_CLOCK)
        FlightArchivePointTiming.write(properties, nextReceived, nextClock, rows.size)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun writeNew() = Decision(Kind.WRITE_NEW, "", "", LongArray(0))

    private fun timeOf(point: JSONArray): Long {
        val raw = point.opt(3) ?: return 0L
        return when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().toLongOrNull() ?: 0L
        }
    }

    private fun copy(point: JSONArray): JSONArray {
        val copy = JSONArray()
        for (index in 0 until point.length()) copy.put(point.opt(index))
        return copy
    }

    private class Row(val point: JSONArray, val receivedAtMs: Long?, val droneClock: Boolean, val timeMs: Long)
}
