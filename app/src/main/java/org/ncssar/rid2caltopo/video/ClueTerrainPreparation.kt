import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ncssar.rid2caltopo.video.mapcache.DemElevationSample

internal class ClueTerrainUnavailable(message: String) : IllegalStateException(message)

internal const val FLAT_SURFACE_LOCATION_NOTE =
    "Location approximated from a flat-surface estimate (terrain data not yet loaded)."

/** Straight down. Matches the projection cutoff so a nadir sightline is not called an estimate. */
internal fun isNadirGimbal(gimbalAngleDeg: Double): Boolean =
    gimbalAngleDeg.isFinite() && gimbalAngleDeg <= -89.9

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

/** Inputs belong to the captured photograph, never the current live aircraft. */
internal suspend fun refineCapturedClueTerrain(
    clue: PendingClue,
    sampleElevation: suspend (Double, Double) -> DemElevationSample?,
): PendingClue = withContext(Dispatchers.Default) {
    val freshElevation: suspend (Double, Double) -> DemElevationSample? = { lat, lng ->
        sampleElevation(lat, lng)?.takeUnless { it.stale }
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
    )
    clue.copy(
        lat = projection.lat, lng = projection.lng, alt = projection.alt,
        aglMeters = height, projectionHeightMeters = height,
        aglSourceLabel = if (capturedHeight != null) "DEM at captured position" else clue.aglSourceLabel,
        projectionHeightSourceLabel = if (capturedHeight != null) "DEM at captured position" else clue.projectionHeightSourceLabel,
        terrainProjectionApplied = projection.terrainProjectionApplied,
        demSource = projection.demSource, demResolutionMeters = projection.demResolutionMeters,
        demSampleStale = projection.demSampleStale,
    )
}

/**
 * Optional refinement. Submit does not call this: a slow GeoTIFF sample must not
 * delay the marker POST. The form preview may still refine in the background.
 */
internal suspend fun prepareClueTerrainForSave(
    clue: PendingClue,
    timeoutMillis: Long = 30_000L,
    sampleElevation: suspend (Double, Double) -> DemElevationSample?,
): PendingClue {
    val prepared = if (clue.terrainProjectionApplied && !clue.demSampleStale) clue else {
        withTimeoutOrNull(timeoutMillis) { refineCapturedClueTerrain(clue, sampleElevation) }
            ?: throw ClueTerrainUnavailable("Terrain lookup timed out. Your clue is still open; try saving again.")
    }
    if (!prepared.terrainProjectionApplied || prepared.demSampleStale) {
        throw ClueTerrainUnavailable("Terrain for this camera view is unavailable. Download the terrain for this area and try saving again.")
    }
    return prepared
}
