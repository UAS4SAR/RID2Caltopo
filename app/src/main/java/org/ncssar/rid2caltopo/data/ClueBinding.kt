package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

// Clue-to-waypoint binding. Apple mirrors these rules in R2CCore/ClueBinding.swift; keep
// thresholds, JSON keys and wording identical.

/**
 * One track waypoint on the drone's clock. [timeMs] is what binding uses; [receivedAtMs] is the
 * app's receive time, kept only as a diagnostic so a drone clock offset can be measured.
 */
data class ClueBindingPoint(
    val timeMs: Long,
    val receivedAtMs: Long?,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    /** "rid", "dji-stream", "relay" or similar. */
    val source: String,
    /** False when the point had no drone timestamp and [timeMs] fell back to the receive time. */
    val droneClock: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("timeMs", timeMs)
        .put("receivedAtMs", receivedAtMs ?: JSONObject.NULL)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("altitudeMeters", altitudeMeters?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("source", source)
        .put("droneClock", droneClock)

    companion object {
        fun fromJson(json: JSONObject?): ClueBindingPoint? {
            json ?: return null
            if (!json.has("timeMs")) return null
            val lat = json.optDouble("latitude", Double.NaN)
            val lon = json.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) return null
            return ClueBindingPoint(
                timeMs = json.optLong("timeMs"),
                receivedAtMs = json.optLongOrNull("receivedAtMs"),
                latitude = lat,
                longitude = lon,
                altitudeMeters = json.optDouble("altitudeMeters", Double.NaN).takeIf { it.isFinite() },
                source = json.optString("source", "unknown"),
                droneClock = json.optBoolean("droneClock", true),
            )
        }
    }
}

enum class ClueBindingQuality(val raw: String) {
    /** Waypoint within 2 s of the capture (or the drone was hovering). */
    EXACT("exact"),
    /** 2–10 s. */
    APPROXIMATE("approximate"),
    /** 10–30 s; the position should be checked. */
    APPROXIMATE_WARNING("approximate-warning"),
    /** No waypoint within 30 s. */
    UNBOUND("unbound");

    companion object {
        fun of(raw: String?): ClueBindingQuality = values().firstOrNull { it.raw == raw } ?: UNBOUND
    }
}

data class ClueBindingFramePosition(val latitude: Double, val longitude: Double) {
    fun toJson(): JSONObject = JSONObject().put("latitude", latitude).put("longitude", longitude)

    companion object {
        fun fromJson(json: JSONObject?): ClueBindingFramePosition? {
            json ?: return null
            val lat = json.optDouble("latitude", Double.NaN)
            val lon = json.optDouble("longitude", Double.NaN)
            return if (lat.isFinite() && lon.isFinite()) ClueBindingFramePosition(lat, lon) else null
        }
    }
}

/** The waypoint a clue is bound to. Offsets are waypoint time minus capture time, in milliseconds. */
data class ClueBinding(
    val aircraftId: String,
    /** Awaiting-map journal entry / live track id (WaypointTrack.deferredPublicationId). */
    val flightId: String?,
    /** Drone-clock time of the bound flight's first waypoint. */
    val flightStartMs: Long?,
    /** Frame capture time on the drone clock when available. */
    val captureTimeMs: Long,
    /** "stream-pts" (drone encoder clock) or "app-receive" (fallback). */
    val captureTimeSource: String,
    /** App receive time of the captured frame (diagnostic). */
    val captureReceivedAtMs: Long,
    val waypoint: ClueBindingPoint? = null,
    val offsetMs: Long? = null,
    /** Offset to the nearest waypoint even when it is beyond the binding limit (diagnostic). */
    val nearestOffsetMs: Long? = null,
    val quality: ClueBindingQuality = ClueBindingQuality.UNBOUND,
    val hovering: Boolean = false,
    /** True when the clue was projected from the bound waypoint (no frame-matched drone position). */
    val originIsWaypoint: Boolean = false,
    /** Drone position decoded from the frame itself (DJI SEI), when present. */
    val framePosition: ClueBindingFramePosition? = null,
    /** Waypoint nearest in time even beyond the binding limit; the projection origin when [originIsWaypoint]. */
    val nearest: ClueBindingPoint? = null,
) {
    /** True when the operator should look at the position (approximate and not hovering, or unbound). */
    val flagged: Boolean get() = quality != ClueBindingQuality.EXACT

    fun toJson(): JSONObject = JSONObject()
        .put("aircraftID", aircraftId)
        .put("flightID", flightId ?: JSONObject.NULL)
        .put("flightStartMs", flightStartMs ?: JSONObject.NULL)
        .put("captureTimeMs", captureTimeMs)
        .put("captureTimeSource", captureTimeSource)
        .put("captureReceivedAtMs", captureReceivedAtMs)
        .put("waypoint", waypoint?.toJson() ?: JSONObject.NULL)
        .put("offsetMs", offsetMs ?: JSONObject.NULL)
        .put("nearestOffsetMs", nearestOffsetMs ?: JSONObject.NULL)
        .put("quality", quality.raw)
        .put("hovering", hovering)
        .put("originIsWaypoint", originIsWaypoint)
        .put("framePosition", framePosition?.toJson() ?: JSONObject.NULL)
        .put("nearest", nearest?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject?): ClueBinding? {
            json ?: return null
            if (!json.has("captureTimeMs")) return null
            return ClueBinding(
                aircraftId = json.optString("aircraftID"),
                flightId = json.optStringOrNull("flightID"),
                flightStartMs = json.optLongOrNull("flightStartMs"),
                captureTimeMs = json.optLong("captureTimeMs"),
                captureTimeSource = json.optString("captureTimeSource", "app-receive"),
                captureReceivedAtMs = json.optLong("captureReceivedAtMs", json.optLong("captureTimeMs")),
                waypoint = ClueBindingPoint.fromJson(json.optJSONObject("waypoint")),
                offsetMs = json.optLongOrNull("offsetMs"),
                nearestOffsetMs = json.optLongOrNull("nearestOffsetMs"),
                quality = ClueBindingQuality.of(json.optString("quality")),
                hovering = json.optBoolean("hovering"),
                originIsWaypoint = json.optBoolean("originIsWaypoint"),
                framePosition = ClueBindingFramePosition.fromJson(json.optJSONObject("framePosition")),
                nearest = ClueBindingPoint.fromJson(json.optJSONObject("nearest")),
            )
        }
    }
}

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toLong() ?: optString(key).toLongOrNull()

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

object ClueBinder {
    const val EXACT_LIMIT_MS = 2_000L
    const val APPROXIMATE_LIMIT_MS = 10_000L
    /** No binding beyond this distance in time. */
    const val BINDING_LIMIT_MS = 30_000L
    /** Waypoints this close together (or to the frame's own position) mean the drone was hovering. */
    const val HOVER_RADIUS_METERS = 5.0

    fun quality(offsetMs: Long?, hovering: Boolean): ClueBindingQuality {
        offsetMs ?: return ClueBindingQuality.UNBOUND
        val magnitude = abs(offsetMs)
        if (magnitude > BINDING_LIMIT_MS) return ClueBindingQuality.UNBOUND
        if (hovering || magnitude <= EXACT_LIMIT_MS) return ClueBindingQuality.EXACT
        if (magnitude <= APPROXIMATE_LIMIT_MS) return ClueBindingQuality.APPROXIMATE
        return ClueBindingQuality.APPROXIMATE_WARNING
    }

    /** Index of the waypoint nearest in time; ties go to the earlier waypoint. */
    fun nearestIndex(points: List<ClueBindingPoint>, captureTimeMs: Long): Int? {
        var best: Int? = null
        var bestDistance = Long.MAX_VALUE
        points.forEachIndexed { index, point ->
            val distance = abs(point.timeMs - captureTimeMs)
            if (distance < bestDistance || (distance == bestDistance && best?.let { point.timeMs < points[it].timeMs } == true)) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    fun bind(
        aircraftId: String,
        flightId: String?,
        captureTimeMs: Long,
        captureTimeSource: String,
        captureReceivedAtMs: Long,
        points: List<ClueBindingPoint>,
        framePosition: ClueBindingFramePosition?,
        originIsWaypoint: Boolean,
    ): ClueBinding {
        val sorted = points.sortedBy { it.timeMs }
        var binding = ClueBinding(
            aircraftId = aircraftId, flightId = flightId, flightStartMs = sorted.firstOrNull()?.timeMs,
            captureTimeMs = captureTimeMs, captureTimeSource = captureTimeSource,
            captureReceivedAtMs = captureReceivedAtMs, originIsWaypoint = originIsWaypoint,
            framePosition = framePosition,
        )
        nearestIndex(sorted, captureTimeMs)?.let { index ->
            val nearest = sorted[index]
            val offset = nearest.timeMs - captureTimeMs
            binding = binding.copy(nearestOffsetMs = offset, nearest = nearest)
            if (abs(offset) <= BINDING_LIMIT_MS) {
                binding = binding.copy(waypoint = nearest, offsetMs = offset,
                    hovering = hovering(sorted, captureTimeMs, nearest, framePosition))
            }
        }
        return binding.copy(quality = quality(binding.offsetMs, binding.hovering))
    }

    /**
     * Re-binds against the waypoints available now (used while the clue form is open and once more
     * on Submit). Without any points the stored binding is kept. A submitted clue is never re-bound.
     */
    fun refresh(binding: ClueBinding, points: List<ClueBindingPoint>): ClueBinding {
        if (points.isEmpty()) return binding
        val next = bind(binding.aircraftId, binding.flightId, binding.captureTimeMs, binding.captureTimeSource,
            binding.captureReceivedAtMs, points, binding.framePosition, binding.originIsWaypoint)
        return if (next.flightStartMs == null) next.copy(flightStartMs = binding.flightStartMs) else next
    }

    internal fun hovering(
        points: List<ClueBindingPoint>,
        captureTimeMs: Long,
        waypoint: ClueBindingPoint,
        framePosition: ClueBindingFramePosition?,
    ): Boolean {
        if (framePosition != null) {
            val distance = distanceMeters(framePosition.latitude, framePosition.longitude, waypoint.latitude, waypoint.longitude)
            if (distance != null && distance <= HOVER_RADIUS_METERS) return true
        }
        val before = points.lastOrNull { it.timeMs <= captureTimeMs } ?: return false
        val after = points.firstOrNull { it.timeMs >= captureTimeMs } ?: return false
        val distance = distanceMeters(before.latitude, before.longitude, after.latitude, after.longitude) ?: return false
        return distance <= HOVER_RADIUS_METERS
    }

    internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double? =
        RidGeometry.relativePosition(lat1, lon1, lat2, lon2)?.distanceMeters

    /**
     * The origin move a Submit applies: when the clue was projected from the bound waypoint and the
     * submit-time binding found a nearer waypoint than the one the form last showed (from, to).
     */
    fun originShift(shown: ClueBinding?, submitted: ClueBinding?): Pair<ClueBindingPoint, ClueBindingPoint>? {
        submitted ?: return null
        if (!submitted.originIsWaypoint) return null
        val from = shown?.nearest ?: return null
        val to = submitted.nearest ?: return null
        return if (from != to) from to to else null
    }

    /**
     * Moves a clue that was projected from the bound waypoint when a nearer waypoint replaces it at
     * Submit: the camera vector (bearing, range) is unchanged, so the clue shifts by the origin's
     * displacement.
     */
    fun translated(latitude: Double, longitude: Double, from: ClueBindingPoint, to: ClueBindingPoint): Pair<Double, Double> {
        val originCos = cos(Math.toRadians(from.latitude))
        val clueCos = cos(Math.toRadians(latitude))
        val longitudeShift = to.longitude - from.longitude
        val scaled = if (abs(clueCos) > 1e-9) longitudeShift * originCos / clueCos else longitudeShift
        return (latitude + (to.latitude - from.latitude)) to (longitude + scaled)
    }
}

object ClueBindingText {
    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    /** Signed seconds with millisecond precision, e.g. "+1.234 s" or "-0.250 s". */
    fun offset(milliseconds: Long): String {
        val sign = if (milliseconds < 0) "-" else "+"
        val magnitude = abs(milliseconds)
        return String.format(Locale.US, "%s%d.%03d s", sign, magnitude / 1_000, magnitude % 1_000)
    }

    fun qualityLabel(binding: ClueBinding): String = when (binding.quality) {
        ClueBindingQuality.EXACT ->
            if (binding.hovering && abs(binding.offsetMs ?: 0) > ClueBinder.EXACT_LIMIT_MS) "exact (hovering)" else "exact"
        ClueBindingQuality.APPROXIMATE -> "approximate"
        ClueBindingQuality.APPROXIMATE_WARNING -> "approximate - check position"
        ClueBindingQuality.UNBOUND -> "not bound (no waypoint within 30 s)"
    }

    /** One line for the clue form. */
    fun formSummary(binding: ClueBinding): String {
        val bound = binding.offsetMs
        val nearest = binding.nearestOffsetMs
        return when {
            bound != null -> "${offset(bound)} · ${qualityLabel(binding)}"
            nearest != null -> "${qualityLabel(binding)}; nearest ${offset(nearest)}"
            else -> qualityLabel(binding)
        }
    }

    fun iso(milliseconds: Long): String = isoFormatter.format(Instant.ofEpochMilli(milliseconds))

    /** Lines appended to the clue description (CalTopo marker and KMZ). */
    fun descriptionLines(binding: ClueBinding): List<String> {
        val lines = mutableListOf("Waypoint binding:")
        val bound = binding.offsetMs
        val nearest = binding.nearestOffsetMs
        lines += when {
            bound != null -> "  Offset: ${offset(bound)} (waypoint minus capture, drone clock)"
            nearest != null -> "  Offset: none; nearest waypoint ${offset(nearest)}"
            else -> "  Offset: none; no waypoint"
        }
        lines += "  Quality: ${qualityLabel(binding)}"
        binding.waypoint?.let { waypoint ->
            lines += String.format(Locale.US, "  Waypoint: %.6f, %.6f at %s (%s%s)", waypoint.latitude, waypoint.longitude,
                iso(waypoint.timeMs), waypoint.source, if (waypoint.droneClock) "" else ", receive time")
        }
        lines += "  Capture: ${iso(binding.captureTimeMs)} (${binding.captureTimeSource})"
        val diagnostics = mutableListOf("capture ${iso(binding.captureReceivedAtMs)}")
        binding.waypoint?.receivedAtMs?.let { diagnostics += "waypoint ${iso(it)}" }
        lines += "  App receive times (diagnostic): ${diagnostics.joinToString(", ")}"
        return lines
    }

    /** Text for CalTopo and the KMZ: the stored description plus the binding block. */
    fun publishedDescription(description: String, binding: ClueBinding?): String {
        binding ?: return description
        val block = descriptionLines(binding).joinToString("\n")
        val trimmed = description.trim()
        return if (trimmed.isEmpty()) block else trimmed + "\n\n" + block
    }

    /** KML ExtendedData name/value pairs. */
    fun extendedData(binding: ClueBinding): List<Pair<String, String>> {
        val pairs = mutableListOf(
            "r2c_binding_quality" to binding.quality.raw,
            "r2c_binding_hovering" to binding.hovering.toString(),
            "r2c_capture_time" to iso(binding.captureTimeMs),
            "r2c_capture_time_source" to binding.captureTimeSource,
            "r2c_capture_received_at" to iso(binding.captureReceivedAtMs),
        )
        binding.offsetMs?.let { pairs += "r2c_binding_offset_s" to String.format(Locale.US, "%.3f", it / 1_000.0) }
        binding.waypoint?.let { waypoint ->
            pairs += "r2c_waypoint_time" to iso(waypoint.timeMs)
            pairs += "r2c_waypoint_lat" to String.format(Locale.US, "%.6f", waypoint.latitude)
            pairs += "r2c_waypoint_lon" to String.format(Locale.US, "%.6f", waypoint.longitude)
            pairs += "r2c_waypoint_source" to waypoint.source
            waypoint.receivedAtMs?.let { pairs += "r2c_waypoint_received_at" to iso(it) }
        }
        binding.flightId?.let { pairs += "r2c_flight_id" to it }
        return pairs
    }
}

/**
 * Maps a stream's presentation timestamps (drone encoder clock) to epoch milliseconds. The anchor is
 * the smallest receive-minus-PTS offset seen, i.e. the least-delayed frame, so jitter never moves a
 * frame later. Resets when the PTS clock jumps (new stream session).
 */
class StreamDroneClock {
    var offsetMs: Long? = null
        private set
    var lastPtsMs: Long? = null
        private set

    fun observe(ptsMicroseconds: Long?, receivedAtMs: Long) {
        if (ptsMicroseconds == null || ptsMicroseconds <= 0) return
        val ptsMs = ptsMicroseconds / 1_000
        val candidate = receivedAtMs - ptsMs
        lastPtsMs?.let { if (ptsMs < it - 1_000) offsetMs = null }
        offsetMs?.let { if (candidate > it + RESET_JUMP_MS) offsetMs = null }
        offsetMs = minOf(offsetMs ?: candidate, candidate)
        lastPtsMs = ptsMs
    }

    fun droneTimeMs(ptsMicroseconds: Long?): Long? {
        if (ptsMicroseconds == null || ptsMicroseconds <= 0) return null
        val offset = offsetMs ?: return null
        return ptsMicroseconds / 1_000 + offset
    }

    companion object {
        const val RESET_JUMP_MS = 5_000L
    }
}
