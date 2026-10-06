package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.ncssar.rid2caltopo.video.AndroidClueRecord

/** What a flight's archived GeoJSON holds that the KMZ and late clue binding need. */
data class FlightArchiveContents(
    val title: String,
    val remoteId: String,
    val points: List<ClueBindingPoint>,
) {
    val designator: String get() = AwaitingMapClueMatch.designatorOfLabel(title)
    val kmzPoints: List<FlightKmzPoint> get() = points.map { FlightKmzPoint(it.latitude, it.longitude, it.altitudeMeters) }
    fun candidate(id: String) = AwaitingMapClueMatch.Candidate(id, designator, points.map { it.timeMs })
}

/** Reading archived tracks and rebuilding a flight KMZ from them (late clues, interrupted writes). */
object FlightArchiveRebuild {
    /** Altitudes at or below this are WaypointTrack's "no valid altitude" sentinel. */
    private const val INVALID_ALTITUDE = -999.0

    /**
     * Decodes WaypointTrack (and Apple) GeoJSON: coordinates are [lng, lat, alt, timeMs] as strings or
     * numbers; `r2c_point_received_ms` and `r2c_point_drone_clock` are optional aligned arrays.
     */
    fun decode(bytes: ByteArray): FlightArchiveContents? = runCatching {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val feature = root.optJSONArray("features")?.optJSONObject(0) ?: return null
        val properties = feature.optJSONObject("properties") ?: JSONObject()
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val received = properties.optJSONArray("r2c_point_received_ms")
        val droneClock = properties.optJSONArray("r2c_point_drone_clock")
        val points = (0 until coordinates.length()).mapNotNull { index ->
            val point = coordinates.optJSONArray(index) ?: return@mapNotNull null
            val lon = point.optString(0).toDoubleOrNull() ?: return@mapNotNull null
            val lat = point.optString(1).toDoubleOrNull() ?: return@mapNotNull null
            val time = point.optString(3).toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            val altitude = point.optString(2).toDoubleOrNull()?.takeIf { it.isFinite() && it > INVALID_ALTITUDE }
            val receivedAt = received?.takeIf { index < it.length() && !it.isNull(index) }?.optString(index)?.toDoubleOrNull()?.toLong()
            val clock = droneClock?.takeIf { index < it.length() && !it.isNull(index) }?.optBoolean(index, true) ?: true
            ClueBindingPoint(time, receivedAt, lat, lon, altitude, "track", clock)
        }
        val remote = properties.optJSONObject("r2c_prop")?.optString("rid").orEmpty()
            .ifEmpty { properties.optString("r2c_remote_id") }
        FlightArchiveContents(properties.optString("title"), remote, points)
    }.getOrNull()

    /** Clues owned by the archive [archiveId] among [archives] (same rule as live flights), oldest first. */
    fun ownedClues(
        archiveId: String,
        archives: Map<String, FlightArchiveContents>,
        clues: List<AndroidClueRecord>,
    ): List<AndroidClueRecord> {
        val candidates = archives.map { (id, contents) -> contents.candidate(id) }
        return AwaitingMapClueMatch.ownedClues(clues, archiveId, candidates,
            { it.sourceDesignator }, { it.ownershipTimeMs }, { it.binding?.flightId })
            .sortedBy { it.ownershipTimeMs }
    }

    /** Binds a clue saved without a usable binding to the archived flight's nearest stored point. */
    fun bindingFallback(record: AndroidClueRecord, contents: FlightArchiveContents): AndroidClueRecord {
        val existing = record.binding
        if (existing?.nearest != null) return record
        val binding = ClueBinder.bind(
            aircraftId = existing?.aircraftId ?: contents.remoteId.ifEmpty { record.sourceDesignator },
            flightId = existing?.flightId,
            captureTimeMs = existing?.captureTimeMs ?: record.createdAtMs,
            captureTimeSource = existing?.captureTimeSource ?: "app-receive",
            captureReceivedAtMs = existing?.captureReceivedAtMs ?: record.createdAtMs,
            points = contents.points,
            framePosition = existing?.framePosition,
            originIsWaypoint = false,
        )
        return record.copy(binding = binding)
    }

    fun kmzClue(record: AndroidClueRecord, jpeg: ByteArray?): FlightKmzClue = FlightKmzClue(
        title = record.title,
        description = record.publishedDescription,
        capturedAtMs = record.ownershipTimeMs,
        latitude = record.lat,
        longitude = record.lng,
        altitudeMeters = record.alt.takeIf { it.isFinite() },
        jpeg = jpeg,
        localOnly = !record.publishToCaltopo,
        binding = record.binding,
    )

    /** `<base>.json` -> `<base>.kmz` (OperatorArchiveFilename.track / clueReport). */
    fun kmzName(geoJsonName: String): String = geoJsonName.removeSuffix(".json") + ".kmz"
}

/** `properties.r2c_flight_id` written by WaypointTrack (the awaiting-map journal / live track id). */
fun FlightArchiveRebuild.flightId(bytes: ByteArray): String? = runCatching {
    JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("features")?.optJSONObject(0)
        ?.optJSONObject("properties")?.optString("r2c_flight_id")?.takeIf { it.isNotEmpty() }
}.getOrNull()
