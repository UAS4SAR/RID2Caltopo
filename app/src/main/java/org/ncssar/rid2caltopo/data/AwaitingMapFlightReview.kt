package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// Review data for the "Flights awaiting a map" panel. Apple mirrors these rules in
// R2CCore/AwaitingMapFlightReview.swift; keep wording and thresholds identical.

/** Latitude/longitude bounding box of a recorded track. */
data class AwaitingMapBoundingBox(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude

    /** Nearest point of the box (edge or corner) by clamping each coordinate. */
    fun nearestPoint(latitude: Double, longitude: Double): Pair<Double, Double> =
        latitude.coerceIn(minLatitude, maxLatitude) to longitude.coerceIn(minLongitude, maxLongitude)

    companion object {
        fun of(coordinates: List<Pair<Double, Double>>): AwaitingMapBoundingBox? {
            val valid = coordinates.filter { (lat, lon) ->
                lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
            }
            if (valid.isEmpty()) return null
            return AwaitingMapBoundingBox(valid.minOf { it.first }, valid.maxOf { it.first },
                valid.minOf { it.second }, valid.maxOf { it.second })
        }

        /** Journal points are [lon, lat, alt, timeMs]; values may be numbers or strings. */
        fun ofFlight(flight: JSONObject): AwaitingMapBoundingBox? {
            val points = flight.optJSONArray("points") ?: return null
            return of((0 until points.length()).mapNotNull { index ->
                points.optJSONArray(index)?.let { it.optDouble(1) to it.optDouble(0) }
            })
        }
    }
}

/** Where the operator is relative to a flight's bounding box. */
sealed class AwaitingMapFlightProximity {
    object Inside : AwaitingMapFlightProximity()
    data class Away(val distanceMeters: Double, val bearingDegrees: Int, val cardinal: String) : AwaitingMapFlightProximity()

    companion object {
        fun evaluate(box: AwaitingMapBoundingBox, latitude: Double, longitude: Double): AwaitingMapFlightProximity? {
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (box.contains(latitude, longitude)) return Inside
            val (nearLat, nearLon) = box.nearestPoint(latitude, longitude)
            val position = RidGeometry.relativePosition(latitude, longitude, nearLat, nearLon) ?: return null
            val degrees = position.bearingDegrees.roundToInt() % 360
            return Away(position.distanceMeters, degrees, RidGeometry.cardinalDirection16(degrees.toDouble()))
        }

        fun forFlight(flight: JSONObject, latitude: Double, longitude: Double): AwaitingMapFlightProximity? =
            AwaitingMapBoundingBox.ofFlight(flight)?.let { evaluate(it, latitude, longitude) }
    }
}

/** Operator-facing wording; Apple uses the same strings. */
object AwaitingMapFlightText {
    const val TITLE = "Flights awaiting a map"
    const val NO_MAP_BANNER = "Select an incident map to publish. Flights and clues remain saved locally."
    const val INSIDE_AREA = "You are within this flight's area"
    const val DISTANCE_UNAVAILABLE = "Distance unavailable"
    const val LOCATION_UNAVAILABLE = "Location unavailable · distances not shown"
    const val DISCARD_TITLE = "Discard flight log?"
    const val FOOTER_PUBLISH = "Publish needs a connected map. "
    const val FOOTER_DISCARD = " permanently deletes a flight's track and clue photos from this device. "
    const val FOOTER_DISMISS = " only hides this list; flights stay saved and can be reviewed again from Live View."

    /** Feet rounded to 10 below one mile; miles to one decimal from one mile up. */
    fun distance(meters: Double): String {
        val feet = meters / 0.3048
        if (feet < 5_280) return "${(feet / 10).roundToInt() * 10} ft"
        return String.format(Locale.US, "%.1f mi", feet / 5_280)
    }

    fun duration(milliseconds: Long): String {
        val total = (milliseconds / 1_000).coerceAtLeast(0)
        val hours = total / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.US, "Duration %dh %02dm %02ds", hours, minutes, seconds)
        else String.format(Locale.US, "Duration %dm %02ds", minutes, seconds)
    }

    fun cluePhotos(count: Int): String = "$count clue photo${if (count == 1) "" else "s"}"

    fun location(proximity: AwaitingMapFlightProximity?): String = when (proximity) {
        AwaitingMapFlightProximity.Inside -> INSIDE_AREA
        is AwaitingMapFlightProximity.Away ->
            "${distance(proximity.distanceMeters)} from current location at bearing ${proximity.bearingDegrees}° ${proximity.cardinal}"
        null -> DISTANCE_UNAVAILABLE
    }

    fun flightCountHeader(count: Int): String = "$count FLIGHT${if (count == 1) "" else "S"} ON THIS DEVICE"

    /** `fixTime` is already formatted for the device locale. */
    fun locationSummary(accuracyMeters: Double?, fixTime: String): String {
        val parts = mutableListOf("Distances from your current location")
        if (accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters >= 0) {
            parts += "GPS ±${(accuracyMeters / 0.3048).roundToInt()} ft"
        }
        parts += fixTime
        return parts.joinToString(" · ")
    }

    fun discardMessage(cluePhotoCount: Int): String =
        if (cluePhotoCount == 0) "This permanently deletes the track from this device."
        else "This permanently deletes the track and ${cluePhotos(cluePhotoCount)} from this device."
}

/**
 * The single rule that associates clue photos with a flight. Used when a publication choice binds
 * clues, for the panel's photo count, for Discard, and for the flight KMZ. Apple mirrors it in
 * AwaitingMapClueMatch (AwaitingMapFlightReview.swift).
 *
 * A clue bound to a flight id belongs to that flight. Otherwise every flight of the same drone whose
 * window [first - 30 s, last + 30 s] contains the capture is a candidate, and the one with the
 * waypoint nearest in time wins (ties go to the earlier flight).
 */
object AwaitingMapClueMatch {
    /** Clues captured shortly after the last waypoint still belong to the flight. */
    const val TRAILING_MILLISECONDS = 30_000L
    /** Clues captured shortly before the first recorded waypoint belong to the flight. */
    const val LEADING_MILLISECONDS = 30_000L

    /** Clue records carry the drone designator, which is the track label before any '_'. */
    fun designator(flight: JSONObject): String = designatorOfLabel(flight.optString("label"))

    fun designatorOfLabel(label: String): String = label.substringBefore('_')

    @JvmStatic
    fun matches(clueDesignator: String, createdAtMs: Long, flightDesignator: String, firstTime: Long, lastTime: Long): Boolean =
        clueDesignator.equals(flightDesignator, ignoreCase = true) &&
            createdAtMs - firstTime >= -LEADING_MILLISECONDS && createdAtMs - lastTime <= TRAILING_MILLISECONDS

    fun matches(clueDesignator: String, createdAtMs: Long, flight: JSONObject): Boolean =
        matches(clueDesignator, createdAtMs, designator(flight),
            AwaitingMapFlights.firstTime(flight), AwaitingMapFlights.lastTime(flight))

    /** A flight as seen by the ownership rule: a journal entry, a live track, or an archived track file. */
    data class Candidate(val id: String, val designator: String, val times: List<Long>) {
        val first: Long get() = times.minOrNull() ?: Long.MIN_VALUE
        val last: Long get() = times.maxOrNull() ?: Long.MIN_VALUE

        companion object {
            /** Journal points are [lon, lat, alt, timeMs]; values may be numbers or strings. */
            fun of(flight: JSONObject): Candidate {
                val points = flight.optJSONArray("points")
                val times = if (points == null) emptyList() else (0 until points.length()).mapNotNull { index ->
                    points.optJSONArray(index)?.optLong(3)?.takeIf { it > 0 }
                }
                return Candidate(flight.optString("id"), designator(flight), times)
            }
        }
    }

    /** Milliseconds from the capture to the candidate's nearest waypoint. */
    fun nearestPointDistance(createdAtMs: Long, candidate: Candidate): Long =
        candidate.times.minOfOrNull { kotlin.math.abs(it - createdAtMs) } ?: Long.MAX_VALUE

    /** Id of the candidate that owns a clue, or null when none does. */
    fun ownerId(clueDesignator: String, createdAtMs: Long, boundFlightId: String?, candidates: List<Candidate>): String? {
        val sameAircraft = candidates.filter { it.designator.equals(clueDesignator, ignoreCase = true) }
        if (boundFlightId != null && sameAircraft.any { it.id == boundFlightId }) return boundFlightId
        return sameAircraft
            .filter { it.times.isNotEmpty() && matches(clueDesignator, createdAtMs, it.designator, it.first, it.last) }
            .minWithOrNull(compareBy<Candidate>({ nearestPointDistance(createdAtMs, it) }, { it.first }))
            ?.id
    }

    /** Clues owned by [flight] when [otherFlights] are also known. */
    fun <C> ownedClues(
        clues: List<C>,
        flight: JSONObject,
        otherFlights: List<JSONObject>,
        designatorOf: (C) -> String,
        createdAtOf: (C) -> Long,
        boundFlightIdOf: (C) -> String? = { null },
    ): List<C> {
        val id = flight.optString("id")
        val candidates = listOf(Candidate.of(flight)) + otherFlights.filter { it.optString("id") != id }.map { Candidate.of(it) }
        return ownedClues(clues, id, candidates, designatorOf, createdAtOf, boundFlightIdOf)
    }

    fun <C> ownedClues(
        clues: List<C>,
        flightId: String,
        candidates: List<Candidate>,
        designatorOf: (C) -> String,
        createdAtOf: (C) -> Long,
        boundFlightIdOf: (C) -> String? = { null },
    ): List<C> = clues.filter { ownerId(designatorOf(it), createdAtOf(it), boundFlightIdOf(it), candidates) == flightId }
}

/** Result of deleting one archive file. */
enum class ArchiveFileDeletion { DELETED, NOT_FOUND, FAILED }

/** Abstracts the operator archive tree (SAF or the session's temporary folder) for Discard. */
fun interface AwaitingMapArchiveFiles {
    fun delete(dayDirectory: String, fileName: String): ArchiveFileDeletion
}

object AwaitingMapFlightArchive {
    /** Aircraft ID used in archive filenames: the remote ID, else the track label (WaypointTrack.archiveAircraftID). */
    fun aircraftId(flight: JSONObject): String = flight.optString("remote").ifBlank { flight.optString("label") }

    /** Exact filenames WaypointTrack writes for a flight: GeoJSON plus clue KMZ. */
    @JvmOverloads
    fun filenames(flight: JSONObject, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val id = aircraftId(flight)
        val start = AwaitingMapFlights.firstTime(flight)
        return listOf(OperatorArchiveFilename.track(id, start, zone), OperatorArchiveFilename.clueReport(id, start, zone))
    }

    /**
     * Day folders ("tracks-ddMMMyyyy", CaltopoClient.GetTodaysTrackDir) the archive could be in:
     * the start day, the end day, and the day after (archiving runs when the flight ends).
     */
    @JvmOverloads
    fun candidateDayDirectories(flight: JSONObject, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val format = SimpleDateFormat("ddMMMyyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }
        val last = AwaitingMapFlights.lastTime(flight)
        return listOf(AwaitingMapFlights.firstTime(flight), last, last + 86_400_000L)
            .map { "tracks-" + format.format(Date(it)) }
            .distinct()
    }
}

object AwaitingMapFlightDiscarder {
    data class Result(
        val entryRemoved: Boolean,
        val deletedClueIds: List<String>,
        val deletedArchiveFiles: List<String>,
        val failures: List<String>,
    ) {
        val succeeded: Boolean get() = entryRemoved && failures.isEmpty()

        fun logSummary(flight: JSONObject): String =
            "Discarded awaiting-map flight id=${flight.optString("id")} remoteId=${flight.optString("remote")} " +
                "label=${flight.optString("label")} start=${AwaitingMapFlights.firstTime(flight)} entryRemoved=$entryRemoved " +
                "clues=${deletedClueIds.size}[${deletedClueIds.joinToString(",")}] " +
                "archive=[${deletedArchiveFiles.joinToString(",")}]" +
                if (failures.isEmpty()) "" else " failures=[${failures.joinToString("; ")}]"
    }

    /**
     * Deletes the flight's clue photos and archive files first, then its journal entry, so a
     * failure leaves the entry in place for another attempt. Nothing outside the flight is touched.
     * [archive] is null when no archive folder is available on this device.
     */
    @JvmOverloads
    fun discard(
        flight: JSONObject,
        clueIds: List<String>,
        deleteClue: (String) -> Boolean,
        archive: AwaitingMapArchiveFiles?,
        removeEntry: (String) -> Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val deletedClues = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()
        val failures = mutableListOf<String>()
        clueIds.forEach { id ->
            if (runCatching { deleteClue(id) }.getOrDefault(false)) deletedClues += id else failures += "clue $id"
        }
        if (archive != null) {
            val names = AwaitingMapFlightArchive.filenames(flight, zone)
            for (day in AwaitingMapFlightArchive.candidateDayDirectories(flight, zone)) {
                for (name in names) {
                    when (runCatching { archive.delete(day, name) }.getOrDefault(ArchiveFileDeletion.FAILED)) {
                        ArchiveFileDeletion.DELETED -> deletedFiles += "$day/$name"
                        ArchiveFileDeletion.FAILED -> failures += "$day/$name"
                        ArchiveFileDeletion.NOT_FOUND -> Unit
                    }
                }
            }
        }
        if (failures.isNotEmpty()) return Result(false, deletedClues, deletedFiles, failures)
        val removed = runCatching { removeEntry(flight.optString("id")) }
        removed.exceptionOrNull()?.let { failures += "journal: ${it.message}" }
        if (removed.getOrNull() == false) failures += "journal entry not found"
        return Result(removed.getOrNull() == true, deletedClues, deletedFiles, failures)
    }
}
