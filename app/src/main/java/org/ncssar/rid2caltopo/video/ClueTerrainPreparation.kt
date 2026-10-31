import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ncssar.rid2caltopo.video.mapcache.DemElevationSample

/** Retained for log/catch sites; Submit no longer throws this for missing DEM. */
internal class ClueTerrainUnavailable(message: String) : IllegalStateException(message)

internal const val FLAT_SURFACE_LOCATION_NOTE =
    "Location approximated from a flat-surface estimate (terrain data not yet loaded)."

/** Bind-time S1M try; Submit never waits longer than this for terrain. */
internal const val CLUE_BIND_TERRAIN_TIMEOUT_MS = 300L

/** Straight down. Matches the projection cutoff so a nadir sightline is not called an estimate. */
internal fun isNadirGimbal(gimbalAngleDeg: Double): Boolean =
    gimbalAngleDeg.isFinite() && gimbalAngleDeg <= -89.9

internal fun clueProjectionMethod(
    terrainProjectionApplied: Boolean,
    gimbalAngleDeg: Double,
): String {
    if (!terrainProjectionApplied) return "flat"
    return if (isNadirGimbal(gimbalAngleDeg)) "nadir-DEM" else "terrain-raymarch"
}

/** One line per clue: method, tile hit/miss, gimbal pitch/yaw, lat/lon delta from aircraft. */
internal fun clueProjectionMethodLogLine(
    clueLat: Double,
    clueLng: Double,
    droneLat: Double,
    droneLng: Double,
    gimbalPitchDeg: Double,
    gimbalYawDeg: Double?,
    terrainProjectionApplied: Boolean,
    demSource: String?,
    tileHit: Boolean? = null,
): String {
    val method = clueProjectionMethod(terrainProjectionApplied, gimbalPitchDeg)
    val hit = when {
        tileHit != null -> if (tileHit) "hit" else "miss"
        demSource?.startsWith("usgs-geotiff-local-") == true -> "hit"
        terrainProjectionApplied -> "hit"
        else -> "miss"
    }
    val yaw = gimbalYawDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "nil"
    return String.format(
        Locale.US,
        "Clue projection method=%s tile=%s gimbalPitch=%.1f gimbalYaw=%s deltaLat=%.6f deltaLon=%.6f demSource=%s",
        method,
        hit,
        gimbalPitchDeg,
        yaw,
        clueLat - droneLat,
        clueLng - droneLng,
        demSource ?: "none",
    )
}

/**
 * CalTopo description note for a published point that was not DEM-refined.
 * Nadir stays unmarked: that position is directly below the aircraft.
 */
internal fun appendFlatSurfaceLocationNote(
    description: String,
    terrainProjectionApplied: Boolean,
    gimbalAngleDeg: Double,
): String {
    if (terrainProjectionApplied || isNadirGimbal(gimbalAngleDeg)) return description
    if (description.contains(FLAT_SURFACE_LOCATION_NOTE)) return description
    val trimmed = description.trimEnd()
    return if (trimmed.isEmpty()) FLAT_SURFACE_LOCATION_NOTE else "$trimmed\n\n$FLAT_SURFACE_LOCATION_NOTE"
}

/** After a late DEM hit, drop the flat-surface note and refresh the DEM-used line. */
internal fun applyingTerrainProjectionDescription(
    description: String,
    demSource: String?,
    demResolutionMeters: Double?,
    demSampleStale: Boolean,
): String {
    var text = description
        .replace("\n\n$FLAT_SURFACE_LOCATION_NOTE", "")
        .replace(FLAT_SURFACE_LOCATION_NOTE, "")
    val demLine = formatClueDemSummary(
        terrainProjectionApplied = true,
        demSource = demSource,
        demResolutionMeters = demResolutionMeters,
        demSampleStale = demSampleStale,
    )
    text = text.replace(Regex("""(?m)^  DEM used:.*$"""), demLine)
    if (!text.contains(demLine)) {
        text = text.trimEnd() + "\n" + demLine
    }
    return text.trim()
}

internal fun cluePositionMoved(
    previousLat: Double,
    previousLng: Double,
    nextLat: Double,
    nextLng: Double,
): Boolean = abs(previousLat - nextLat) > 1e-7 || abs(previousLng - nextLng) > 1e-7

/** Inputs belong to the captured photograph, never the current live aircraft. */
internal suspend fun refineCapturedClueTerrain(
    clue: PendingClue,
    sampleElevation: suspend (Double, Double) -> DemElevationSample?,
    sampleLocalElevation: suspend (Double, Double) -> DemElevationSample? = sampleElevation,
): PendingClue = withContext(Dispatchers.Default) {
    val freshElevation: suspend (Double, Double) -> DemElevationSample? = { lat, lng ->
        sampleElevation(lat, lng)?.takeUnless { it.stale }
    }
    val freshLocal: suspend (Double, Double) -> DemElevationSample? = { lat, lng ->
        sampleLocalElevation(lat, lng)?.takeUnless { it.stale }
    }
    val capturedHeight = clue.demAltitudeReference?.let { reference ->
        freshElevation(clue.droneLat, clue.droneLng)
            ?.let { reference.heightMeters(clue.droneAlt, it.elevationMeters) }
    }
    val height = capturedHeight ?: clue.projectionHeightMeters
    val projection = projectClueLocationWithDemSamples(
        droneLat = clue.droneLat, droneLng = clue.droneLng, droneAlt = clue.droneAlt,
        headingDeg = clue.headingDeg, aglMeters = height, gimbalAngleDeg = clue.gimbalAngleDeg,
        terrainReferenceLatitude = clue.terrainReferenceLatitude,
        terrainReferenceLongitude = clue.terrainReferenceLongitude,
        seiRelativeUpMeters = clue.seiRelativeUpMeters,
        sampleElevationMeters = freshElevation,
        sampleLocalElevationMeters = freshLocal,
    )
    val knownGround = when {
        projection.terrainProjectionApplied && projection.alt.isFinite() -> projection.alt
        projection.demSource != null && projection.alt.isFinite() -> projection.alt
        else -> clue.knownDemGroundMeters
    }
    clue.copy(
        lat = projection.lat, lng = projection.lng, alt = projection.alt,
        aglMeters = height, projectionHeightMeters = height,
        aglSourceLabel = if (capturedHeight != null) "DEM at captured position" else clue.aglSourceLabel,
        projectionHeightSourceLabel = if (capturedHeight != null) "DEM at captured position" else clue.projectionHeightSourceLabel,
        terrainProjectionApplied = projection.terrainProjectionApplied,
        demSource = projection.demSource, demResolutionMeters = projection.demResolutionMeters,
        demSampleStale = projection.demSampleStale,
        knownDemGroundMeters = knownGround,
    ).also {
        if (!projection.terrainProjectionApplied) {
            val reason = projection.flatFallbackReason ?: "DEM terrain unavailable"
            org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug(
                "StreamsViewModel",
                "refineCapturedClueTerrain: flat fallback reason=$reason designator=${clue.designator}",
            )
        }
    }
}

/**
 * Optional bounded refinement for callers that still want a best-effort DEM pass.
 * Submit does not call this: a slow or missing GeoTIFF sample must not delay the marker POST.
 * Without DEM, returns the flat-ground projection with terrainProjectionApplied=false.
 */
internal suspend fun prepareClueTerrainForSave(
    clue: PendingClue,
    timeoutMillis: Long = 30_000L,
    sampleElevation: suspend (Double, Double) -> DemElevationSample?,
): PendingClue {
    if (clue.terrainProjectionApplied && !clue.demSampleStale) return clue
    return withTimeoutOrNull(timeoutMillis) { refineCapturedClueTerrain(clue, sampleElevation) }
        ?: clue.copy(terrainProjectionApplied = false, demSource = null, demSampleStale = false)
}
