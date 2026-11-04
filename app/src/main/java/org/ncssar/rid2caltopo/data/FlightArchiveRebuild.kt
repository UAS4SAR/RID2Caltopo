package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.video.AndroidClueFlightFolders
import org.ncssar.rid2caltopo.video.AndroidClueRecord
import org.ncssar.rid2caltopo.video.toAndroidClueRecord
import java.io.File

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
     * numbers. Per-point receive times / drone-clock flags use [FlightArchivePointTiming] (compact
     * writer format, with legacy absolute arrays still accepted).
     */
    fun decode(bytes: ByteArray): FlightArchiveContents? = runCatching {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val feature = root.optJSONArray("features")?.optJSONObject(0) ?: return null
        val properties = feature.optJSONObject("properties") ?: JSONObject()
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val received = FlightArchivePointTiming.readReceivedAtMs(properties, coordinates.length())
        val droneClock = FlightArchivePointTiming.readDroneClock(properties, coordinates.length())
        val points = (0 until coordinates.length()).mapNotNull { index ->
            val point = coordinates.optJSONArray(index) ?: return@mapNotNull null
            val lon = point.optString(0).toDoubleOrNull() ?: return@mapNotNull null
            val lat = point.optString(1).toDoubleOrNull() ?: return@mapNotNull null
            val time = point.optString(3).toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            val altitude = point.optString(2).toDoubleOrNull()?.takeIf { it.isFinite() && it > INVALID_ALTITUDE }
            ClueBindingPoint(time, received[index], lat, lon, altitude, "track", droneClock[index])
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

    /** Merge memory + per-flight index; drop tombstones; prefer migrated `<flightId>/…` paths. */
    fun mergeCluesForRewrite(
        memory: List<AndroidClueRecord>,
        flightFolder: List<AndroidClueRecord>,
        tombstoned: Set<String>,
    ): List<AndroidClueRecord> {
        val byId = LinkedHashMap<String, AndroidClueRecord>()
        for (record in memory) {
            if (record.id in tombstoned) continue
            byId[record.id] = record
        }
        for (record in flightFolder) {
            if (record.id in tombstoned) continue
            val prior = byId[record.id]
            if (prior == null) {
                byId[record.id] = record
            } else {
                val nested = record.imageFilename.contains('/')
                val priorNested = prior.imageFilename.contains('/')
                if (nested && !priorNested) byId[record.id] = record
            }
        }
        return byId.values.toList()
    }

    fun belongsToFlightFolder(record: AndroidClueRecord, archiveId: String): Boolean {
        if (record.binding?.flightId == archiveId) return true
        val folder = runCatching { AndroidClueFlightFolders.folderNameFor(archiveId) }.getOrNull() ?: return false
        return record.imageFilename.substringBefore('/') == folder
    }

    /**
     * Clues to embed for a KMZ rewrite: ownership (binding.flightId preferred) plus non-tombstoned
     * flight-folder recovery so a migrated index is never silently stripped.
     */
    fun cluesForRewrite(
        archiveId: String,
        archives: Map<String, FlightArchiveContents>,
        memoryClues: List<AndroidClueRecord>,
        flightFolderClues: List<AndroidClueRecord>,
        tombstonedIds: Set<String>,
    ): List<AndroidClueRecord> {
        val merged = mergeCluesForRewrite(memoryClues, flightFolderClues, tombstonedIds)
        val byId = LinkedHashMap<String, AndroidClueRecord>()
        for (record in ownedClues(archiveId, archives, merged)) byId[record.id] = record
        for (record in merged) {
            if (belongsToFlightFolder(record, archiveId)) byId[record.id] = record
        }
        return byId.values.sortedBy { it.ownershipTimeMs }
    }

    /** Loads `files/clues/<flightId>/clues.json` for [flightId]. */
    fun loadFlightFolderClues(cluesRoot: File, flightId: String): List<AndroidClueRecord> {
        val folder = runCatching { AndroidClueFlightFolders.folderNameFor(flightId) }.getOrNull() ?: return emptyList()
        val index = File(File(cluesRoot, folder), AndroidClueFlightFolders.INDEX_FILENAME)
        if (!index.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(index.readText())
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toAndroidClueRecord() }
        }.getOrDefault(emptyList())
    }

    /** Binds a clue saved without a usable binding to the archived flight's nearest stored point. */
    fun bindingFallback(record: AndroidClueRecord, contents: FlightArchiveContents, archiveFlightId: String? = null): AndroidClueRecord {
        val existing = record.binding
        if (existing?.nearest != null) return record
        val binding = ClueBinder.bind(
            aircraftId = existing?.aircraftId ?: contents.remoteId.ifEmpty { record.sourceDesignator },
            flightId = existing?.flightId ?: archiveFlightId,
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
