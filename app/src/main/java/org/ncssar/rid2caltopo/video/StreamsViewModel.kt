import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.view.Surface
import org.osmdroid.api.IGeoPoint
import org.ncssar.rid2caltopo.video.MapViewportBounds
import org.ncssar.rid2caltopo.video.AndroidClueRecord
import org.ncssar.rid2caltopo.video.AndroidClueStore
import org.ncssar.rid2caltopo.video.folderHiddenAfterDefault
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import org.ncssar.rid2caltopo.video.StreamRenderRouter
import org.ncssar.rid2caltopo.video.StreamFlightActivityRegistry
import org.ncssar.rid2caltopo.video.SerializedTaskQueue
import org.ncssar.rid2caltopo.video.session.StreamSessionService
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoMap.MapStatusListener.mapStatus
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DesignatorState
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.ui.ComplianceAlertCandidate
import org.ncssar.rid2caltopo.ui.ComplianceAlertCenter
import org.ncssar.rid2caltopo.video.anomaly.AnomalyAlgorithm
import org.ncssar.rid2caltopo.video.anomaly.AnomalyConfig
import org.ncssar.rid2caltopo.video.anomaly.AnomalyPrefs
import org.ncssar.rid2caltopo.video.anomaly.AnomalyDetectorMode
import org.ncssar.rid2caltopo.video.anomaly.AnomalyStrideMode
import org.ncssar.rid2caltopo.video.anomaly.AppearanceAnomalyMode
import org.ncssar.rid2caltopo.video.anomaly.AppearanceAnomalySelection
import org.ncssar.rid2caltopo.video.anomaly.MotionRegistrationMode
import org.ncssar.rid2caltopo.video.anomaly.MovementEstimatorMode
import org.ncssar.rid2caltopo.video.anomaly.PersonRelevanceMode
import org.ncssar.rid2caltopo.video.anomaly.TargetColorFamily
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegProbeService
import org.ncssar.rid2caltopo.video.ffmpeg.DjiCameraOrientation
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetrySample
import org.ncssar.rid2caltopo.video.ffmpeg.shouldBlockRidClueFallback
import org.ncssar.rid2caltopo.video.ffmpeg.StreamRuntimeSnapshot
import org.ncssar.rid2caltopo.video.ffmpeg.StreamTelemetrySnapshot
import org.ncssar.rid2caltopo.video.CoordinateDisplayFormat
import org.ncssar.rid2caltopo.video.MapArtifactRenderCache
import org.ncssar.rid2caltopo.video.CoordinateFormatter
import org.ncssar.rid2caltopo.video.STREAM_COORDINATE_DISPLAY_FORMAT_KEY
import org.ncssar.rid2caltopo.video.restoredCoordinateDisplayFormat
import org.ncssar.rid2caltopo.video.PlaybackIndicatorState
import org.ncssar.rid2caltopo.video.LocalPlaybackAnnotationType
import org.ncssar.rid2caltopo.video.LocalPlaybackAnnotationVerdict
import org.ncssar.rid2caltopo.video.LocalPlaybackFrameReview
import org.ncssar.rid2caltopo.video.LocalPlaybackPointAnnotation
import org.ncssar.rid2caltopo.video.LocalPlaybackReviewKind
import org.ncssar.rid2caltopo.video.LocalPlaybackReviewFile
import org.ncssar.rid2caltopo.video.LocalPlaybackScenario
import org.ncssar.rid2caltopo.video.PendingLocalPlaybackReviewExport
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamTelemetryBindingStatus
import org.ncssar.rid2caltopo.video.StreamTelemetryState
import org.ncssar.rid2caltopo.video.StreamTelemetryPairingWarning
import org.ncssar.rid2caltopo.video.StreamTelemetryPairingControlDecision
import org.ncssar.rid2caltopo.video.StreamAdmissionGuardResult
import org.ncssar.rid2caltopo.video.StreamAdmissionState
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.ncssar.rid2caltopo.video.StreamState
import org.ncssar.rid2caltopo.video.automaticStreamTelemetryPairingTarget
import org.ncssar.rid2caltopo.video.bindStreamToRemoteId
import org.ncssar.rid2caltopo.video.buildLocalPlaybackFrameAnnotationSummary
import org.ncssar.rid2caltopo.video.clearStreamTelemetryBinding
import org.ncssar.rid2caltopo.video.closestStreamTelemetryRemoteId
import org.ncssar.rid2caltopo.video.configuredStreamTelemetryBindingMaps
import org.ncssar.rid2caltopo.video.localPlaybackReviewFromJson
import org.ncssar.rid2caltopo.video.resolveStreamTelemetryBinding
import org.ncssar.rid2caltopo.video.streamTelemetryPairingControlAction
import org.ncssar.rid2caltopo.video.streamTelemetryPairingWarning
import org.ncssar.rid2caltopo.video.toJson
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.DemElevationSample

data class PendingClue(
    val droneSpec: CtDroneSpec,
    val designator: String,
    val droneLat: Double,
    val droneLng: Double,
    val droneAlt: Double,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val headingDeg: Double?,
    val headingSourceLabel: String?,
    val aglMeters: Double?,
    val atoMeters: Double?,
    val projectionHeightMeters: Double? = null,
    val projectionHeightSourceLabel: String? = null,
    val gimbalAngleDeg: Double,
    val timestamp: Long,
    val bitmap: Bitmap?,
    val preview: Bitmap?,
    val title: String,
    val description: String,
    val streamTelemetrySummary: String? = null,
    val aircraftPositionSourceLabel: String = "RID",
    val aglSourceLabel: String? = null,
    val terrainProjectionApplied: Boolean = false,
    val demSource: String? = null,
    val demResolutionMeters: Double? = null,
    val demSampleStale: Boolean = false,
)

internal data class ClueProjectionHeightSelection(
    val meters: Double,
    val sourceLabel: String,
)

internal fun selectClueProjectionHeight(
    freshAglMeters: Double?,
    atoMeters: Double?,
    validatedDjiRelativeUpMeters: Double? = null,
): ClueProjectionHeightSelection? {
    fun validHeight(value: Double?): Double? = value?.takeIf {
        it.isFinite() && it > 0.0 && it <= 10_000.0
    }
    validHeight(freshAglMeters)?.let {
        return ClueProjectionHeightSelection(it, "fresh AGL")
    }
    validHeight(atoMeters)?.let {
        return ClueProjectionHeightSelection(it, "ATO flat-ground fallback")
    }
    validHeight(validatedDjiRelativeUpMeters)?.let {
        return ClueProjectionHeightSelection(it, "validated DJI relative altitude flat-ground fallback")
    }
    return null
}

internal data class CenterpointElevationSample(
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Int,
    val demResolutionMeters: Int?,
)

private data class CenterpointElevationInputKey(
    val latitudeE5: Long,
    val longitudeE5: Long,
    val altitudeHalfMeters: Long,
    val aglHalfMeters: Long,
    val bearingFifths: Long,
    val tiltFifths: Long,
)

data class LocalMapMarker(
    val id: String,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val title: String,
    val description: String,
    val createdAtMs: Long,
    val sourceDesignator: String
)

data class ClueSnapshotRef(
    val title: String,
    val thumbnail: Bitmap?,
    val fullImage: Bitmap?,
    val fullImagePath: String? = null,
)

private fun AndroidClueRecord.toLocalMapMarker(): LocalMapMarker = LocalMapMarker(
    id = id,
    lat = lat,
    lng = lng,
    alt = alt,
    title = title,
    description = description,
    createdAtMs = createdAtMs,
    sourceDesignator = sourceDesignator,
)

fun removeLocalMapMarkerById(markers: MutableList<LocalMapMarker>, markerId: String): Boolean {
    val index = markers.indexOfFirst { it.id == markerId }
    if (index < 0) return false
    markers.removeAt(index)
    return true
}

fun localMapMarkerForArtifact(
    markers: List<LocalMapMarker>,
    artifactTitle: String,
    artifactLat: Double,
    artifactLng: Double,
    maxDistanceMeters: Double = 10.0,
): LocalMapMarker? {
    val normalizedTitle = artifactTitle.trim()
    if (normalizedTitle.isEmpty() || !artifactLat.isFinite() || !artifactLng.isFinite()) return null
    return markers
        .asSequence()
        .filter { it.title.trim().equals(normalizedTitle, ignoreCase = true) }
        .map { marker ->
            val meanLatitudeRadians = Math.toRadians((marker.lat + artifactLat) / 2.0)
            val northMeters = (marker.lat - artifactLat) * 111_320.0
            val eastMeters = (marker.lng - artifactLng) * 111_320.0 * cos(meanLatitudeRadians)
            marker to sqrt(northMeters * northMeters + eastMeters * eastMeters)
        }
        .filter { (_, distanceMeters) -> distanceMeters <= maxDistanceMeters }
        .minByOrNull { (_, distanceMeters) -> distanceMeters }
        ?.first
}

fun registerClueSnapshotByTitle(
    snapshots: MutableMap<String, ClueSnapshotRef>,
    title: String,
    thumbnail: Bitmap?,
    fullImage: Bitmap?,
    fullImagePath: String? = null,
): ClueSnapshotRef? {
    val key = title.trim()
    if (key.isBlank()) return null
    val snapshot = ClueSnapshotRef(
        title = key,
        thumbnail = thumbnail,
        fullImage = fullImage,
        fullImagePath = fullImagePath,
    )
    snapshots[key] = snapshot
    return snapshot
}

fun clueSnapshotForTitle(
    snapshots: Map<String, ClueSnapshotRef>,
    title: String
): ClueSnapshotRef? = snapshots[title.trim()]

data class MapViewportState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val bounds: MapViewportBounds? = null
)

private fun isUsablePersistedMapViewportState(latitude: Double, longitude: Double, zoom: Double): Boolean {
    if (!latitude.isFinite() || !longitude.isFinite() || !zoom.isFinite()) return false
    if (latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return false
    return !(abs(latitude) < 0.000001 && abs(longitude) < 0.000001)
}

enum class StreamsLayoutMode {
    Both,
    Streams,
    Map
}

data class StreamPipUiState(
    val enabled: Boolean,
    val insetFraction: Float,
    val editorMode: Boolean = false
) {
    fun withEnabled(nextEnabled: Boolean): StreamPipUiState =
        copy(enabled = nextEnabled, editorMode = if (nextEnabled) editorMode else false)

    fun withEditorLongPress(): StreamPipUiState =
        copy(editorMode = enabled && !editorMode)

    companion object {
        fun fromPersisted(enabled: Boolean, insetFraction: Float): StreamPipUiState =
            StreamPipUiState(
                enabled = enabled,
                insetFraction = clampStreamPipInsetFraction(insetFraction),
                editorMode = false
            )
    }
}

internal const val STREAM_PIP_MIN_INSET_FRACTION = 0.22f
internal const val STREAM_PIP_DEFAULT_INSET_FRACTION = 0.33f
internal const val STREAM_PIP_MAX_INSET_FRACTION = 0.55f

internal fun clampStreamPipInsetFraction(value: Float): Float {
    if (!value.isFinite()) return STREAM_PIP_DEFAULT_INSET_FRACTION
    return value.coerceIn(STREAM_PIP_MIN_INSET_FRACTION, STREAM_PIP_MAX_INSET_FRACTION)
}

data class ProximityMapFocusTarget(
    val requestId: Long,
    val firstLat: Double,
    val firstLng: Double,
    val secondLat: Double,
    val secondLng: Double
)

private const val COMPLIANCE_ALERT_AGL_LIMIT_FT = 200.0
private const val LOCAL_PLAYBACK_STAGE_BUFFER_BYTES = 1024 * 1024

data class OverLimitDroneUiState(
    val remoteId: String,
    val mappedId: String,
    val aglFt: Double,
    val thresholdFt: Double,
    val muted: Boolean,
    val staleDem: Boolean,
    val usingDemAgl: Boolean,
    val atoFt: Double?,
)

private data class ProcessLoadSnapshot(
    val observedAtMs: Long,
    val sampleWindowMs: Long,
    val processCpuFraction: Double,
    val mainThreadCpuFraction: Double,
    val javaHeapUsedMb: Long,
    val javaHeapMaxMb: Long,
    val nativeHeapUsedMb: Long,
    val mediaMtxPid: Int,
    val mediaMtxCpuFraction: Double,
    val liveStreamCount: Int,
    val ffmpegStreamCount: Int,
    val anomalyEnabledCount: Int,
    val thermalStatusLabel: String,
    val anomalyHeadroomLabel: String,
)

private data class RealtimeStatus(
    val factor: Double,
    val descriptor: String,
)

private fun realtimeStatus(factor: Double?): RealtimeStatus? {
    val value = factor?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val descriptor = when {
        value > 1.02 -> "faster than realtime"
        value < 0.98 -> "slower than realtime"
        else -> "equal to realtime"
    }
    return RealtimeStatus(factor = value, descriptor = descriptor)
}

internal fun chooseResyncSnapshot(
    lastSyncedStreams: Map<String, StreamInfo>,
    latestFlowValue: Map<String, StreamInfo>,
): Map<String, StreamInfo> {
    return if (lastSyncedStreams.isNotEmpty() && latestFlowValue.isEmpty()) {
        lastSyncedStreams
    } else {
        latestFlowValue
    }
}

internal fun focusAfterStreamSync(
    currentFocus: String?,
    liveDesignators: Set<String>,
    newlyVisibleLiveDesignators: Set<String>,
): String? {
    if (currentFocus == null) return null
    if (currentFocus !in liveDesignators) return null
    if (newlyVisibleLiveDesignators.isNotEmpty()) return null
    return currentFocus
}

internal data class CapturedVideoPlaybackPlan(
    val designator: String,
    val localPlaybackDesignatorsToClose: Set<String>,
)

internal fun capturedVideoPlaybackPlan(
    normalizedName: String,
    activeStreams: Map<String, StreamInfo>,
    localPlaybackEntries: Map<String, StreamInfo>,
): CapturedVideoPlaybackPlan {
    val localPlaybackDesignatorsToClose = localPlaybackEntries.keys.toSet()
    val activeNames = activeStreams.keys
    var designator = normalizedName
    var suffix = 2
    while (designator in activeNames) {
        designator = "$normalizedName ($suffix)"
        suffix += 1
    }
    return CapturedVideoPlaybackPlan(
        designator = designator,
        localPlaybackDesignatorsToClose = localPlaybackDesignatorsToClose,
    )
}

private val clueTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val DEFAULT_CLUE_GIMBAL_ANGLE_DEG = -90.0
private const val METERS_TO_FEET = 3.28084
private const val RID_INVALID_ALTITUDE_METERS = -1000.0

internal data class ClueProjection(
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val terrainProjectionApplied: Boolean = false,
    val demSource: String? = null,
    val demResolutionMeters: Double? = null,
    val demSampleStale: Boolean = false,
)

internal fun buildClueDescriptionTemplate(
    timestampMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val localTime = Instant.ofEpochMilli(timestampMs)
        .atZone(zoneId)
        .format(clueTimeFormatter)
    return "time: $localTime\nfound by: \nreported to IC: yes|no\n"
}

internal fun buildClueCaptureSummary(
    clue: PendingClue,
    coordinateDisplayFormat: CoordinateDisplayFormat,
): String {
    val lines = mutableListOf<String>()
    lines += "Projected clue location:"
    val primaryPosition = CoordinateFormatter.format(
        clue.lat,
        clue.lng,
        coordinateDisplayFormat,
    ).removePrefix("loc:")
    lines += String.format(
        Locale.US,
        "  Position (%s): %s alt %.0f'",
        coordinateDisplayFormat.label,
        primaryPosition,
        clue.alt * METERS_TO_FEET,
    )
    if (coordinateDisplayFormat != CoordinateDisplayFormat.DECIMAL) {
        lines += String.format(Locale.US, "  Decimal: %.6f, %.6f", clue.lat, clue.lng)
    }
    lines += formatClueHeading(clue.headingDeg)?.let {
        "  Camera Azimuth: $it\u00b0"
    } ?: "  Camera Azimuth: N/A"
    lines += "  Heading source: ${clue.headingSourceLabel ?: "N/A"}"
    lines += String.format(Locale.US, "  Gimbal angle at capture: %.1f\u00b0", clue.gimbalAngleDeg)
    lines += clue.aglMeters?.let {
        String.format(Locale.US, "  AGL: %.0f'", it * METERS_TO_FEET)
    } ?: "  AGL: N/A"
    clue.aglSourceLabel?.let { lines += "  AGL source: $it" }
    clue.projectionHeightMeters?.let {
        lines += String.format(
            Locale.US,
            "  Projection height: %.0f' (%s)",
            it * METERS_TO_FEET,
            clue.projectionHeightSourceLabel ?: "unspecified",
        )
    }
    lines += "  Aircraft position source: ${clue.aircraftPositionSourceLabel}"
    lines += formatClueDemSummary(
        terrainProjectionApplied = clue.terrainProjectionApplied,
        demSource = clue.demSource,
        demResolutionMeters = clue.demResolutionMeters,
        demSampleStale = clue.demSampleStale,
    )
    lines += clue.atoMeters?.let {
        String.format(Locale.US, "  ATO: %.0f'", it * METERS_TO_FEET)
    } ?: "  ATO: N/A"
    lines += String.format(Locale.US, "  Distance to clue: %.0f'", clueDistanceMeters(clue) * METERS_TO_FEET)
    return lines.joinToString("\n")
}

internal fun formatClueDemSummary(
    terrainProjectionApplied: Boolean,
    demSource: String?,
    demResolutionMeters: Double?,
    demSampleStale: Boolean,
): String {
    if (!terrainProjectionApplied) return "  DEM used: none (flat-ground estimate)"
    val sourceLabel = when {
        demSource?.startsWith("usgs-geotiff-local-") == true -> "local USGS GeoTIFF"
        demSource == "usgs-epqs" -> "USGS elevation service"
        demSource.isNullOrBlank() -> "USGS elevation data"
        else -> demSource
    }
    val resolutionLabel = demResolutionMeters
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { " (${String.format(Locale.US, "%.0f", it)} m grid)" }
        ?: " (resolution not reported)"
    val staleLabel = if (demSampleStale) ", cached" else ""
    return "  DEM used: $sourceLabel$resolutionLabel$staleLabel"
}

private fun clueDistanceMeters(clue: PendingClue): Double {
    val latitude1 = Math.toRadians(clue.droneLat)
    val latitude2 = Math.toRadians(clue.lat)
    val latitudeDelta = latitude2 - latitude1
    val longitudeDelta = Math.toRadians(clue.lng - clue.droneLng)
    val haversine = sin(latitudeDelta / 2).let { it * it } +
        cos(latitude1) * cos(latitude2) *
        sin(longitudeDelta / 2).let { it * it }
    val angularDistance = 2 * atan2(sqrt(haversine), sqrt(maxOf(0.0, 1 - haversine)))
    return 6_371_000.0 * angularDistance
}

internal data class HeadingSelection(
    val headingDeg: Double?,
    val sourceLabel: String?,
)

internal fun normalizeClueHeading(value: Double?): Double? {
    val finite = value?.takeIf { it.isFinite() } ?: return null
    return ((finite % 360.0) + 360.0) % 360.0
}

internal fun formatClueHeading(value: Double?): String? {
    val normalized = normalizeClueHeading(value) ?: return null
    val roundedTenths = kotlin.math.round(normalized * 10.0).toInt() % 3_600
    return String.format(Locale.US, "%.1f", roundedTenths / 10.0)
}

internal fun videoMslAglMeters(mslAltitudeMeters: Double?, groundElevationMeters: Double?): Double? {
    val msl = mslAltitudeMeters?.takeIf { it.isFinite() } ?: return null
    val ground = groundElevationMeters?.takeIf { it.isFinite() } ?: return null
    return (msl - ground).takeIf { it.isFinite() && it in 0.0..10_000.0 }
}

internal fun selectClueHeading(
    djiCameraAzimuthDeg: Double? = null,
    djiVideoCourseDeg: Double?,
    telemetry: StreamTelemetrySnapshot?,
    derivedHeadingDeg: Double?,
    ridTrackDeg: Double?,
): HeadingSelection {
    val djiCameraAzimuth = normalizeClueHeading(djiCameraAzimuthDeg)
    if (djiCameraAzimuth != null) {
        return HeadingSelection(djiCameraAzimuth, "DJI camera azimuth")
    }

    val djiVideoCourse = normalizeClueHeading(djiVideoCourseDeg)
    if (djiVideoCourse != null) {
        return HeadingSelection(djiVideoCourse, "DJI video-derived course")
    }

    val derivedHeading = normalizeClueHeading(derivedHeadingDeg)
    if (derivedHeading != null) {
        return HeadingSelection(derivedHeading, "Derived drone heading")
    }

    val cameraYaw = normalizeClueHeading(
        telemetry?.takeUnless { it.sourceTag == "dji-sei-245" }?.cameraYawDeg
    )
    if (cameraYaw != null) return HeadingSelection(cameraYaw, "Camera yaw")

    val streamHeading = normalizeClueHeading(telemetry?.headingDeg)
    if (streamHeading != null) return HeadingSelection(streamHeading, "Stream heading")

    val ridTrack = normalizeClueHeading(ridTrackDeg)
    if (ridTrack != null) return HeadingSelection(ridTrack, "RID aircraft track")

    return HeadingSelection(null, null)
}

internal fun projectClueLocation(
    droneLat: Double,
    droneLng: Double,
    droneAlt: Double,
    headingDeg: Double?,
    aglMeters: Double?,
    gimbalAngleDeg: Double,
): ClueProjection {
    val validHeading = headingDeg?.takeIf { it.isFinite() }
    val validAgl = aglMeters?.takeIf { it.isFinite() && it >= 0.0 }
    val projectedAlt = if (droneAlt.isFinite() && droneAlt > RID_INVALID_ALTITUDE_METERS && validAgl != null) {
        droneAlt - validAgl
    } else {
        droneAlt
    }
    if (validHeading == null || validAgl == null) {
        return ClueProjection(droneLat, droneLng, projectedAlt)
    }

    val clampedAngle = gimbalAngleDeg
        .takeIf { it.isFinite() }
        ?.coerceIn(-90.0, 90.0)
        ?: DEFAULT_CLUE_GIMBAL_ANGLE_DEG
    if (clampedAngle >= -0.1) {
        return ClueProjection(droneLat, droneLng, projectedAlt)
    }
    val tiltFromHorizonDeg = abs(clampedAngle).coerceIn(0.1, 90.0)
    val horizontalDistanceM = if (tiltFromHorizonDeg >= 89.9) {
        0.0
    } else {
        validAgl / tan(Math.toRadians(tiltFromHorizonDeg))
    }
    if (!horizontalDistanceM.isFinite() || horizontalDistanceM <= 0.0) {
        return ClueProjection(droneLat, droneLng, projectedAlt)
    }

    val destination = destinationPoint(
        startLat = droneLat,
        startLng = droneLng,
        bearingDeg = validHeading,
        distanceM = horizontalDistanceM,
    )
    return ClueProjection(destination.first, destination.second, projectedAlt)
}

internal suspend fun projectClueLocationWithDem(
    demElevationService: DemElevationService,
    droneLat: Double,
    droneLng: Double,
    droneAlt: Double,
    headingDeg: Double?,
    aglMeters: Double?,
    gimbalAngleDeg: Double,
): ClueProjection {
    return projectClueLocationWithDemSamples(
        droneLat = droneLat,
        droneLng = droneLng,
        droneAlt = droneAlt,
        headingDeg = headingDeg,
        aglMeters = aglMeters,
        gimbalAngleDeg = gimbalAngleDeg,
        sampleElevationMeters = { lat, lng ->
            demElevationService.sampleElevationMeters(lat, lng)
        },
    )
}

internal suspend fun projectClueLocationWithDemSamples(
    droneLat: Double,
    droneLng: Double,
    droneAlt: Double,
    headingDeg: Double?,
    aglMeters: Double?,
    gimbalAngleDeg: Double,
    sampleElevationMeters: suspend (Double, Double) -> DemElevationSample?,
): ClueProjection {
    val flatProjection = projectClueLocation(
        droneLat = droneLat,
        droneLng = droneLng,
        droneAlt = droneAlt,
        headingDeg = headingDeg,
        aglMeters = aglMeters,
        gimbalAngleDeg = gimbalAngleDeg,
    )
    val validHeading = headingDeg?.takeIf { it.isFinite() } ?: return flatProjection
    val validAgl = aglMeters?.takeIf { it.isFinite() && it > 0.0 } ?: return flatProjection
    val clampedAngle = gimbalAngleDeg
        .takeIf { it.isFinite() }
        ?.coerceIn(-90.0, 90.0)
        ?: DEFAULT_CLUE_GIMBAL_ANGLE_DEG
    if (clampedAngle >= -0.1) return flatProjection
    val tiltFromHorizonDeg = abs(clampedAngle).coerceIn(0.1, 90.0)
    if (tiltFromHorizonDeg >= 89.9) return flatProjection

    val slopeDown = tan(Math.toRadians(tiltFromHorizonDeg))
    if (!slopeDown.isFinite() || slopeDown <= 0.0) return flatProjection

    val flatDistanceM = validAgl / slopeDown
    if (!flatDistanceM.isFinite() || flatDistanceM <= 0.0) return flatProjection

    val flatGroundM = droneAlt - validAgl
    val droneDemSample = sampleElevationMeters(droneLat, droneLng)
    val droneDemRaw = droneDemSample?.elevationMeters?.takeIf { it.isFinite() }
    val demScaleToMeters = inferDemScaleToMeters(
        droneAltMeters = droneAlt,
        knownGroundMeters = flatGroundM,
        droneDemRaw = droneDemRaw,
    )

    // A shallow sightline over falling terrain may stay above the ground for kilometres.
    // Search far enough to reach the visible terrain instead of sizing the DEM walk from
    // the flat-ground answer, which is shortest exactly when the terrain drops away.
    val usingLocalDem = droneDemSample?.source?.startsWith("usgs-geotiff-local-") == true
    val maxDistanceM = if (usingLocalDem) 10_000.0 else 2_500.0
    val stepM = if (usingLocalDem) 50.0 else 100.0

    var previousDistanceM = 0.0
    var previousPoint = Pair(droneLat, droneLng)
    var previousGroundM = flatGroundM

    var distanceM = stepM
    while (distanceM <= maxDistanceM + 0.001) {
        val candidate = destinationPoint(
            startLat = droneLat,
            startLng = droneLng,
            bearingDeg = validHeading,
            distanceM = distanceM,
        )
        val sampledCandidate = sampleElevationMeters(candidate.first, candidate.second)
        if (usingLocalDem && sampledCandidate?.source?.startsWith("usgs-geotiff-local-") != true) {
            break
        }
        if (sampledCandidate == null) {
            distanceM += stepM
            continue
        }
        val candidateDemSample = sampledCandidate
        val candidateDemRaw = candidateDemSample.elevationMeters
        val sampledGroundM = normalizeDemGroundMeters(
            candidateDemRaw = candidateDemRaw,
            droneDemRaw = droneDemRaw,
            flatGroundM = flatGroundM,
            demScaleToMeters = demScaleToMeters,
        )
        if (sampledGroundM == null) {
            distanceM += stepM
            continue
        }
        val groundM = checkNotNull(sampledGroundM)
        val rayAltitudeM = droneAlt - (slopeDown * distanceM)
        if (rayAltitudeM <= groundM) {
            var lowDistanceM = previousDistanceM
            var lowPoint = previousPoint
            var lowGroundM = previousGroundM
            var highDistanceM = distanceM
            var highPoint = candidate
            var highGroundM = groundM

            repeat(6) {
                val midDistanceM = (lowDistanceM + highDistanceM) / 2.0
                val midPoint = destinationPoint(
                    startLat = droneLat,
                    startLng = droneLng,
                    bearingDeg = validHeading,
                    distanceM = midDistanceM,
                )
                val midDemSample = sampleElevationMeters(midPoint.first, midPoint.second)
                val midGroundM = normalizeDemGroundMeters(
                    candidateDemRaw = midDemSample?.elevationMeters,
                    droneDemRaw = droneDemRaw,
                    flatGroundM = flatGroundM,
                    demScaleToMeters = demScaleToMeters,
                ) ?: ((lowGroundM + highGroundM) / 2.0)
                val midRayAltitudeM = droneAlt - (slopeDown * midDistanceM)
                if (midRayAltitudeM <= midGroundM) {
                    highDistanceM = midDistanceM
                    highPoint = midPoint
                    highGroundM = midGroundM
                } else {
                    lowDistanceM = midDistanceM
                    lowPoint = midPoint
                    lowGroundM = midGroundM
                }
            }

            return ClueProjection(
                lat = highPoint.first,
                lng = highPoint.second,
                alt = highGroundM,
                terrainProjectionApplied = true,
                demSource = candidateDemSample.source,
                demResolutionMeters = candidateDemSample.horizontalResolutionMeters,
                demSampleStale = candidateDemSample.stale,
            )
        }
        previousDistanceM = distanceM
        previousPoint = candidate
        previousGroundM = groundM
        distanceM += stepM
    }

    return flatProjection
}

internal fun inferDemScaleToMeters(
    droneAltMeters: Double,
    knownGroundMeters: Double,
    droneDemRaw: Double?,
): Double {
    val raw = droneDemRaw?.takeIf { it.isFinite() } ?: return 1.0
    val directGroundErrorM = abs(raw - knownGroundMeters)
    val feetGroundErrorM = abs((raw * 0.3048) - knownGroundMeters)
    val directAltitudeErrorM = abs(raw - droneAltMeters)
    val feetAltitudeErrorM = abs((raw * 0.3048) - droneAltMeters)

    return if (feetGroundErrorM + feetAltitudeErrorM < directGroundErrorM + directAltitudeErrorM) {
        0.3048
    } else {
        1.0
    }
}

internal fun normalizeDemGroundMeters(
    candidateDemRaw: Double?,
    droneDemRaw: Double?,
    flatGroundM: Double,
    demScaleToMeters: Double,
): Double? {
    val candidate = candidateDemRaw?.takeIf { it.isFinite() } ?: return null
    val droneRaw = droneDemRaw?.takeIf { it.isFinite() } ?: return candidate * demScaleToMeters
    return flatGroundM + ((candidate - droneRaw) * demScaleToMeters)
}

private fun destinationPoint(
    startLat: Double,
    startLng: Double,
    bearingDeg: Double,
    distanceM: Double,
): Pair<Double, Double> {
    val earthRadiusM = 6_371_000.0
    val angularDistance = distanceM / earthRadiusM
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(startLat)
    val lon1 = Math.toRadians(startLng)

    val lat2 = asin(
        sin(lat1) * cos(angularDistance) +
            cos(lat1) * sin(angularDistance) * cos(bearing)
    )
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )
    return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

@Stable
class DroneSpecState(
    val source: CtDroneSpec
) {
    val remoteId = source.remoteId
    var flightStartMsec by mutableStateOf(source.startMsecTimestamp)
        private set
    var lastLat by mutableStateOf(source.lastLat)
        private set
    var lastLng by mutableStateOf(source.lastLng)
        private set
    var lastAlt by mutableStateOf(source.lastAlt)
        private set
    var lastTimestamp by mutableStateOf(source.durationInSecAsString)
        private set
    var mappedId by mutableStateOf(source.mappedId)
        private set

    fun updateFrom(spec: CtDroneSpec) {
        flightStartMsec = spec.startMsecTimestamp
        lastLat = spec.lastLat
        lastLng = spec.lastLng
        lastAlt = spec.lastAlt
        lastTimestamp = spec.durationInSecAsString
        mappedId = spec.mappedId
    }
}

/** Display-ready values computed by [DroneAltitudeCoordinator] for use in labels and clues. */
data class DroneDisplayState(
    val headingDeg: Double?,
    val derivedHeadingDeg: Double? = null,
    val aglFt: Double?,
    /** True when the AGL value is DEM-sourced but the DEM data is stale or the drone has moved. */
    val aglStale: Boolean = false,
    /** True when aglFt is DEM-backed; false means we are falling back to ATO / flat-earth estimate. */
    val aglUsesDem: Boolean = false,
    val atoFt: Double?,
    val rangeFt: Double? = null,
)

data class AutomaticStreamPairingRequest(
    val generation: Long,
    val streamDesignator: String,
    val remoteId: String,
)

internal fun streamTelemetryDisplayState(
    streamDesignator: String,
    pairedMappedId: String?,
    displayStateByDesignator: Map<String, DroneDisplayState>
): DroneDisplayState? {
    val pairedDisplay = pairedMappedId
        ?.takeIf { it.isNotBlank() }
        ?.let { displayStateByDesignator[it] }
    return pairedDisplay ?: displayStateByDesignator[streamDesignator]
}

internal fun streamTelemetrySummaryDesignatorLabel(
    streamDesignator: String,
    droneSpec: CtDroneSpec
): String = droneSpec.mappedId
    ?.takeIf { it.isNotBlank() }
    ?: streamDesignator

internal data class AnomalyPolicyUpdate(
    val designator: String,
    val thermalPaused: Boolean,
    val personRelevanceMode: PersonRelevanceMode,
    val config: org.ncssar.rid2caltopo.video.anomaly.NativeAnomalyConfig,
)

internal fun anomalyPolicyChanged(
    previous: AnomalyPolicyUpdate?,
    next: AnomalyPolicyUpdate,
): Boolean = previous != next

internal fun shouldKeepFfmpegRender(
    streamsUiActive: Boolean,
    normalRenderSelected: Boolean,
    managedVideoSourceRequired: Boolean,
): Boolean = managedVideoSourceRequired || (streamsUiActive && normalRenderSelected)

internal fun managedVideoRequiredSources(
    requestSources: Set<String>,
    previewSources: Set<String>,
): Set<String> = requestSources + previewSources

internal fun shouldEnsureManagedVideoRenderSession(
    managedVideoSourceRequired: Boolean,
    activeRenderSessionId: Long?,
): Boolean = managedVideoSourceRequired && activeRenderSessionId == null

class StreamsViewModel(
    application: Application
) : AndroidViewModel(application),
    CtDroneSpec.DroneSpecsChangedListener,
    CaltopoMap.MapStatusListener {

    private val tag = "StreamsViewModel"
    private val processLoadSampleIntervalMs = 1_000L
    private val hotProcessCpuFractionForSecondStream = 0.85
    private val hotProcessCpuFractionForThirdOrFourthStream = 0.55
    private val hotMainThreadCpuFractionForThirdOrFourthStream = 0.30
    private val processLoadLogIntervalMs = 5_000L
    private val ffmpegProbeService: FfmpegProbeService? = try {
        FfmpegProbeService(
            onLiveFrame = { designator, observedAtMs ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    notePairedLiveVideoFrame(designator, observedAtMs)
                }
            },
            onCameraTelemetry = { designator, sample ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    cameraTelemetryExpiryJobs.remove(designator)?.cancel()
                    if (sample == null) {
                        _streamCameraTelemetryByDesignator.remove(designator)
                    } else {
                        _streamCameraTelemetryByDesignator[designator] = sample
                        cameraTelemetryExpiryJobs[designator] = viewModelScope.launch(Dispatchers.Main.immediate) {
                            kotlinx.coroutines.delay(StreamCameraTelemetryRegistry.DEFAULT_MAX_AGE_MS + 1L)
                            if (_streamCameraTelemetryByDesignator[designator]?.receivedAtMs == sample.receivedAtMs) {
                                _streamCameraTelemetryByDesignator.remove(designator)
                            }
                            cameraTelemetryExpiryJobs.remove(designator)
                        }
                    }
                }
            },
            onLocalPlaybackEof = { designator ->
                viewModelScope.launch {
                    handleLocalPlaybackEof(designator)
                }
            },
            onLocalPlaybackEnded = { designator ->
                viewModelScope.launch {
                    val localInfo = streamInfoByDesignator[designator]
                    val exoPlayerActive = streamSessionService.playerFor(designator) != null
                    val ffmpegStillOwnsPlayback = renderRouteByDesignator[designator] == true
                    if (localInfo?.isLocalPlayback == true && !exoPlayerActive && ffmpegStillOwnsPlayback) {
                        closeStream(designator)
                    } else {
                        CTDebug(
                            tag,
                            "Ignoring FFmpeg local-playback end for $designator " +
                                "local=${localInfo?.isLocalPlayback == true} " +
                                "exoActive=$exoPlayerActive ffmpegOwns=$ffmpegStillOwnsPlayback"
                        )
                    }
                }
            }
        )
    } catch (t: Throwable) {
        CTError(tag, "FFmpeg probe service unavailable; stream playback will remain unavailable.", Exception(t))
        null
    }
    private val streamSessionService = StreamSessionService(
        context = application.applicationContext,
        scope = viewModelScope,
        // Prefer RTSP for ExoPlayer tiles. The bundled MediaMTX SDP now uses a
        // non-empty session name so ExoPlayer's RtspMediaSource can parse it.
        // This avoids the HLS muxing/playlist churn that was adding large lag and
        // repeated stalls in multi-stream scenarios.
        preferredModeProvider = { StreamSessionService.ProtocolMode.RTSP },
        // All drone video is bursty: the controller batches H.264 NAL units and sends
        // them in tight clusters with gaps of 200–800 ms between bursts.  Enable the
        // bursty-HLS tuning (larger buffer headroom, faster stall recovery) for every
        // stream so ExoPlayer handles inter-burst silences without needless buffering.
        burstyHlsSourceProvider = { true },
        sourcePathProvider = { designator -> streamInfoByDesignator[designator]?.sourcePath ?: designator },
        localPlaybackUriProvider = { designator -> streamInfoByDesignator[designator]?.playbackUri },
        listener = object : StreamSessionService.Listener {
            override fun onBuffering(designator: String) {
                _playbackIndicatorStateByDesignator[designator] = PlaybackIndicatorState.BUFFERING
            }

            override fun onLive(designator: String) {
                if (_renderDelayMsByDesignator[designator] == null &&
                    renderRouteByDesignator[designator] == false
                ) {
                    _playbackIndicatorStateByDesignator[designator] = PlaybackIndicatorState.LIVE_UNMEASURED
                } else {
                    _playbackIndicatorStateByDesignator.remove(designator)
                }
            }

            override fun onEnded(designator: String) {
                _playbackIndicatorStateByDesignator.remove(designator)
            }

            override fun onError(designator: String, error: androidx.media3.common.PlaybackException) {
                _playbackIndicatorStateByDesignator.remove(designator)
            }

            override fun onPlaybackDelayChanged(designator: String, delayMs: Long?) {
                if (delayMs == null) {
                    _renderDelayMsByDesignator.remove(designator)
                } else {
                    _renderDelayMsByDesignator[designator] = delayMs
                    if (_playbackIndicatorStateByDesignator[designator] == PlaybackIndicatorState.LIVE_UNMEASURED) {
                        _playbackIndicatorStateByDesignator.remove(designator)
                    }
                }
            }
        },
        isLocalPlaybackProvider = { designator -> streamInfoByDesignator[designator]?.isLocalPlayback == true },
    )

    private val _localPlaybackEntries = MutableStateFlow<Map<String, StreamInfo>>(emptyMap())

    val streams: StateFlow<Map<String, StreamInfo>> =
        combine(StreamRegistry.streams, _localPlaybackEntries) { liveStreams, localPlaybackEntries ->
            buildMap<String, StreamInfo> {
                putAll(liveStreams)
                putAll(localPlaybackEntries)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    private val _focusedPath = MutableStateFlow<String?>(null)
    val focusedPath: StateFlow<String?> = _focusedPath.asStateFlow()
    private val localPlaybackPausedState = mutableStateMapOf<String, Boolean>()
    private var localPlaybackPauseOnOpenEnabled by mutableStateOf(false)
    private val localPlaybackReviewByDesignator = mutableStateMapOf<String, LocalPlaybackReviewFile>()
    private var pendingLocalPlaybackReviewExport by mutableStateOf<PendingLocalPlaybackReviewExport?>(null)
    private val dirtyLocalPlaybackReviews = mutableStateSetOf<String>()
    private val _streamsUiActive = MutableStateFlow(false)
    private val streamsUiConsumerLock = Any()
    private val streamsUiConsumers = mutableSetOf<Any>()
    private val managedVideoRequestSources = mutableSetOf<String>()
    private val managedVideoPreviewSources = mutableSetOf<String>()

    private val _droneStates = mutableStateMapOf<String, DroneSpecState>()
    val droneStates: Map<String, DroneSpecState> get() = _droneStates
    private val runtimeStreamTelemetryBindings = mutableStateMapOf<String, String>()
    private val _streamCameraTelemetryByDesignator =
        mutableStateMapOf<String, StreamCameraTelemetrySample>()
    private val cameraTelemetryExpiryJobs = mutableMapOf<String, Job>()
    private var automaticPairingGeneration = 0L
    var automaticStreamPairingRequest by mutableStateOf<AutomaticStreamPairingRequest?>(null)
        private set
    private var configuredStreamBindings by mutableStateOf<Map<String, String>>(emptyMap())
    private var configuredStreamDesignatorsByRemoteId by mutableStateOf<Map<String, String>>(emptyMap())
    private val _anomalyConfigByDesignator = mutableStateMapOf<String, AnomalyConfig>()
    private var defaultAnomalyConfig by mutableStateOf(AnomalyConfig())
    private var lastCapturedVideoSelectionUri: Uri? by mutableStateOf(null)
    private val capturedVideoOpenGeneration = AtomicLong(0L)
    private val _renderDelayMsByDesignator = mutableStateMapOf<String, Long>()
    private val _playbackIndicatorStateByDesignator = mutableStateMapOf<String, PlaybackIndicatorState>()
    private val renderRouteByDesignator = mutableStateMapOf<String, Boolean>()
    private val streamInfoByDesignator = mutableMapOf<String, StreamInfo>()
    private val dismissedStreamRevisions = mutableStateMapOf<String, Long>()
    private val lastAppliedAnomalyPolicyByDesignator = mutableMapOf<String, AnomalyPolicyUpdate>()
    private val anomalyPolicyApplyQueue = SerializedTaskQueue(viewModelScope, Dispatchers.Default)
    private var latestProcessLoadSnapshot by mutableStateOf<ProcessLoadSnapshot?>(null)
    private var lastProcessLoadLogAtMs = 0L
    /** Coordinator that owns all DEM / AGL / ATO / heading computation. */
    internal val altitudeCoordinator = DroneAltitudeCoordinator(
        scope = viewModelScope,
        appContext = application.applicationContext,
        droneStates = _droneStates,
    )
    private val centerpointElevationCache = mutableMapOf<
        String,
        Pair<CenterpointElevationInputKey, CenterpointElevationSample>
    >()

    /**
     * Register a UI consumer so the coordinator's update loop stays alive.
     * Call from a [DisposableEffect] and invoke the returned lambda from [onDispose].
     */
    fun addAltitudeConsumer(): () -> Unit = altitudeCoordinator.addConsumer()

    fun prefetchTerrainForLocation(latitude: Double, longitude: Double) {
        altitudeCoordinator.prefetchTerrainForLocation(latitude, longitude)
    }

    fun droneDisplayStateFor(mappedId: String): DroneDisplayState? =
        altitudeCoordinator.displayStateByDesignator[mappedId]

    fun droneDisplayStateForStream(streamDesignator: String): DroneDisplayState? {
        val pairedMappedId = pairedDroneSpecStateFor(streamDesignator)?.mappedId
        return streamTelemetryDisplayState(
            streamDesignator = streamDesignator,
            pairedMappedId = pairedMappedId,
            displayStateByDesignator = altitudeCoordinator.displayStateByDesignator
        )
    }

    fun cameraAzimuthForStream(streamDesignator: String): Double? =
        _streamCameraTelemetryByDesignator[streamDesignator]
            ?.azimuthDeg
            ?.takeIf { it.isFinite() }

    internal suspend fun centerpointElevationForStream(
        streamDesignator: String,
        nowMs: Long = System.currentTimeMillis(),
    ): CenterpointElevationSample? {
        val droneState = pairedDroneSpecStateFor(streamDesignator) ?: droneStates[streamDesignator]
        val droneSpec = droneState?.source ?: return null
        val camera = StreamCameraTelemetryRegistry.fresh(
            designator = streamDesignator,
            nowMs = nowMs,
        ) ?: return null
        val bearing = (camera.fovAzimuthDeg ?: camera.azimuthDeg)
            ?.takeIf { it.isFinite() }
            ?: return null
        val tilt = camera.tiltDeg.takeIf { it.isFinite() && it < -0.1 } ?: return null
        val displayState = streamTelemetryDisplayState(
            streamDesignator = streamDesignator,
            pairedMappedId = droneSpec.mappedId,
            displayStateByDesignator = altitudeCoordinator.displayStateByDesignator,
        )
        val aglMeters = displayState?.aglFt
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.div(METERS_TO_FEET)
            ?: return null
        val latitude = droneSpec.lastLat.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = droneSpec.lastLng.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val altitudeMeters = droneSpec.lastAlt.takeIf { it.isFinite() } ?: return null
        val inputKey = CenterpointElevationInputKey(
            latitudeE5 = kotlin.math.round(latitude * 100_000.0).toLong(),
            longitudeE5 = kotlin.math.round(longitude * 100_000.0).toLong(),
            altitudeHalfMeters = kotlin.math.round(altitudeMeters * 2.0).toLong(),
            aglHalfMeters = kotlin.math.round(aglMeters * 2.0).toLong(),
            bearingFifths = kotlin.math.round(bearing * 5.0).toLong(),
            tiltFifths = kotlin.math.round(tilt * 5.0).toLong(),
        )
        centerpointElevationCache[streamDesignator]
            ?.takeIf { it.first == inputKey }
            ?.let { return it.second }

        val projected = if (tilt <= -89.9) {
            ClueProjection(latitude, longitude, altitudeMeters - aglMeters)
        } else {
            projectClueLocationWithDem(
                demElevationService = altitudeCoordinator.demElevationService,
                droneLat = latitude,
                droneLng = longitude,
                droneAlt = altitudeMeters,
                headingDeg = bearing,
                aglMeters = aglMeters,
                gimbalAngleDeg = tilt,
            )
        }
        val terrain = altitudeCoordinator.demElevationService.sampleElevationMeters(
            projected.lat,
            projected.lng,
        ) ?: return null
        val result = CenterpointElevationSample(
            latitude = projected.lat,
            longitude = projected.lng,
            elevationFeet = kotlin.math.round(terrain.elevationMeters * METERS_TO_FEET).toInt(),
            demResolutionMeters = terrain.horizontalResolutionMeters
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { kotlin.math.round(it).toInt().coerceAtLeast(1) },
        )
        centerpointElevationCache[streamDesignator] = inputKey to result
        return result
    }

    /**
     * No-op stub retained for any call sites not yet migrated.
     * @deprecated Coordinator now owns display state; callers should be removed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateDroneDisplayState(designator: String, headingDeg: Double?, aglFt: Double?, atoFt: Double?) = Unit

    // --- Map Folders visibility state ---
    // Persisted in the ViewModel so user selections survive navigation away and back.

    internal val mapArtifactRenderCache = MapArtifactRenderCache()

    /** Folder IDs whose contents should be hidden on the local map. */
    val hiddenFolderIds = mutableStateSetOf<String>()

    /** Individual feature IDs hidden regardless of their folder's visibility. */
    val hiddenItemIds = mutableStateSetOf<String>()

    /** Explicit operator choices that take precedence over defaults for this map session. */
    val folderVisibilityOverrides = mutableStateMapOf<String, Boolean>()

    /**
     * Tracks which folder IDs have already had their Caltopo default visibility applied,
     * so we don't override the user's manual selections on re-entry.
     */
    private val seenFolderIds = HashSet<String>()

    /**
     * Called when a Folder feature is encountered in the artifact stream.
     * Re-applies server-hidden state so CalTopo folder hides win after reloads; visible
     * folders are still only recorded once so local hides survive ordinary reconnects.
     */
    fun applyCaltopoFolderDefault(folderId: String, caltopoVisible: Boolean) {
        val hidden = folderHiddenAfterDefault(
            currentlyHidden = folderId in hiddenFolderIds,
            defaultVisible = caltopoVisible,
            operatorVisibilityOverride = folderVisibilityOverrides[folderId]
        )
        if (hidden) hiddenFolderIds.add(folderId) else hiddenFolderIds.remove(folderId)
        if (folderId in seenFolderIds) return
        seenFolderIds.add(folderId)
    }

    /** Records a local operator choice that remains authoritative until the map resets. */
    fun setMapFolderVisibility(folderId: String, visible: Boolean) {
        folderVisibilityOverrides[folderId] = visible
        if (visible) {
            hiddenFolderIds.remove(folderId)
        } else {
            hiddenFolderIds.add(folderId)
        }
    }

    /** Clears all folder/item visibility state and the seen-folder registry (e.g. on map disconnect). */
    fun resetFolderVisibility() {
        hiddenFolderIds.clear()
        hiddenItemIds.clear()
        folderVisibilityOverrides.clear()
        seenFolderIds.clear()
    }

    private val _pendingClue = mutableStateOf<PendingClue?>(null)
    val pendingClue: PendingClue?
        get() = _pendingClue.value
    val localMapMarkers = mutableStateListOf<LocalMapMarker>()
    private val clueSnapshotRefsByTitle = mutableStateMapOf<String, ClueSnapshotRef>()
    private val localClueStore = AndroidClueStore(application.applicationContext)
    private var activeLocalClueMapKey: String? = null

    private val _mapName = mutableStateOf<String?>(null)
    val mapName: String? by _mapName
    private val _layoutMode = MutableStateFlow(StreamsLayoutMode.Both)
    val layoutMode: StateFlow<StreamsLayoutMode> = _layoutMode.asStateFlow()
    private val streamPipPrefs by lazy {
        getApplication<Application>()
            .applicationContext
            .getSharedPreferences("stream_pip_prefs", android.content.Context.MODE_PRIVATE)
    }
    private val _streamPipUiState = mutableStateOf(
        StreamPipUiState.fromPersisted(
            enabled = streamPipPrefs.getBoolean("enabled", false),
            insetFraction = streamPipPrefs.getFloat("inset_fraction", STREAM_PIP_DEFAULT_INSET_FRACTION)
        )
    )
    val streamPipUiState: StreamPipUiState
        get() = _streamPipUiState.value
    private val _followFocusedDroneEnabled = mutableStateOf(
        streamPipPrefs.getBoolean("follow_focused_drone_enabled", true)
    )
    val followFocusedDroneEnabled: Boolean
        get() = _followFocusedDroneEnabled.value
    private val _proximityMapFocusTarget = MutableStateFlow<ProximityMapFocusTarget?>(null)
    val proximityMapFocusTarget: StateFlow<ProximityMapFocusTarget?> = _proximityMapFocusTarget.asStateFlow()
    private val _coordinateDisplayFormat = mutableStateOf<CoordinateDisplayFormat>(
        restoredCoordinateDisplayFormat(
            streamPreference = streamPipPrefs.getString(STREAM_COORDINATE_DISPLAY_FORMAT_KEY, null),
            legacyConfigPreference = CaltopoClient.GetCoordinateDisplayFormat(),
        )
    )
    val coordinateDisplayFormat: CoordinateDisplayFormat
        get() = _coordinateDisplayFormat.value
    private val _baseLayer = mutableStateOf(
        MapCacheSettings.baseLayer(application.applicationContext)
    )
    internal val baseLayer: org.ncssar.rid2caltopo.video.BaseLayerOption
        get() = _baseLayer.value
    private var persistedMapViewportState: MapViewportState? = null
    private var clueProjectionJob: Job? = null
    private val mutedComplianceAlertDesignators = mutableStateSetOf<String>()
    private val _overLimitDrones = MutableStateFlow<List<OverLimitDroneUiState>>(emptyList())
    val overLimitDrones: StateFlow<List<OverLimitDroneUiState>> = _overLimitDrones.asStateFlow()

    private var lastLiveRevisions: Map<String, Long> = emptyMap()
    private var lastLivePublisherConnIds: Map<String, String?> = emptyMap()

    /**
     * Received from CaltopoClient at a maximum rate of once per second if
     * there are any active dronespecs.
     */
    override fun onDroneSpecsChanged(currentDrones: List<CtDroneSpec>) {
        refreshConfiguredStreamBindings()
        val activeKeys = HashSet<String>(currentDrones.size)
        if (currentDrones.isNotEmpty()) {
            CTInfo(tag, "onDroneSpecsChanged(): received ${currentDrones.size} dronespecs.")
            currentDrones.forEach { spec ->
                val key = spec.mappedId
                activeKeys.add(key)
                val state = _droneStates.getOrPut(key) { DroneSpecState(spec) }
                state.updateFrom(spec)
                altitudeCoordinator.prefetchTerrainForLocation(spec.lastLat, spec.lastLng)
            }
        }
        _droneStates.keys.toList().forEach { key ->
            if (!activeKeys.contains(key)) {
                _droneStates.remove(key)
            }
        }
        val complianceNowMs = System.currentTimeMillis()
        val overLimit = _droneStates.mapNotNull { (designator, state) ->
            if (state.source.isLocalArchiveOnly) return@mapNotNull null
            if (!ComplianceAlertCenter.isFreshAltitudeSample(
                    state.source.mostRecentMsecTimestamp,
                    complianceNowMs,
                )
            ) return@mapNotNull null
            val displayState = altitudeCoordinator.displayStateByDesignator[designator] ?: return@mapNotNull null
            val aglFt = displayState.aglFt ?: return@mapNotNull null
            if (aglFt < COMPLIANCE_ALERT_AGL_LIMIT_FT) return@mapNotNull null
            OverLimitDroneUiState(
                remoteId = state.remoteId,
                mappedId = designator,
                aglFt = aglFt,
                thresholdFt = COMPLIANCE_ALERT_AGL_LIMIT_FT,
                muted = designator in mutedComplianceAlertDesignators,
                staleDem = displayState.aglStale,
                usingDemAgl = displayState.aglUsesDem,
                atoFt = displayState.atoFt
            )
        }.sortedByDescending { it.aglFt }
        _overLimitDrones.value = overLimit
        ComplianceAlertCenter.updateCandidates(
            overLimit
                .filterNot { it.muted }
                .map {
                    ComplianceAlertCandidate(
                        remoteId = it.remoteId,
                        mappedId = it.mappedId,
                        aglFt = it.aglFt,
                        thresholdFt = it.thresholdFt,
                        staleDem = it.staleDem,
                        telemetryTimestampMs = _droneStates[it.mappedId]
                            ?.source
                            ?.mostRecentMsecTimestamp
                            ?: 0L,
                    )
                }
        )
    }

    fun setComplianceAlertMuted(mappedId: String, muted: Boolean) {
        if (muted) {
            mutedComplianceAlertDesignators.add(mappedId)
        } else {
            mutedComplianceAlertDesignators.remove(mappedId)
        }
        _overLimitDrones.value = _overLimitDrones.value.map { drone ->
            if (drone.mappedId == mappedId) drone.copy(muted = muted) else drone
        }
        ComplianceAlertCenter.updateCandidates(
            _overLimitDrones.value
                .filterNot { it.muted }
                .map {
                    ComplianceAlertCandidate(
                        remoteId = it.remoteId,
                        mappedId = it.mappedId,
                        aglFt = it.aglFt,
                        thresholdFt = it.thresholdFt,
                        staleDem = it.staleDem,
                        telemetryTimestampMs = _droneStates[it.mappedId]
                            ?.source
                            ?.mostRecentMsecTimestamp
                            ?: 0L,
                    )
                }
        )
    }

    fun isStreamVisible(stream: StreamInfo): Boolean {
        return dismissedStreamRevisions[stream.designator] != stream.revision
    }

    fun isLocalPlayback(designator: String): Boolean {
        return streamInfoByDesignator[designator]?.isLocalPlayback == true
    }

    fun isLocalPlaybackPaused(designator: String): Boolean {
        return localPlaybackPausedState[designator]
            ?: (ffmpegProbeService?.isLocalPlaybackPaused(designator) == true)
    }

    fun pauseLocalPlaybackOnOpenEnabled(): Boolean = localPlaybackPauseOnOpenEnabled

    fun setPauseLocalPlaybackOnOpen(enabled: Boolean) {
        localPlaybackPauseOnOpenEnabled = enabled
    }

    fun toggleLocalPlaybackPaused(designator: String) {
        val probeService = ffmpegProbeService ?: return
        val paused = probeService.isLocalPlaybackPaused(designator)
        val nextPaused = !paused
        localPlaybackPausedState[designator] = nextPaused
        viewModelScope.launch(Dispatchers.Default) {
            probeService.setLocalPlaybackPaused(designator, nextPaused)
        }
    }

    fun stepLocalPlaybackFrame(designator: String, frameCount: Int = 1) {
        localPlaybackPausedState[designator] = true
        val probeService = ffmpegProbeService ?: return
        viewModelScope.launch(Dispatchers.Default) {
            probeService.stepLocalPlayback(designator, frameCount)
        }
    }

    fun stepLocalPlaybackBack(designator: String) {
        localPlaybackPausedState[designator] = true
        val probeService = ffmpegProbeService ?: return
        viewModelScope.launch(Dispatchers.Default) {
            probeService.stepLocalPlaybackBack(designator)
        }
    }

    private fun handleLocalPlaybackEof(designator: String) {
        localPlaybackPausedState[designator] = true
        val localInfo = streamInfoByDesignator[designator] ?: return
        if (!localInfo.isLocalPlayback) return
        queueLocalPlaybackReviewExportIfNeeded(localInfo)
        if (pendingLocalPlaybackReviewExport != null) {
            CaltopoClient.ShowToast("Reached end of ${localInfo.designator}. Save the review annotations when ready.")
        }
    }

    fun runtimeSnapshotFor(designator: String): StreamRuntimeSnapshot? {
        if (renderRouteByDesignator[designator] != true) return null
        return ffmpegProbeService?.runtimeSnapshot(designator)
    }

    fun anomalyPauseReasonFor(designator: String): String? {
        return ffmpegProbeService?.anomalyPauseReason(designator)
    }

    fun localPlaybackFrameCounterText(designator: String): String? {
        val timestampUs = runtimeSnapshotFor(designator)?.currentSourceTimestampUs ?: return null
        return "T ${formatPlaybackTimestampUs(timestampUs)}"
    }

    fun pendingLocalPlaybackReviewExport(): PendingLocalPlaybackReviewExport? {
        return pendingLocalPlaybackReviewExport
    }

    fun clearPendingLocalPlaybackReviewExport() {
        pendingLocalPlaybackReviewExport = null
    }

    fun completeLocalPlaybackReviewExport(targetUri: Uri?) {
        val pending = pendingLocalPlaybackReviewExport ?: return
        if (targetUri != null) {
            val context = getApplication<Application>().applicationContext
            runCatching {
                context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                    output.write(pending.jsonText.toByteArray(Charsets.UTF_8))
                } ?: error("Unable to open export destination")
            }.onFailure { error ->
                CaltopoClient.ShowToast("Review export failed: ${error.message ?: "unknown error"}")
                CTDebug(tag, "Review export failed for ${pending.designator}: ${error.message}")
            }.onSuccess {
                CaltopoClient.ShowToast("Saved review for ${pending.designator}.")
                dirtyLocalPlaybackReviews.remove(pending.designator)
            }
        }
        pendingLocalPlaybackReviewExport = null
    }

    fun localPlaybackFrameAnnotations(
        designator: String,
        sourceTimestampUs: Long?,
    ): List<LocalPlaybackPointAnnotation> {
        val timestampUs = sourceTimestampUs?.takeIf { it > 0L } ?: return emptyList()
        val review = localPlaybackReviewByDesignator[designator] ?: return emptyList()
        return review.frames.firstOrNull { it.sourceTimestampUs == timestampUs }?.annotations.orEmpty()
    }

    fun localPlaybackFrameAnnotationSummary(
        designator: String,
        sourceTimestampUs: Long?,
    ): String? {
        return buildLocalPlaybackFrameAnnotationSummary(localPlaybackFrameAnnotations(designator, sourceTimestampUs))
    }

    fun clearLocalPlaybackReviewAnnotations(designator: String) {
        val streamInfo = _localPlaybackEntries.value[designator] ?: return
        val sidecarPath = streamInfo.annotationSidecarPath
        localPlaybackReviewByDesignator[designator] = LocalPlaybackReviewFile(
            sourceDisplayName = streamInfo.designator,
            originalSourceUri = streamInfo.originalSourceUri?.toString(),
            playbackUri = streamInfo.playbackUri?.toString(),
            annotationSidecarPath = sidecarPath,
            updatedAtMs = System.currentTimeMillis(),
            frames = mutableListOf(),
        )
        dirtyLocalPlaybackReviews.remove(designator)
        pendingLocalPlaybackReviewExport =
            pendingLocalPlaybackReviewExport?.takeUnless { it.designator == designator }
        if (!sidecarPath.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { File(sidecarPath).delete() }
                    .onFailure { error ->
                        CTDebug(tag, "Failed clearing local playback review for $designator: ${error.message}")
                    }
            }
        }
    }

    fun addLocalPlaybackPointAnnotation(
        designator: String,
        sourceTimestampUs: Long,
        xNorm: Float,
        yNorm: Float,
        verdict: LocalPlaybackAnnotationVerdict,
        reviewKind: LocalPlaybackReviewKind,
        objectType: LocalPlaybackAnnotationType,
        scenario: LocalPlaybackScenario?,
        note: String,
        anomalyDebugSummary: String?,
    ) {
        val streamInfo = _localPlaybackEntries.value[designator] ?: return
        val sidecarPath = streamInfo.annotationSidecarPath ?: return
        val nowMs = System.currentTimeMillis()
        val existing = localPlaybackReviewByDesignator[designator]
            ?: loadLocalPlaybackReviewFromDisk(streamInfo)
            ?: LocalPlaybackReviewFile(
                sourceDisplayName = streamInfo.designator,
                originalSourceUri = streamInfo.originalSourceUri?.toString(),
                playbackUri = streamInfo.playbackUri?.toString(),
                annotationSidecarPath = sidecarPath,
                updatedAtMs = nowMs,
            )
        val frameIndex = existing.frames.indexOfFirst { it.sourceTimestampUs == sourceTimestampUs }
        val annotation = LocalPlaybackPointAnnotation(
            xNorm = xNorm.coerceIn(0f, 1f),
            yNorm = yNorm.coerceIn(0f, 1f),
            verdict = verdict,
            reviewKind = reviewKind,
            objectType = objectType,
            scenario = scenario,
            note = note.trim(),
            createdAtMs = nowMs,
            anomalyDebugSummary = anomalyDebugSummary,
        )
        val updatedFrames = existing.frames.toMutableList()
        if (frameIndex >= 0) {
            val frame = updatedFrames[frameIndex]
            updatedFrames[frameIndex] = frame.copy(
                annotations = frame.annotations.toMutableList().apply { add(annotation) }
            )
        } else {
            updatedFrames += LocalPlaybackFrameReview(
                sourceTimestampUs = sourceTimestampUs,
                annotations = mutableListOf(annotation),
            )
        }
        val updated = existing.copy(
            updatedAtMs = nowMs,
            frames = updatedFrames.sortedBy { it.sourceTimestampUs }.toMutableList(),
        )
        localPlaybackReviewByDesignator[designator] = updated
        dirtyLocalPlaybackReviews += designator
        viewModelScope.launch(Dispatchers.IO) {
            writeLocalPlaybackReviewToDisk(updated, sidecarPath)
        }
    }

    fun useFfmpegRender(designator: String): Boolean {
        return renderRouteByDesignator[designator] == true
    }

    fun bindFfmpegRenderSurface(designator: String, surface: Surface): Boolean {
        return ffmpegProbeService?.bindRenderSurface(designator, surface) == true
    }

    fun unbindFfmpegRenderSurface(designator: String, surface: Surface?) {
        ffmpegProbeService?.unbindRenderSurface(designator, surface)
    }

    fun getExoPlayerFor(designator: String): ExoPlayer? = streamSessionService.playerFor(designator)

    fun capturedVideoPickerInitialUri(): Uri? {
        return lastCapturedVideoSelectionUri
            ?: CaltopoClient.GetTodaysTrackDir()?.uri
            ?: CaltopoClient.GetArchiveUri()
            ?: CaltopoClient.GetArchiveUriSelectionHint()
    }

    fun openCapturedVideo(uri: Uri, displayName: String) {
        lastCapturedVideoSelectionUri = uri
        val normalizedName = displayName.ifBlank { "Captured Video" }
        val openGeneration = capturedVideoOpenGeneration.incrementAndGet()
        val plan = capturedVideoPlaybackPlan(
            normalizedName = normalizedName,
            activeStreams = StreamRegistry.streams.value,
            localPlaybackEntries = _localPlaybackEntries.value,
        )
        plan.localPlaybackDesignatorsToClose.forEach { existingDesignator ->
            closeLocalPlaybackEntry(existingDesignator, showToast = false)
        }
        val designator = plan.designator

        val pendingInfo = StreamInfo(
            designator = designator,
            sourcePath = uri.toString(),
            playbackUri = uri,
            originalSourceUri = uri,
            isLocalPlayback = true,
            state = StreamState.CONNECTING,
            revision = 1L,
        )
        _localPlaybackEntries.value = _localPlaybackEntries.value.toMutableMap().apply {
            this[designator] = pendingInfo
        }
        if (!_anomalyConfigByDesignator.containsKey(designator)) {
            _anomalyConfigByDesignator[designator] = defaultAnomalyConfig.forLocalPlaybackReview()
        }
        localPlaybackPausedState[designator] = localPlaybackPauseOnOpenEnabled
        ffmpegProbeService?.setLocalPlaybackPaused(designator, localPlaybackPauseOnOpenEnabled)
        _focusedPath.value = designator
        syncStreamSessions(currentResyncSnapshot())
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedInfo = try {
                val cachedUri = stageCapturedVideoForPlayback(designator, uri, displayName)
                val annotationSidecarPath = annotationSidecarPathForPlaybackUri(cachedUri)
                pendingInfo.copy(
                    sourcePath = cachedUri.toString(),
                    playbackUri = cachedUri,
                    originalSourceUri = uri,
                    annotationSidecarPath = annotationSidecarPath,
                    state = StreamState.LIVE,
                    revision = pendingInfo.revision + 1L,
                )
            } catch (t: Throwable) {
                pendingInfo.copy(
                    state = StreamState.ERROR,
                    errorDetail = t.message ?: "Unable to prepare captured video",
                    revision = pendingInfo.revision + 1L,
                )
            }
            withContext(Dispatchers.Main) {
                if (capturedVideoOpenGeneration.get() != openGeneration) {
                    resolvedInfo.playbackUri
                        ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
                        ?.path
                        ?.let { path -> runCatching { File(path).delete() } }
                    return@withContext
                }
                val current = _localPlaybackEntries.value[designator] ?: return@withContext
                localPlaybackReviewByDesignator[designator] = loadLocalPlaybackReviewFromDisk(resolvedInfo)
                    ?: LocalPlaybackReviewFile(
                        sourceDisplayName = resolvedInfo.designator,
                        originalSourceUri = resolvedInfo.originalSourceUri?.toString(),
                        playbackUri = resolvedInfo.playbackUri?.toString(),
                        annotationSidecarPath = resolvedInfo.annotationSidecarPath,
                        updatedAtMs = 0L,
                    )
                dirtyLocalPlaybackReviews.remove(designator)
                _localPlaybackEntries.value = _localPlaybackEntries.value.toMutableMap().apply {
                    this[designator] = resolvedInfo.copy(revision = maxOf(current.revision + 1L, resolvedInfo.revision))
                }
                syncStreamSessions(currentResyncSnapshot())
            }
        }
    }

    private fun closeLocalPlaybackEntry(designator: String, showToast: Boolean): Boolean {
        val localInfo = _localPlaybackEntries.value[designator] ?: return false
        queueLocalPlaybackReviewExportIfNeeded(localInfo)
        localPlaybackPausedState.remove(designator)
        dirtyLocalPlaybackReviews.remove(designator)
        localPlaybackReviewByDesignator.remove(designator)
        _localPlaybackEntries.value = _localPlaybackEntries.value.toMutableMap().apply {
            remove(designator)
        }
        localInfo.playbackUri
            ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
            ?.path
            ?.let { path ->
                runCatching { File(path).delete() }
            }
        dismissedStreamRevisions.remove(designator)
        if (_focusedPath.value == designator) {
            _focusedPath.value = null
        }
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
        if (showToast) {
            CaltopoClient.ShowToast("Closed $designator.")
        }
        return true
    }

    fun managedVideoRenderSessionId(designator: String): Long? =
        ffmpegProbeService?.activeRenderSessionId(designator)

    fun startManagedVideoRecordingSession(designator: String, inputUrl: String): Long? =
        ffmpegProbeService?.startAuxiliaryRenderSession(designator, inputUrl)

    fun managedVideoSourceInfo(designator: String) =
        ffmpegProbeService?.videoSourceInfo(designator)

    fun hasRecentManagedVideoFrame(designator: String, maxAgeMs: Long = 6_000L): Boolean =
        ffmpegProbeService?.hasRecentFrame(designator, maxAgeMs) == true

    fun closeStream(designator: String) {
        if (closeLocalPlaybackEntry(designator, showToast = true)) {
            return
        }
        if (_focusedPath.value != designator) {
            _focusedPath.value = designator
        }
        dismissFocusedStream()
    }

    fun toggleFocus(designator: String) {
        var fString = "has"
        _focusedPath.value =
            if (_focusedPath.value == designator) {
                fString = "does not have"
                null
            } else {
                designator
            }
        CTDebug(tag, "toggleFocus(): ${designator} ${fString} focus.")
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
    }

    fun ensureFocus(designator: String) {
        if (_focusedPath.value == designator) return
        _focusedPath.value = designator
        CTDebug(tag, "ensureFocus(): $designator has focus.")
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
    }

    fun addStreamsUiConsumer(): () -> Unit {
        val token = Any()
        var shouldSync = false
        synchronized(streamsUiConsumerLock) {
            streamsUiConsumers.add(token)
            if (!_streamsUiActive.value) {
                _streamsUiActive.value = true
                shouldSync = true
            }
            CTDebug(tag, "addStreamsUiConsumer(): count=${streamsUiConsumers.size}")
        }
        if (shouldSync) {
            syncStreamSessions(currentResyncSnapshot())
        }
        return {
            var becameInactive = false
            synchronized(streamsUiConsumerLock) {
                if (!streamsUiConsumers.remove(token)) return@synchronized
                CTDebug(tag, "removeStreamsUiConsumer(): count=${streamsUiConsumers.size}")
                if (streamsUiConsumers.isEmpty() && _streamsUiActive.value) {
                    _streamsUiActive.value = false
                    becameInactive = true
                }
            }
            if (becameInactive) {
                syncStreamSessions(currentResyncSnapshot())
            }
        }
    }

    fun setManagedVideoSourceRequired(designator: String, required: Boolean) {
        val normalized = designator.trim()
        if (normalized.isEmpty()) return
        val changed = synchronized(streamsUiConsumerLock) {
            if (required) {
                managedVideoRequestSources.add(normalized)
            } else {
                managedVideoRequestSources.remove(normalized)
            }
        }
        if (changed) {
            CTDebug(tag, "Managed video source $normalized required=$required")
            syncStreamSessions(currentResyncSnapshot())
        }
    }

    fun setManagedVideoPreviewSources(designators: Set<String>) {
        val normalized = designators
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val changed = synchronized(streamsUiConsumerLock) {
            if (managedVideoPreviewSources == normalized) {
                false
            } else {
                managedVideoPreviewSources.clear()
                managedVideoPreviewSources.addAll(normalized)
                true
            }
        }
        if (changed) {
            CTDebug(tag, "Managed video preview sources=${normalized.sorted()}")
            syncStreamSessions(currentResyncSnapshot())
        }
    }

    override fun onCleared() {
        StreamRegistry.setAdmissionGuard(null)
        CaltopoMap.RemoveMapStatusListener(this)
        ffmpegProbeService?.close()
        streamSessionService.releaseAll()
        super.onCleared()
    }

    override fun mapStatusUpdate(status: mapStatus?, mapNode: CaltopoNode.MapNode?, optErrmsg: String?) {
        val newName = mapNode?.title;
        val oldName = _mapName.value;
        if (status == CaltopoMap.MapStatusListener.mapStatus.up) {
            val mapKey = currentLocalClueMapKey()
            if (activeLocalClueMapKey != mapKey) {
                hydrateLocalClues(mapKey)
            }
            if (!oldName.equals(newName)) {
                persistedMapViewportState = null
                CTDebug(tag, "Connected to ${newName}")
                _mapName.value = newName
            }
        } else if (_mapName.value != null) {
            _mapName.value = null
            CTDebug(tag, "Disconnected from ${oldName} map")
            resetFolderVisibility()
            localMapMarkers.clear()
            clueSnapshotRefsByTitle.clear()
            activeLocalClueMapKey = null
        }
    }

    fun designatorStateFor(designator: String): DesignatorState {
        if (isLocalPlayback(designator)) return DesignatorState.Red
        val resolution = resolveStreamTelemetryBinding(
            streamDesignator = designator,
            telemetryStates = streamTelemetryStates(),
            runtimeStreamBindings = runtimeStreamTelemetryBindings,
            configuredStreamBindings = configuredStreamBindings
        )
        if (resolution.status == StreamTelemetryBindingStatus.PAIRED) {
            val pairedState = pairedDroneSpecStateFor(designator)
            if (pairedState != null) return DesignatorState.Green(pairedState)
        }
        return if (resolution.status == StreamTelemetryBindingStatus.NO_TELEMETRY) {
            DesignatorState.Red
        } else {
            DesignatorState.Yellow(droneStates)
        }
    }

    fun streamTilePrimaryLabel(streamDesignator: String): String {
        if (isLocalPlayback(streamDesignator)) return streamDesignator
        return resolveStreamTelemetryBinding(
            streamDesignator = streamDesignator,
            telemetryStates = streamTelemetryStates(),
            runtimeStreamBindings = runtimeStreamTelemetryBindings,
            configuredStreamBindings = configuredStreamBindings
        ).primaryLabel
    }

    fun bindStreamTelemetry(streamDesignator: String, remoteId: String) {
        bindStreamToRemoteId(runtimeStreamTelemetryBindings, streamDesignator, remoteId)
        StreamFlightActivityRegistry.bindRuntime(streamDesignator, remoteId)
    }

    fun clearStreamTelemetry(streamDesignator: String) {
        clearStreamTelemetryBinding(runtimeStreamTelemetryBindings, streamDesignator)
        StreamFlightActivityRegistry.clearRuntime(streamDesignator)
    }

    fun hasPairedTelemetry(streamDesignator: String): Boolean {
        return pairedDroneSpecStateFor(streamDesignator) != null
    }

    fun managedVideoDroneDesignator(streamDesignator: String): String =
        pairedDroneSpecStateFor(streamDesignator)
            ?.mappedId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: streamDesignator

    private fun notePairedLiveVideoFrame(streamDesignator: String, observedAtMs: Long) {
        val streamInfo = streamInfoByDesignator[streamDesignator] ?: return
        if (streamInfo.isLocalPlayback || streamInfo.state != StreamState.LIVE) return
        val remoteId = resolveStreamTelemetryBinding(
            streamDesignator = streamDesignator,
            telemetryStates = streamTelemetryStates(),
            runtimeStreamBindings = runtimeStreamTelemetryBindings,
            configuredStreamBindings = configuredStreamBindings,
        ).telemetry?.remoteId ?: return
        // A newly rendered frame from a stream that is paired to an active aircraft proves that
        // the controller/drone session is still alive. Preserve the flight and its takeoff
        // reference across a temporary RID receiver gap without making video a position update.
        CaltopoClient.NoteActiveAircraftMessageReceived(remoteId, observedAtMs)
    }

    fun streamPairingWarning(
        streamDesignator: String,
        remoteId: String,
        mappedId: String
    ): StreamTelemetryPairingWarning? {
        return streamTelemetryPairingWarning(
            streamDesignator = streamDesignator,
            selectedTelemetry = StreamTelemetryState(remoteId = remoteId, mappedId = mappedId),
            configuredStreamDesignatorByRemoteId = configuredStreamDesignatorsByRemoteId
        )
    }

    fun streamPairingControlDecision(streamDesignator: String): StreamTelemetryPairingControlDecision {
        return streamTelemetryPairingControlAction(
            streamDesignator = streamDesignator,
            candidateTelemetry = streamTelemetryStates(),
            configuredStreamDesignatorByRemoteId = configuredStreamDesignatorsByRemoteId
        )
    }

    fun closestTelemetryRemoteId(streamDesignator: String): String? =
        closestStreamTelemetryRemoteId(streamDesignator, streamTelemetryStates())

    fun requestAutomaticStreamPairingAfterConfirmation(remoteId: String) {
        val liveUnpairedStreams = streamInfoByDesignator.values
            .filter { info ->
                info.state == StreamState.LIVE &&
                    !info.isLocalPlayback &&
                    isStreamVisible(info) &&
                    !hasPairedTelemetry(info.designator)
            }
            .map { it.designator }
        val target = automaticStreamTelemetryPairingTarget(
            confirmedRemoteId = remoteId,
            candidateTelemetry = streamTelemetryStates(),
            liveUnpairedStreamDesignators = liveUnpairedStreams,
        )
        if (target == null) {
            CTDebug(
                tag,
                "Automatic telemetry pairing prompt skipped after confirmation remoteId=$remoteId " +
                    "activeTelemetry=${_droneStates.size} liveUnpaired=${liveUnpairedStreams.size}",
            )
            automaticStreamPairingRequest = null
            return
        }
        automaticPairingGeneration += 1
        automaticStreamPairingRequest = AutomaticStreamPairingRequest(
            generation = automaticPairingGeneration,
            streamDesignator = target,
            remoteId = remoteId.trim(),
        )
        CTDebug(
            tag,
            "Automatic telemetry pairing prompt queued stream=$target remoteId=$remoteId",
        )
    }

    fun consumeAutomaticStreamPairingRequest(request: AutomaticStreamPairingRequest) {
        if (automaticStreamPairingRequest == request) {
            automaticStreamPairingRequest = null
        }
    }

    private fun pairedDroneSpecStateFor(streamDesignator: String): DroneSpecState? {
        val remoteId = resolveStreamTelemetryBinding(
            streamDesignator = streamDesignator,
            telemetryStates = streamTelemetryStates(),
            runtimeStreamBindings = runtimeStreamTelemetryBindings,
            configuredStreamBindings = configuredStreamBindings
        ).telemetry?.remoteId ?: return null
        return _droneStates.values.firstOrNull { it.remoteId == remoteId }
    }

    internal fun cameraTelemetryDesignatorsFor(remoteId: String, mappedId: String): List<String> =
        buildList {
            runtimeStreamTelemetryBindings
                .filterValues { it == remoteId }
                .keys
                .forEach(::add)
            configuredStreamDesignatorsByRemoteId[remoteId]?.let(::add)
            mappedId.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()

    private fun streamTelemetryStates(): List<StreamTelemetryState> =
        _droneStates.values.map { state ->
            StreamTelemetryState(remoteId = state.remoteId, mappedId = state.mappedId)
        }

    private fun refreshConfiguredStreamBindings() {
        val maps = configuredStreamTelemetryBindingMaps(
            CaltopoClient.GetPersistedDroneSpecs().map { spec ->
                StreamTelemetryState(
                    remoteId = spec.remoteId?.trim().orEmpty(),
                    mappedId = spec.mappedId?.trim().orEmpty()
                )
            }
        )
        configuredStreamBindings = maps.streamDesignatorToRemoteId
        configuredStreamDesignatorsByRemoteId = maps.remoteIdToStreamDesignator
        StreamFlightActivityRegistry.replaceConfigured(maps.streamDesignatorToRemoteId)
    }

    fun renderDelayMsFor(designator: String): Long? {
        return _renderDelayMsByDesignator[designator]
    }

    fun playbackIndicatorStateFor(designator: String): PlaybackIndicatorState? {
        return _playbackIndicatorStateByDesignator[designator]
    }

    fun setCoordinateDisplayFormat(format: CoordinateDisplayFormat) {
        if (_coordinateDisplayFormat.value == format) return
        _coordinateDisplayFormat.value = format
        streamPipPrefs.edit()
            .putString(STREAM_COORDINATE_DISPLAY_FORMAT_KEY, format.storageValue)
            .apply()
        CaltopoClient.SetCoordinateDisplayFormat(format.storageValue)
    }

    internal fun setBaseLayer(baseLayer: org.ncssar.rid2caltopo.video.BaseLayerOption) {
        if (_baseLayer.value == baseLayer) return
        _baseLayer.value = baseLayer
        MapCacheSettings.setBaseLayer(getApplication<Application>().applicationContext, baseLayer)
    }

    fun setLayoutMode(layoutMode: StreamsLayoutMode) {
        _layoutMode.value = layoutMode
    }

    fun setStreamPipEnabled(enabled: Boolean) {
        val next = _streamPipUiState.value.withEnabled(enabled)
        _streamPipUiState.value = next
        streamPipPrefs.edit().putBoolean("enabled", next.enabled).apply()
    }

    fun setStreamPipEditorMode(editorMode: Boolean) {
        _streamPipUiState.value = _streamPipUiState.value.copy(
            editorMode = editorMode && _streamPipUiState.value.enabled
        )
    }

    fun toggleStreamPipEditorModeFromLongPress() {
        _streamPipUiState.value = _streamPipUiState.value.withEditorLongPress()
    }

    fun setStreamPipInsetFraction(insetFraction: Float) {
        val clamped = clampStreamPipInsetFraction(insetFraction)
        _streamPipUiState.value = _streamPipUiState.value.copy(insetFraction = clamped)
        streamPipPrefs.edit().putFloat("inset_fraction", clamped).apply()
    }

    fun setFollowFocusedDroneEnabled(enabled: Boolean) {
        if (_followFocusedDroneEnabled.value == enabled) return
        _followFocusedDroneEnabled.value = enabled
        streamPipPrefs.edit().putBoolean("follow_focused_drone_enabled", enabled).apply()
    }

    fun showMapOnly() {
        _layoutMode.value = StreamsLayoutMode.Map
    }

    fun requestProximityMapFocus(firstLat: Double, firstLng: Double, secondLat: Double, secondLng: Double) {
        _proximityMapFocusTarget.value = ProximityMapFocusTarget(
            requestId = System.currentTimeMillis(),
            firstLat = firstLat,
            firstLng = firstLng,
            secondLat = secondLat,
            secondLng = secondLng
        )
    }

    fun clearProximityMapFocus(requestId: Long) {
        if (_proximityMapFocusTarget.value?.requestId == requestId) {
            _proximityMapFocusTarget.value = null
        }
    }

    fun mapViewportState(): MapViewportState? = persistedMapViewportState

    fun persistMapViewportState(
        center: IGeoPoint?,
        zoom: Double,
        widthPx: Int? = null,
        heightPx: Int? = null,
        bounds: MapViewportBounds? = null
    ) {
        val lat = center?.latitude ?: return
        val lng = center.longitude
        if (!isUsablePersistedMapViewportState(lat, lng, zoom)) return
        persistedMapViewportState = MapViewportState(
            latitude = lat,
            longitude = lng,
            zoom = zoom,
            widthPx = widthPx?.takeIf { it > 0 },
            heightPx = heightPx?.takeIf { it > 0 },
            bounds = bounds?.takeIf { it.isUsable }
        )
    }

    fun onSnapshotCaptured(designator: String, bitmap: Bitmap) {
        val startedAtMs = System.currentTimeMillis()
        fun logSnapshotIfSlow(step: String, elapsedMs: Long) {
            if (elapsedMs < 250L) return
            CaltopoClient.CTWarn(
                tag,
                String.format(
                    Locale.US,
                    "onSnapshotCaptured slow step=%s elapsedMs=%d designator=%s bitmap=%dx%d thread=%s",
                    step,
                    elapsedMs,
                    designator,
                    bitmap.width,
                    bitmap.height,
                    Thread.currentThread().name
                )
            )
        }
        val pairedDroneState = pairedDroneSpecStateFor(designator) ?: droneStates[designator]
        val droneSpec = pairedDroneState?.source

        if (droneSpec == null) {
            CTDebug(tag, "onSnapshotCaptured(${designator}): No associated dronespec.")
            logSnapshotIfSlow("total.noDrone", System.currentTimeMillis() - startedAtMs)
            return
        }

        val telemetryStartedAtMs = System.currentTimeMillis()
        val telemetry = ffmpegProbeService?.telemetrySnapshot(designator)
        logSnapshotIfSlow("telemetrySnapshot", System.currentTimeMillis() - telemetryStartedAtMs)
        val freshRawDjiCamera = StreamCameraTelemetryRegistry.fresh(designator)
        val freshDjiCamera = StreamCameraTelemetryRegistry.freshPositionAfterRidValidation(
            designator = designator,
            anchorLatitudeDeg = droneSpec.lastLat,
            anchorLongitudeDeg = droneSpec.lastLng,
            anchorAltitudeMeters = droneSpec.lastAlt,
            takeoffReportedAltitudeMeters = droneSpec.getImpliedTakeoffAltM(),
            nowMs = System.currentTimeMillis(),
        )
        val validatedSeiPositionAvailable = freshDjiCamera?.latitudeDeg != null &&
            freshDjiCamera.longitudeDeg != null
        val freshRawSeiPositionAvailable = freshRawDjiCamera?.latitudeDeg != null &&
            freshRawDjiCamera.longitudeDeg != null
        val seiPositionAuthorityEstablished = StreamCameraTelemetryRegistry
            .isPositionAuthorityEstablished(designator)
        if (shouldBlockRidClueFallback(
                seiPositionAuthorityEstablished = seiPositionAuthorityEstablished,
                freshRawSeiPositionAvailable = freshRawSeiPositionAvailable,
                validatedSeiPositionAvailable = validatedSeiPositionAvailable,
            )
        ) {
            CaltopoClient.CTWarn(
                tag,
                "onSnapshotCaptured($designator): clue blocked instead of falling back to RID " +
                    "seiAuthority=$seiPositionAuthorityEstablished " +
                    "freshRawSeiPosition=$freshRawSeiPositionAvailable",
            )
            CaltopoClient.ShowToast(
                "Clue unavailable: current video aircraft position is unavailable. " +
                    "Wait for SEI telemetry and try again.",
            )
            return
        }
        val nonDjiTelemetry = telemetry?.takeUnless { it.sourceTag == "dji-sei-245" }
        val clueLat = freshDjiCamera?.latitudeDeg ?: nonDjiTelemetry?.latitude ?: droneSpec.lastLat
        val clueLng = freshDjiCamera?.longitudeDeg ?: nonDjiTelemetry?.longitude ?: droneSpec.lastLng
        // DJI's fixed altitude uses an unknown datum. Anchor its continuous relative-up
        // displacement to the same RID/barometric takeoff MSL value used elsewhere.
        val seiBarometricAltitude = freshDjiCamera?.relativeUpMeters?.let { relativeUp ->
            droneSpec.getImpliedTakeoffAltM()?.plus(relativeUp)
        }
        val clueAlt = seiBarometricAltitude ?: nonDjiTelemetry?.altitudeMeters ?: droneSpec.lastAlt
        val clueTimestamp = freshDjiCamera
            ?.takeIf { it.latitudeDeg != null && it.longitudeDeg != null }
            ?.receivedAtMs
            ?: nonDjiTelemetry?.sourceTimestampUs?.div(1000L)
            ?: droneSpec.mostRecentMsecTimestamp
        val displayState = streamTelemetryDisplayState(
            streamDesignator = designator,
            pairedMappedId = droneSpec.mappedId,
            displayStateByDesignator = altitudeCoordinator.displayStateByDesignator
        )
        val headingSelection = selectClueHeading(
            djiCameraAzimuthDeg = freshDjiCamera?.azimuthDeg,
            djiVideoCourseDeg = freshDjiCamera?.courseDeg,
            telemetry = nonDjiTelemetry,
            derivedHeadingDeg = displayState?.derivedHeadingDeg,
            ridTrackDeg = droneSpec.lastPositionTelemetry?.aircraftTrackDeg,
        )
        val clueBearing = headingSelection.headingDeg
        val clueAglMeters = displayState?.aglFt?.div(METERS_TO_FEET)
        val clueAtoMeters = displayState?.atoFt?.div(METERS_TO_FEET)
        val projectionHeight = selectClueProjectionHeight(
            freshAglMeters = clueAglMeters?.takeUnless { displayState.aglStale },
            atoMeters = clueAtoMeters,
            validatedDjiRelativeUpMeters = freshDjiCamera?.relativeUpMeters
                ?.takeIf { droneSpec.getImpliedTakeoffAltM()?.isFinite() == true },
        )
        val clueGimbalAngle = freshDjiCamera?.tiltDeg
            ?: nonDjiTelemetry?.gimbalPitchDeg
                ?.takeIf { it.isFinite() }
                ?.coerceIn(-90.0, 90.0)
            ?: DEFAULT_CLUE_GIMBAL_ANGLE_DEG
        val projectionStartedAtMs = System.currentTimeMillis()
        val projectedLocation = projectClueLocation(
            droneLat = clueLat,
            droneLng = clueLng,
            droneAlt = clueAlt,
            headingDeg = clueBearing,
            aglMeters = projectionHeight?.meters,
            gimbalAngleDeg = clueGimbalAngle,
        )
        logSnapshotIfSlow("projection", System.currentTimeMillis() - projectionStartedAtMs)
        CTDebug(tag, String.format(
            Locale.US,
            "onSnapshotCaptured(%s): projection inputs source=%s droneLat=%.6f droneLng=%.6f droneAlt=%.1f bearingDeg=%s aglM=%s gimbalDeg=%s projectedLat=%.6f projectedLng=%.6f projectedAlt=%.1f",
            designator,
            when {
                freshDjiCamera?.latitudeDeg != null -> "dji-sei-position"
                nonDjiTelemetry?.latitude != null -> "stream"
                else -> "rid"
            },
            clueLat,
            clueLng,
            clueAlt,
            clueBearing?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clueAglMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            String.format(Locale.US, "%.1f", clueGimbalAngle),
            projectedLocation.lat,
            projectedLocation.lng,
            projectedLocation.alt,
        ))
        val summaryStartedAtMs = System.currentTimeMillis()
        val summary = buildTelemetrySummary(
            designator,
            droneSpec,
            telemetry,
            coordinateDisplayFormat,
            freshDjiCamera,
        )
        logSnapshotIfSlow("buildTelemetrySummary", System.currentTimeMillis() - summaryStartedAtMs)

        _pendingClue.value = PendingClue(
            droneSpec = droneSpec,
            designator = designator,
            droneLat = clueLat,
            droneLng = clueLng,
            droneAlt = clueAlt,
            lat = projectedLocation.lat,
            lng = projectedLocation.lng,
            alt = projectedLocation.alt,
            headingDeg = clueBearing,
            headingSourceLabel = headingSelection.sourceLabel,
            aglMeters = clueAglMeters,
            atoMeters = clueAtoMeters,
            projectionHeightMeters = projectionHeight?.meters,
            projectionHeightSourceLabel = projectionHeight?.sourceLabel,
            gimbalAngleDeg = clueGimbalAngle,
            timestamp = clueTimestamp,
            bitmap = bitmap,
            preview = null,
            title = "",
            description = buildClueDescriptionTemplate(clueTimestamp),
            streamTelemetrySummary = summary,
            aircraftPositionSourceLabel = when {
                freshDjiCamera?.latitudeDeg != null -> "DJI SEI local displacement"
                nonDjiTelemetry?.latitude != null -> "stream"
                else -> "RID"
            },
        )

        requestDemClueProjectionRefresh(designator)

        viewModelScope.launch(Dispatchers.Default) {
            val width = 600
            val height = (width * bitmap.height / bitmap.width)
            val preview = bitmap.scale(width, height)

            withContext(Dispatchers.Main) {
                val clue = _pendingClue.value
                if (clue != null && clue.designator == designator) {
                    _pendingClue.value = clue.copy(
                        bitmap = bitmap,
                        preview = preview,
                    )
                }
            }
        }

        CTDebug(tag, "onSnapshotCaptured(${designator}): clue started for ${bitmap.width}x${bitmap.height} snapshot.")
        logSnapshotIfSlow("total", System.currentTimeMillis() - startedAtMs)
    }

    fun updateClueTitle(title: String) {
        _pendingClue.value = _pendingClue.value?.copy(title = title)
    }

    fun updateClueDescription(description: String) {
        _pendingClue.value = _pendingClue.value?.copy(description = description)
    }

    fun clueSnapshotForTitle(title: String): ClueSnapshotRef? =
        clueSnapshotForTitle(clueSnapshotRefsByTitle, title)

    private fun registerClueSnapshot(
        title: String,
        fullImage: Bitmap?,
        preview: Bitmap?,
        fullImagePath: String? = null,
    ): ClueSnapshotRef? {
        val image = fullImage ?: preview
        val thumbnail = makeClueSnapshotThumbnail(preview ?: fullImage)
        return registerClueSnapshotByTitle(
            snapshots = clueSnapshotRefsByTitle,
            title = title,
            thumbnail = thumbnail,
            fullImage = image,
            fullImagePath = fullImagePath,
        )
    }

    private fun currentLocalClueMapKey(): String {
        val mapId = CaltopoMap.GetMapId().trim()
        if (mapId.isNotEmpty()) return "map:$mapId"
        val mapName = CaltopoMap.GetMapName().trim()
        if (mapName.isNotEmpty()) return "name:$mapName"
        return "unassigned"
    }

    private fun hydrateLocalClues(mapKey: String) {
        localMapMarkers.clear()
        clueSnapshotRefsByTitle.clear()
        localClueStore.recordsForMap(mapKey).forEach { record ->
            localMapMarkers.add(record.toLocalMapMarker())
            registerClueSnapshotByTitle(
                snapshots = clueSnapshotRefsByTitle,
                title = record.title,
                thumbnail = localClueStore.loadThumbnail(record),
                fullImage = null,
                fullImagePath = localClueStore.imageFile(record).absolutePath,
            )
        }
        activeLocalClueMapKey = mapKey
        CTDebug(tag, "Loaded ${localMapMarkers.size} local clue(s) for $mapKey")
    }

    private fun persistClueLocally(clue: PendingClue, title: String, description: String, publish: Boolean): AndroidClueRecord? {
        val bitmap = clue.bitmap ?: run {
            CaltopoClient.ShowToast("Clue image is not ready; local copy was not saved.")
            return null
        }
        return try {
            val record = localClueStore.save(
                mapKey = currentLocalClueMapKey(),
                lat = clue.lat,
                lng = clue.lng,
                alt = clue.alt,
                title = title,
                description = description,
                createdAtMs = clue.timestamp,
                sourceDesignator = clue.designator,
                bitmap = bitmap,
                publishToCaltopo = publish,
            )
            localMapMarkers.add(record.toLocalMapMarker())
            registerClueSnapshot(
                title = record.title,
                fullImage = bitmap,
                preview = clue.preview,
                fullImagePath = localClueStore.imageFile(record).absolutePath,
            )
            record
        } catch (error: Exception) {
            CTError(tag, "Unable to save local clue copy", error)
            CaltopoClient.ShowToast("Clue could not be saved locally; it was not submitted.")
            null
        }
    }

    private fun makeClueSnapshotThumbnail(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val maxSide = 180
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scaleFactor = maxSide.toFloat() / longest.toFloat()
        val width = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
        return bitmap.scale(width, height)
    }

    fun updateClueGimbalAngle(gimbalAngleDeg: Double) {
        _pendingClue.value = _pendingClue.value?.let { clue ->
            val projection = projectClueLocation(
                droneLat = clue.droneLat,
                droneLng = clue.droneLng,
                droneAlt = clue.droneAlt,
                headingDeg = clue.headingDeg,
                aglMeters = clue.projectionHeightMeters,
                gimbalAngleDeg = gimbalAngleDeg,
            )
            CTDebug(tag, String.format(
                Locale.US,
                "updateClueGimbalAngle(): designator=%s bearingDeg=%s aglM=%s gimbalDeg=%.1f projectedLat=%.6f projectedLng=%.6f projectedAlt=%.1f",
                clue.designator,
                clue.headingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                clue.projectionHeightMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                gimbalAngleDeg,
                projection.lat,
                projection.lng,
                projection.alt,
            ))
            clue.copy(
                lat = projection.lat,
                lng = projection.lng,
                alt = projection.alt,
                gimbalAngleDeg = gimbalAngleDeg,
                terrainProjectionApplied = false,
                demSource = null,
                demResolutionMeters = null,
                demSampleStale = false,
            )
        }?.also { requestDemClueProjectionRefresh(it.designator) }
    }

    fun updateClueCameraHeading(headingDeg: Double) {
        val normalizedHeading = normalizeClueHeading(headingDeg) ?: return
        _pendingClue.value = _pendingClue.value?.let { clue ->
            val projection = projectClueLocation(
                droneLat = clue.droneLat,
                droneLng = clue.droneLng,
                droneAlt = clue.droneAlt,
                headingDeg = normalizedHeading,
                aglMeters = clue.projectionHeightMeters,
                gimbalAngleDeg = clue.gimbalAngleDeg,
            )
            clue.copy(
                lat = projection.lat,
                lng = projection.lng,
                alt = projection.alt,
                headingDeg = normalizedHeading,
                headingSourceLabel = "Operator adjusted",
                terrainProjectionApplied = false,
                demSource = null,
                demResolutionMeters = null,
                demSampleStale = false,
            )
        }?.also { requestDemClueProjectionRefresh(it.designator) }
    }

    fun submitClue() {
        val clue = pendingClue ?: return
        if (clue.projectionHeightMeters == null) {
            CaltopoClient.ShowToast("Clue projection needs fresh AGL or a valid relative altitude.")
            CTWarn(tag, "Clue submission blocked: projection height unavailable")
            return
        }
        CTDebug(tag, String.format(
            Locale.US,
            "submitting clue: '%s' for '%s' clueLat=%.6f clueLng=%.6f clueAlt=%.1f droneLat=%.6f droneLng=%.6f droneAlt=%.1f headingDeg=%s aglM=%s atoM=%s gimbalDeg=%.1f",
            clue.title,
            clue.droneSpec.trackLabel(),
            clue.lat,
            clue.lng,
            clue.alt,
            clue.droneLat,
            clue.droneLng,
            clue.droneAlt,
            clue.headingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clue.aglMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clue.atoMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clue.gimbalAngleDeg,
        ))
        val withCaptureSummary = appendTelemetrySummary(
            clue.description,
            buildClueCaptureSummary(clue, coordinateDisplayFormat),
        )
        val finalDescription = appendTelemetrySummary(withCaptureSummary, clue.streamTelemetrySummary)
        if (persistClueLocally(clue, clue.title, finalDescription, publish = true) == null) return
        CaltopoClient.SubmitClue(
            clue.droneSpec,
            clue.bitmap,
            clue.lat,
            clue.lng,
            clue.alt,
            clue.title,
            finalDescription,
            clue.timestamp
        )

        clearPendingClue()
    }

    fun submitLocalMarkerOnly() {
        val clue = pendingClue ?: return
        if (clue.projectionHeightMeters == null) {
            CaltopoClient.ShowToast("Clue projection needs fresh AGL or a valid relative altitude.")
            CTWarn(tag, "Local clue submission blocked: projection height unavailable")
            return
        }
        val markerTitle = clue.title.ifBlank { "Local marker" }
        val markerDescription = appendTelemetrySummary(
            clue.description,
            buildClueCaptureSummary(clue, coordinateDisplayFormat),
        )
        if (persistClueLocally(clue, markerTitle, markerDescription, publish = false) == null) return
        CaltopoClient.ShowToast("Local marker added to R2C Map Pane.")
        CTDebug(tag, String.format(
            Locale.US,
            "submitLocalMarkerOnly: '%s' designator=%s lat=%.6f lng=%.6f alt=%.1f",
            markerTitle,
            clue.designator,
            clue.lat,
            clue.lng,
            clue.alt
        ))
        clearPendingClue()
    }

    fun deleteLocalMapMarker(markerId: String): Boolean {
        val marker = localMapMarkers.firstOrNull { it.id == markerId } ?: return false
        val removed = try {
            localClueStore.delete(markerId)
        } catch (error: Exception) {
            CTError(tag, "deleteLocalMapMarker: unable to delete id=$markerId", error)
            CaltopoClient.ShowToast("Local clue copy could not be deleted.")
            false
        }
        if (removed && removeLocalMapMarkerById(localMapMarkers, markerId)) {
            val replacement = localMapMarkers.lastOrNull { it.title.trim() == marker.title.trim() }
            if (replacement == null) {
                clueSnapshotRefsByTitle.remove(marker.title.trim())
            } else {
                localClueStore.recordsForMap(currentLocalClueMapKey())
                    .firstOrNull { it.id == replacement.id }
                    ?.let { record ->
                        registerClueSnapshotByTitle(
                            snapshots = clueSnapshotRefsByTitle,
                            title = record.title,
                            thumbnail = localClueStore.loadThumbnail(record),
                            fullImage = null,
                            fullImagePath = localClueStore.imageFile(record).absolutePath,
                        )
                    }
            }
            CaltopoClient.ShowToast("Local clue copy deleted; its CalTopo marker remains.")
            CTDebug(tag, "deleteLocalMapMarker: deleted local copy id=$markerId")
        }
        return removed
    }

    fun clearPendingClue() {
        clueProjectionJob?.cancel()
        clueProjectionJob = null
        _pendingClue.value = null
    }

    private fun requestDemClueProjectionRefresh(designator: String) {
        val clue = _pendingClue.value ?: return
        if (clue.designator != designator) return
        clueProjectionJob?.cancel()
        clueProjectionJob = viewModelScope.launch(Dispatchers.Default) {
            val projectionAglMeters = clue.projectionHeightMeters
            val refined = projectClueLocationWithDem(
                demElevationService = altitudeCoordinator.demElevationService,
                droneLat = clue.droneLat,
                droneLng = clue.droneLng,
                droneAlt = clue.droneAlt,
                headingDeg = clue.headingDeg,
                aglMeters = projectionAglMeters,
                gimbalAngleDeg = clue.gimbalAngleDeg,
            )
            withContext(Dispatchers.Main) {
                val current = _pendingClue.value ?: return@withContext
                if (current.designator != clue.designator ||
                    current.timestamp != clue.timestamp ||
                    current.headingDeg != clue.headingDeg ||
                    current.gimbalAngleDeg != clue.gimbalAngleDeg) {
                    return@withContext
                }
                _pendingClue.value = current.copy(
                    lat = refined.lat,
                    lng = refined.lng,
                    alt = refined.alt,
                    aglMeters = projectionAglMeters,
                    aglSourceLabel = current.aglSourceLabel,
                    terrainProjectionApplied = refined.terrainProjectionApplied,
                    demSource = refined.demSource,
                    demResolutionMeters = refined.demResolutionMeters,
                    demSampleStale = refined.demSampleStale,
                )
                CTDebug(tag, String.format(
                    Locale.US,
                    "requestDemClueProjectionRefresh(%s): refined projectedLat=%.6f projectedLng=%.6f projectedAlt=%.1f headingDeg=%s aglM=%s gimbalDeg=%.1f terrainApplied=%s demSource=%s demResolutionM=%s",
                    clue.designator,
                    refined.lat,
                    refined.lng,
                    refined.alt,
                    clue.headingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                    clue.aglMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                    clue.gimbalAngleDeg,
                    refined.terrainProjectionApplied,
                    refined.demSource ?: "none",
                    refined.demResolutionMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown",
                ))
            }
        }
    }

    fun anomalyConfigFor(designator: String): AnomalyConfig {
        return effectiveAnomalyConfigFor(designator)
    }

    fun resolvedAppearanceModeFor(designator: String): AppearanceAnomalyMode {
        return anomalyConfigFor(designator).resolvedAppearanceMode()
    }

    fun setAnomalyDetectorMode(designator: String, mode: AnomalyDetectorMode) {
        updateAnomalyConfig(designator) { current ->
            current.withDetectorMode(mode)
        }
    }

    fun resetAnomalyRealtimeDefaults(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.resetToRealtimeDefaults(
            )
        }
    }

    fun toggleAnomalyAlgorithm(designator: String, algorithm: AnomalyAlgorithm) {
        updateAnomalyConfig(designator) { current ->
            current.toggledAlgorithm(algorithm)
        }
    }

    fun toggleShowHotOverlay(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(showHotOverlay = !current.showHotOverlay)
        }
    }

    fun toggleShowGuideBoxes(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(showGuideBoxes = !current.showGuideBoxes)
        }
    }

    fun toggleShowCandidateBlobs(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(showCandidateBlobs = !current.showCandidateBlobs)
        }
    }

    fun toggleAnomalyTroubleshootingDebug(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(troubleshootingDebug = !current.troubleshootingDebug)
        }
    }

    fun setPersonRelevanceMode(designator: String, mode: PersonRelevanceMode) {
        updateAnomalyConfig(designator) { current ->
            current.copy(personRelevanceMode = mode)
        }
    }

    fun toggleSaliencyEnabled(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(saliencyEnabled = !current.saliencyEnabled)
        }
    }

    fun setAppearanceAnomalySelection(designator: String, selection: AppearanceAnomalySelection) {
        updateAnomalyConfig(designator) { current ->
            current.withAppearanceSelection(selection)
        }
    }

    fun cycleAnomalyFrameStride(designator: String) {
        val frameStrideSteps = listOf(1, 2, 3, 4, 6, 8, 10)
        updateAnomalyConfig(designator) { current ->
            val idx = frameStrideSteps.indexOf(current.frameStride)
            val next = if (idx < 0) frameStrideSteps[0] else frameStrideSteps[(idx + 1) % frameStrideSteps.size]
            current.copy(frameStride = next)
        }
    }

    fun setAnomalyStrideMode(designator: String, strideMode: AnomalyStrideMode) {
        updateAnomalyConfig(designator) { current ->
            current.copy(strideMode = strideMode)
        }
    }

    fun setAnomalyFrameStride(designator: String, frameStride: Int) {
        updateAnomalyConfig(designator) { current ->
            current.copy(frameStride = frameStride.coerceIn(1, 33))
        }
    }

    fun setAnomalyAdaptiveStride(
        designator: String,
        minStrideFrames: Int,
        maxStrideSeconds: Float,
    ) {
        updateAnomalyConfig(designator) { current ->
            current.copy(
                adaptiveMinStrideFrames = minStrideFrames.coerceIn(2, 33),
                adaptiveMaxStrideSeconds = maxStrideSeconds.coerceIn(0.1f, 10.0f),
            )
        }
    }

    fun cycleAnomalyPixelStep(designator: String) {
        val pixelStepSteps = listOf(0, 1, 2, 3, 4)
        updateAnomalyConfig(designator) { current ->
            val idx = pixelStepSteps.indexOf(current.pixelStep.coerceIn(0, 4))
            val next = if (idx < 0) pixelStepSteps[0] else pixelStepSteps[(idx + 1) % pixelStepSteps.size]
            current.copy(pixelStep = next)
        }
    }

    fun setAnomalyPixelStep(designator: String, pixelStep: Int) {
        updateAnomalyConfig(designator) { current ->
            current.copy(pixelStep = pixelStep.coerceIn(0, 8))
        }
    }

    fun cycleAnomalySensitivity(designator: String) {
        val sensitivitySteps = listOf(0.25f, 0.60f, 0.90f)
        updateAnomalyConfig(designator) { current ->
            val currentClamped = current.sensitivity.coerceIn(0f, 1f)
            val idx = sensitivitySteps.indexOfFirst { kotlin.math.abs(it - currentClamped) < 0.01f }
            val next = if (idx < 0) sensitivitySteps[1] else sensitivitySteps[(idx + 1) % sensitivitySteps.size]
            current.copy(sensitivity = next)
        }
    }

    fun setAnomalySensitivity(designator: String, sensitivity: Float) {
        updateAnomalyConfig(designator) { current ->
            current.copy(sensitivity = sensitivity.coerceIn(0f, 1f))
        }
    }

    fun setMotionEvidenceSensitivity(designator: String, sensitivity: Float) {
        updateAnomalyConfig(designator) { current ->
            current.copy(motionEvidenceSensitivity = sensitivity.coerceIn(0f, 1f))
        }
    }

    fun cycleMotionEvidenceSensitivity(designator: String) {
        val sensitivitySteps = listOf(0.25f, 0.60f, 0.90f)
        updateAnomalyConfig(designator) { current ->
            val currentClamped = current.motionEvidenceSensitivity.coerceIn(0f, 1f)
            val idx = sensitivitySteps.indexOfFirst { kotlin.math.abs(it - currentClamped) < 0.01f }
            val next = if (idx < 0) sensitivitySteps[1] else sensitivitySteps[(idx + 1) % sensitivitySteps.size]
            current.copy(motionEvidenceSensitivity = next)
        }
    }

    fun setScanZone(designator: String, scanZone: Float) {
        updateAnomalyConfig(designator) { current ->
            current.copy(scanZone = scanZone.coerceIn(0.5f, 1.0f))
        }
    }

    fun cycleMinHits(designator: String) {
        updateAnomalyConfig(designator) { current ->
            val next = if (current.minHits >= 5) 1 else current.minHits + 1
            current.copy(minHits = next)
        }
    }

    fun setMinHits(designator: String, minHits: Int) {
        updateAnomalyConfig(designator) { current ->
            current.copy(minHits = minHits.coerceIn(1, 10))
        }
    }

    fun setColorTargetCandidateLimit(designator: String, limit: Int) {
        updateAnomalyConfig(designator) { current ->
            current.copy(colorTargetCandidateLimit = limit.coerceIn(1, 4))
        }
    }

    fun setTargetColorFamilyMask(designator: String, mask: Int) {
        updateAnomalyConfig(designator) { current ->
            current.copy(targetColorFamilyMask = mask and TargetColorFamily.allowedMask)
        }
    }

    fun cycleAnomalyThermalPolarity(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(thermalPolarity = current.thermalPolarity.next())
        }
    }

    fun cycleAnomalyRegistrationMode(designator: String) {
        updateAnomalyConfig(designator) { current ->
            val next = when (current.registrationMode) {
                MotionRegistrationMode.Gmv -> MotionRegistrationMode.Affine
                MotionRegistrationMode.Affine -> MotionRegistrationMode.Gmv
            }
            current.copy(registrationMode = next)
        }
    }

    fun cycleAnomalyMovementEstimatorMode(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(movementEstimatorMode = current.movementEstimatorMode.next())
        }
    }

    fun setAnomalyMovementEstimatorMode(designator: String, mode: MovementEstimatorMode) {
        updateAnomalyConfig(designator) { current ->
            current.copy(movementEstimatorMode = mode)
        }
    }

    fun setAnomalyThermalMinDelta(designator: String, thermalMinDelta: Float) {
        updateAnomalyConfig(designator) { current ->
            current.copy(thermalMinDelta = thermalMinDelta.coerceIn(1.0f, 64.0f))
        }
    }

    fun setAnomalySmallTargetScreenFraction(designator: String, fraction: Float) {
        updateAnomalyConfig(designator) { current ->
            current.copy(smallTargetScreenFraction = fraction.coerceIn(0.0015f, 0.03f))
        }
    }

    private fun buildTelemetrySummary(
        designator: String,
        droneSpec: CtDroneSpec,
        telemetry: StreamTelemetrySnapshot?,
        coordinateDisplayFormat: CoordinateDisplayFormat,
        djiCameraSample: StreamCameraTelemetrySample? = null,
    ): String? {
        val ridTelemetry = droneSpec.lastPositionTelemetry
        if (telemetry == null && ridTelemetry == null) return null

        val lines = mutableListOf<String>()
        lines += "Designator: ${streamTelemetrySummaryDesignatorLabel(designator, droneSpec)}"
        lines += "Telemetry:"
        val primaryDronePosition = CoordinateFormatter.format(
            droneSpec.lastLat,
            droneSpec.lastLng,
            coordinateDisplayFormat,
        ).removePrefix("loc:")
        lines += String.format(
            Locale.US,
            "  Drone position (%s): %s alt %.0f'",
            coordinateDisplayFormat.label,
            primaryDronePosition,
            droneSpec.lastAlt * METERS_TO_FEET,
        )
        if (coordinateDisplayFormat != CoordinateDisplayFormat.DECIMAL) {
            lines += String.format(
                Locale.US,
                "  Drone position (Decimal): %.6f, %.6f",
                droneSpec.lastLat,
                droneSpec.lastLng,
            )
        }

        // First three: Heading, AGL, ATO — use values computed by DroneAltitudeCoordinator
        val display = streamTelemetryDisplayState(
            streamDesignator = designator,
            pairedMappedId = droneSpec.mappedId,
            displayStateByDesignator = altitudeCoordinator.displayStateByDesignator
        )
        lines += if (display?.headingDeg != null)
            String.format(Locale.US, "  Heading: %.1f\u00b0", display.headingDeg)
        else
            "  Heading: N/A"
        lines += if (display?.aglFt != null)
            String.format(Locale.US, "  AGL: %.0f'", display.aglFt)
        else
            "  AGL: N/A"
        lines += if (display?.atoFt != null)
            String.format(Locale.US, "  ATO: %.0f'", display.atoFt)
        else
            "  ATO: N/A"

        // Remaining RID telemetry
        ridTelemetry?.let { rid ->
            rid.aircraftAltitudeRateFpm?.let { lines += String.format(Locale.US, "  Vertical rate: %.0f fpm", it) }
            rid.aircraftGsKnots?.let { lines += String.format(Locale.US, "  Ground speed: %.1f kt", it) }
            rid.aircraftTrackDeg?.let { lines += String.format(Locale.US, "  Track: %.1f\u00b0", it) }
        }

        // Stream telemetry
        telemetry?.let {
            telemetry.latestRemoteId?.let { lines += "  RID (stream): $it" }
            if (telemetry.remoteIdCandidates.isNotEmpty()) {
                lines += "  RID candidates: ${telemetry.remoteIdCandidates.joinToString(",")}"
            }
            if (telemetry.sourceTag != "dji-sei-245" &&
                telemetry.latitude != null && telemetry.longitude != null) {
                val altText = telemetry.altitudeMeters?.let {
                    String.format(Locale.US, ", alt=%.0f'", it * METERS_TO_FEET)
                } ?: ""
                lines += String.format(Locale.US, "  Stream position: %.6f, %.6f%s", telemetry.latitude, telemetry.longitude, altText)
            }
            val diagnosticTilt = if (telemetry.sourceTag == "dji-sei-245") {
                djiCameraSample?.rawTiltDeg
            } else {
                telemetry.gimbalPitchDeg
            }
            diagnosticTilt?.let { raw ->
                val calibrated = if (telemetry.sourceTag == "dji-sei-245") {
                    DjiCameraOrientation.calibratedTiltDeg(raw)
                } else null
                lines += calibrated?.let {
                    String.format(Locale.US, "  Camera tilt: %.1f\u00b0 (raw %.1f\u00b0)", it, raw)
                } ?: String.format(Locale.US, "  Gimbal pitch: %.1f\u00b0", raw)
            }
            val diagnosticYaw = if (telemetry.sourceTag == "dji-sei-245") {
                djiCameraSample?.rawCameraAzimuthDeg
            } else {
                telemetry.cameraYawDeg
            }
            diagnosticYaw?.let { raw ->
                lines += if (telemetry.sourceTag == "dji-sei-245") {
                    String.format(Locale.US, "  DJI raw azimuth encoder: %.1f\u00b0", raw)
                } else {
                    String.format(Locale.US, "  Camera yaw: %.1f\u00b0", raw)
                }
            }
            djiCameraSample?.let { sample ->
                lines += String.format(
                    Locale.US,
                    "  Magnetic declination applied: %+.1f\u00b0",
                    sample.magneticDeclinationDeg,
                )
            }
            telemetry.horizontalFovDeg?.let { lines += String.format(Locale.US, "  Horizontal FOV: %.2f\u00b0", it) }
            telemetry.verticalFovDeg?.let { lines += String.format(Locale.US, "  Vertical FOV: %.2f\u00b0", it) }
            telemetry.sourceTag?.let { src ->
                val confidenceText = telemetry.confidence?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"
                lines += "  Telemetry source: $src (confidence=$confidenceText)"
            }
            (djiCameraSample?.sourceTimestampUs ?: telemetry.sourceTimestampUs)
                ?.let { lines += "  Telemetry timestamp(us): $it" }
        }
        djiCameraSample?.let { sample ->
            if (sample.latitudeDeg != null && sample.longitudeDeg != null) {
                lines += String.format(
                    Locale.US,
                    "  DJI SEI aircraft position: %.7f, %.7f relative-up %.1f'",
                    sample.latitudeDeg,
                    sample.longitudeDeg,
                    (sample.relativeUpMeters ?: 0.0) * METERS_TO_FEET,
                )
                lines += "  Clue aircraft position source: DJI SEI local displacement"
            }
            if (sample.referenceLatitudeDeg != null && sample.referenceLongitudeDeg != null) {
                lines += String.format(
                    Locale.US,
                    "  DJI SEI home/reference: %.7f, %.7f datum alt %s",
                    sample.referenceLatitudeDeg,
                    sample.referenceLongitudeDeg,
                    sample.referenceAltitudeMeters?.let { String.format(Locale.US, "%.1f'", it * METERS_TO_FEET) }
                        ?: "N/A",
                )
            }
        }
        return lines.joinToString("\n")
    }

    private fun appendTelemetrySummary(description: String, summary: String?): String {
        if (summary.isNullOrBlank()) return description
        if (description.trim() == summary.trim()) return description
        if (description.contains("Telemetry:")) return description
        val trimmedDescription = description.trimEnd()
        if (trimmedDescription.isBlank()) return summary
        return "$trimmedDescription\n\n$summary"
    }

    private fun shouldUseFfmpegRender(designator: String): Boolean {
        return StreamRenderRouter.useFfmpeg(
            designator = designator,
            liveStreams = streamInfoByDesignator,
            focusedDesignator = _focusedPath.value,
            ffmpegAvailable = ffmpegProbeService != null,
            displayedTileCount = displayedTileCountForCurrentLayout(),
        )
    }

    private fun displayedTileCountForCurrentLayout(): Int {
        if (!_streamsUiActive.value) return 0
        if (_layoutMode.value == StreamsLayoutMode.Map) return 0
        val visibleLiveCount = streamInfoByDesignator.values.count { info ->
            info.state == StreamState.LIVE && isStreamVisible(info)
        }
        if (visibleLiveCount <= 0) return 0
        return if (_focusedPath.value != null) 1 else visibleLiveCount
    }

    private fun syncStreamSessions(streamsMap: Map<String, StreamInfo>) {
        streamInfoByDesignator.clear()
        streamInfoByDesignator.putAll(streamsMap)

        val focused = _focusedPath.value
        if (focused != null && !streamsMap.containsKey(focused)) {
            CTDebug(tag, "Focused stream $focused is no longer present -> clearing focus")
            _focusedPath.value = null
        }

        val liveStreams = streamsMap.values
            .filter { it.state == StreamState.LIVE }
        val liveRevisions = liveStreams.associate { it.designator to it.revision }
        val livePublisherConnIds = liveStreams.associate { it.designator to it.publisherConnId }
        dismissedStreamRevisions.entries.toList().forEach { (designator, dismissedRevision) ->
            val liveRevision = liveRevisions[designator]
            if (liveRevision == null || liveRevision != dismissedRevision) {
                dismissedStreamRevisions.remove(designator)
            }
        }
        val dismissedLiveDesignators = liveStreams
            .filterNot(::isStreamVisible)
            .map { it.designator }
            .toSet()
        val activeLiveStreams = liveStreams.filter(::isStreamVisible)
        val liveDesignators = liveRevisions.keys
        val streamsUiActive = _streamsUiActive.value
        val managedVideoSources = synchronized(streamsUiConsumerLock) {
            managedVideoRequiredSources(
                requestSources = managedVideoRequestSources,
                previewSources = managedVideoPreviewSources,
            )
        }
        val added = activeLiveStreams.map { it.designator }.toSet() - lastLiveRevisions.keys
        val republished = activeLiveStreams
            .filter { info ->
                val previousRevision = lastLiveRevisions[info.designator]
                val revisionChanged = previousRevision != null && info.revision != previousRevision
                val previousPublisherConnId = lastLivePublisherConnIds[info.designator]
                val publisherChanged =
                    previousPublisherConnId != null &&
                    info.publisherConnId != null &&
                    info.publisherConnId != previousPublisherConnId
                revisionChanged || publisherChanged
            }
            .map { it.designator }
            .toSet()
        val focusAfterSync = focusAfterStreamSync(
            currentFocus = _focusedPath.value,
            liveDesignators = liveDesignators,
            newlyVisibleLiveDesignators = added,
        )
        if (focusAfterSync != _focusedPath.value) {
            val previousFocus = _focusedPath.value
            _focusedPath.value = focusAfterSync
            if (previousFocus != null && added.isNotEmpty()) {
                CTInfo(
                    tag,
                    "Live stream set changed while $previousFocus had focus -> clearing focus to show ${activeLiveStreams.size} streams"
                )
            }
        }

        val removed = lastLiveRevisions.keys - liveDesignators
        removed.forEach { designator ->
            CTDebug(tag, "Stream $designator no longer live -> stop render")
            renderRouteByDesignator.remove(designator)
            _playbackIndicatorStateByDesignator.remove(designator)
            ffmpegProbeService?.setRenderEnabled(designator, false)
            ffmpegProbeService?.onStreamStopped(designator)
            streamSessionService.onStreamStopped(designator)
        }

        dismissedLiveDesignators.forEach { designator ->
            renderRouteByDesignator[designator] = false
            _playbackIndicatorStateByDesignator.remove(designator)
            ffmpegProbeService?.setRenderEnabled(designator, false)
            ffmpegProbeService?.onStreamStopped(designator)
            streamSessionService.onStreamStopped(designator)
        }

        activeLiveStreams.forEach { info ->
            val designator = info.designator
            val newlyLive = designator in added
            val previousPublisherConnId = lastLivePublisherConnIds[designator]
            val publisherChanged =
                previousPublisherConnId != null &&
                info.publisherConnId != null &&
                info.publisherConnId != previousPublisherConnId
            val republishDetected = designator in republished
            val keepForManagedVideo = designator in managedVideoSources
            val useFfmpeg = shouldKeepFfmpegRender(
                streamsUiActive = streamsUiActive,
                normalRenderSelected = shouldUseFfmpegRender(designator),
                managedVideoSourceRequired = keepForManagedVideo,
            )
            val wasUsingFfmpeg = renderRouteByDesignator[designator] == true
            if (info.isLocalPlayback && (newlyLive || wasUsingFfmpeg != useFfmpeg)) {
                CTDebug(
                    tag,
                    "Local playback route $designator state=${info.state} focused=${_focusedPath.value} " +
                        "useFfmpeg=$useFfmpeg ffmpegAvailable=${ffmpegProbeService != null} " +
                        "streamsUiActive=$streamsUiActive displayedTiles=${displayedTileCountForCurrentLayout()}"
                )
            }
            ffmpegProbeService?.updateSourcePath(designator, info.sourcePath)
            ffmpegProbeService?.ensureTelemetryProbeSession(designator)
            renderRouteByDesignator[designator] = useFfmpeg
            ffmpegProbeService?.setRenderEnabled(designator, useFfmpeg)
            if (
                shouldEnsureManagedVideoRenderSession(
                    managedVideoSourceRequired = keepForManagedVideo,
                    activeRenderSessionId = ffmpegProbeService?.activeRenderSessionId(designator),
                )
            ) {
                ffmpegProbeService?.ensureManagedVideoRenderSession(designator)
            }
            if (!streamsUiActive && !keepForManagedVideo) {
                ffmpegProbeService?.suspendRender(designator)
                streamSessionService.onStreamStopped(designator)
                CTDebug(tag, "Stream $designator live -> streams UI inactive, retaining telemetry probe")
                return@forEach
            }
            if (useFfmpeg) {
                _playbackIndicatorStateByDesignator.remove(designator)
                streamSessionService.onStreamStopped(designator)
                if (republishDetected) {
                    if (publisherChanged) {
                        CTDebug(
                            tag,
                            "Stream $designator publisherConn=${previousPublisherConnId} -> ${info.publisherConnId} -> evaluating FFmpeg render session"
                        )
                    } else {
                        CTDebug(tag, "Stream $designator live revision=${info.revision} -> tolerating controller republish")
                    }
                    ffmpegProbeService?.onStreamRepublished(
                        designator,
                        publisherChanged = publisherChanged,
                        previousPublisherConnId = previousPublisherConnId,
                        publisherConnId = info.publisherConnId,
                    )
                    CTDebug(tag, "Stream $designator live -> using FFmpeg render path")
                } else if (newlyLive || !wasUsingFfmpeg) {
                    ffmpegProbeService?.onStreamBecameLive(designator)
                    CTDebug(tag, "Stream $designator live -> using FFmpeg render path")
                }
            } else {
                if (wasUsingFfmpeg) {
                    ffmpegProbeService?.suspendRender(designator)
                }
                // If another stream is focused, suspend this one's ExoPlayer to save CPU.
                // Re-read _focusedPath.value here in case it was cleared above (e.g. a new
                // off-focus stream arriving auto-dismissed focus and returned to grid view).
                val currentFocus = _focusedPath.value
                if (currentFocus != null && currentFocus != designator) {
                    if (streamSessionService.playerFor(designator) != null) {
                        _playbackIndicatorStateByDesignator.remove(designator)
                        streamSessionService.onStreamStopped(designator)
                        CTDebug(tag, "Stream $designator -> ExoPlayer suspended (focus is on $currentFocus)")
                    }
                } else {
                    if (republishDetected) {
                        streamSessionService.onStreamRepublished(designator)
                        CTDebug(tag, "Stream $designator live -> using ExoPlayer render path (republished)")
                    } else if (newlyLive || wasUsingFfmpeg || streamSessionService.playerFor(designator) == null) {
                        streamSessionService.onStreamBecameLive(designator)
                        CTDebug(tag, "Stream $designator live -> using ExoPlayer render path")
                    }
                }
            }
        }

        applyFocusedAnomalyPolicy(liveDesignators)
        lastLiveRevisions = liveRevisions
        lastLivePublisherConnIds = livePublisherConnIds
    }

    fun clearFocus() {
        _focusedPath.value = null
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
    }

    fun dismissFocusedStream() {
        val designator = _focusedPath.value ?: return
        if (_localPlaybackEntries.value.containsKey(designator)) {
            closeStream(designator)
            return
        }
        val info = streams.value[designator] ?: return
        dismissedStreamRevisions[designator] = info.revision
        CTDebug(tag, "dismissFocusedStream(): hiding $designator at revision=${info.revision}")
        _focusedPath.value = null
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
        CaltopoClient.ShowToast("Closed $designator until it republishes.")
    }

    private fun updateAnomalyConfig(
        designator: String,
        reducer: (AnomalyConfig) -> AnomalyConfig,
    ) {
        val current = _anomalyConfigByDesignator[designator] ?: defaultAnomalyConfig
        val updated = reducer(current)
        _anomalyConfigByDesignator[designator] = updated
        defaultAnomalyConfig = updated
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
    }

    private fun effectiveAnomalyConfigFor(designator: String): AnomalyConfig {
        return _anomalyConfigByDesignator[designator] ?: defaultAnomalyConfig
    }

    private fun currentResyncSnapshot(): Map<String, StreamInfo> {
        val latestDirectSnapshot = buildMap<String, StreamInfo> {
            putAll(StreamRegistry.streams.value)
            putAll(_localPlaybackEntries.value)
        }
        return chooseResyncSnapshot(
            lastSyncedStreams = streamInfoByDesignator.toMap(),
            latestFlowValue = latestDirectSnapshot,
        )
    }

    private fun startProcessLoadMonitor() {
        viewModelScope.launch {
            var lastObservedAtMs = 0L
            var lastProcessCpuMs = 0L
            var lastMainThreadCpuNs = 0L
            var lastMediaMtxPid = 0
            var lastMediaMtxCpuTicks = 0L
            var smoothedProcessCpuFraction = 0.0
            var smoothedMainThreadCpuFraction = 0.0
            var smoothedMediaMtxCpuFraction = 0.0
            val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            while (true) {
                kotlinx.coroutines.delay(processLoadSampleIntervalMs)
                val nowMs = System.currentTimeMillis()
                val processCpuMs = Process.getElapsedCpuTime()
                val mainThreadCpuNs = Debug.threadCpuTimeNanos()
                val mediaMtxPid = if (MediaMTXService.IsRunning()) MediaMTXService.findNativeServerPid() else 0
                val mediaMtxCpuTicks = if (mediaMtxPid > 0) readProcCpuTicks(mediaMtxPid) else null
                if (lastObservedAtMs != 0L) {
                    val windowMs = nowMs - lastObservedAtMs
                    if (windowMs > 0L) {
                        val rawProcessCpuFraction =
                            ((processCpuMs - lastProcessCpuMs).toDouble() / windowMs.toDouble() / cpuCount.toDouble())
                                .coerceIn(0.0, 1.0)
                        val rawMainThreadCpuFraction =
                            (((mainThreadCpuNs - lastMainThreadCpuNs) / 1_000_000.0) / windowMs.toDouble())
                                .coerceIn(0.0, 1.0)
                        smoothedProcessCpuFraction = if (smoothedProcessCpuFraction == 0.0) {
                            rawProcessCpuFraction
                        } else {
                            (smoothedProcessCpuFraction * 0.7) + (rawProcessCpuFraction * 0.3)
                        }
                        smoothedMainThreadCpuFraction = if (smoothedMainThreadCpuFraction == 0.0) {
                            rawMainThreadCpuFraction
                        } else {
                            (smoothedMainThreadCpuFraction * 0.7) + (rawMainThreadCpuFraction * 0.3)
                        }
                        val rawMediaMtxCpuFraction = if (mediaMtxPid > 0 &&
                            mediaMtxPid == lastMediaMtxPid &&
                            mediaMtxCpuTicks != null &&
                            lastMediaMtxCpuTicks > 0L) {
                            (((mediaMtxCpuTicks - lastMediaMtxCpuTicks).toDouble() * 10.0) /
                                windowMs.toDouble() / cpuCount.toDouble()).coerceIn(0.0, 1.0)
                        } else {
                            0.0
                        }
                        smoothedMediaMtxCpuFraction = if (smoothedMediaMtxCpuFraction == 0.0) {
                            rawMediaMtxCpuFraction
                        } else {
                            (smoothedMediaMtxCpuFraction * 0.7) + (rawMediaMtxCpuFraction * 0.3)
                        }
                        val runtime = Runtime.getRuntime()
                        val javaHeapUsedMb =
                            ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).coerceAtLeast(0L)
                        val javaHeapMaxMb = (runtime.maxMemory() / (1024L * 1024L)).coerceAtLeast(1L)
                        val nativeHeapUsedMb =
                            (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).coerceAtLeast(0L)
                        val liveStreamCount = streamInfoByDesignator.values.count { it.state == StreamState.LIVE }
                        val ffmpegStreamCount = streamInfoByDesignator.values.count { info ->
                            info.state == StreamState.LIVE && shouldUseFfmpegRender(info.designator)
                        }
                        val anomalyEnabledCount = streamInfoByDesignator.values.count { info ->
                            effectiveAnomalyConfigFor(info.designator).enabled
                        }
                        val thermalStatusLabel = currentThermalStatusLabel()
                        val anomalyHeadroomLabel = estimateAnomalyHeadroom(
                            processCpuFraction = smoothedProcessCpuFraction,
                            mainThreadCpuFraction = smoothedMainThreadCpuFraction,
                            thermalStatusLabel = thermalStatusLabel,
                            liveStreamCount = liveStreamCount,
                            ffmpegStreamCount = ffmpegStreamCount,
                            anomalyEnabledCount = anomalyEnabledCount,
                        )
                        latestProcessLoadSnapshot = ProcessLoadSnapshot(
                            observedAtMs = nowMs,
                            sampleWindowMs = windowMs,
                            processCpuFraction = smoothedProcessCpuFraction,
                            mainThreadCpuFraction = smoothedMainThreadCpuFraction,
                            javaHeapUsedMb = javaHeapUsedMb,
                            javaHeapMaxMb = javaHeapMaxMb,
                            nativeHeapUsedMb = nativeHeapUsedMb,
                            mediaMtxPid = mediaMtxPid,
                            mediaMtxCpuFraction = smoothedMediaMtxCpuFraction,
                            liveStreamCount = liveStreamCount,
                            ffmpegStreamCount = ffmpegStreamCount,
                            anomalyEnabledCount = anomalyEnabledCount,
                            thermalStatusLabel = thermalStatusLabel,
                            anomalyHeadroomLabel = anomalyHeadroomLabel,
                        )
                        if (liveStreamCount > 0 && nowMs - lastProcessLoadLogAtMs >= processLoadLogIntervalMs) {
                            lastProcessLoadLogAtMs = nowMs
                            CTDebug(
                                tag,
                                String.format(
                                    Locale.US,
                                    "Device load: cpu=%d%% mediamtx=%d%% ui=%d%% java=%d/%dMB native=%dMB live=%d ffmpeg=%d anomaly=%d thermal=%s headroom=%s windowMs=%d",
                                    (smoothedProcessCpuFraction * 100.0).toInt(),
                                    (smoothedMediaMtxCpuFraction * 100.0).toInt(),
                                    (smoothedMainThreadCpuFraction * 100.0).toInt(),
                                    javaHeapUsedMb,
                                    javaHeapMaxMb,
                                    nativeHeapUsedMb,
                                    liveStreamCount,
                                    ffmpegStreamCount,
                                    anomalyEnabledCount,
                                    thermalStatusLabel,
                                    anomalyHeadroomLabel,
                                    windowMs,
                                )
                            )
                        }
                    }
                }
                lastObservedAtMs = nowMs
                lastProcessCpuMs = processCpuMs
                lastMainThreadCpuNs = mainThreadCpuNs
                lastMediaMtxPid = mediaMtxPid
                lastMediaMtxCpuTicks = mediaMtxCpuTicks ?: 0L
            }
        }
    }

    private fun admissionGuardDecision(
        state: StreamAdmissionState,
        designator: String,
        targetState: StreamState,
    ): StreamAdmissionGuardResult {
        if (targetState != StreamState.CONNECTING && targetState != StreamState.LIVE) {
            return StreamAdmissionGuardResult(allow = true)
        }
        val activeCount = state.active.size
        val projectedCount = activeCount + 1
        val snapshot = latestProcessLoadSnapshot ?: return StreamAdmissionGuardResult(allow = true)
        val sampleAgeMs = System.currentTimeMillis() - snapshot.observedAtMs
        if (sampleAgeMs > processLoadSampleIntervalMs * 2) {
            return StreamAdmissionGuardResult(allow = true)
        }
        if (projectedCount >= 3 &&
            (snapshot.processCpuFraction >= hotProcessCpuFractionForThirdOrFourthStream ||
                snapshot.mainThreadCpuFraction >= hotMainThreadCpuFractionForThirdOrFourthStream)
        ) {
            val processPct = (snapshot.processCpuFraction * 100.0).toInt()
            return StreamAdmissionGuardResult(
                allow = false,
                reason = "load_guard",
                toastMessage = "Rejected stream $designator (device load ${processPct}% too high for stream $projectedCount).",
            )
        }
        if (projectedCount == 2 && snapshot.processCpuFraction >= hotProcessCpuFractionForSecondStream) {
            val processPct = (snapshot.processCpuFraction * 100.0).toInt()
            return StreamAdmissionGuardResult(
                allow = false,
                reason = "load_guard",
                toastMessage = "Rejected stream $designator (device load ${processPct}% too high for a second stream).",
            )
        }
        return StreamAdmissionGuardResult(allow = true)
    }

    fun deviceLoadOverlayText(): String? {
        val snapshot = latestProcessLoadSnapshot ?: return null
        val ageMs = System.currentTimeMillis() - snapshot.observedAtMs
        if (ageMs > processLoadSampleIntervalMs * 3) return null
        return String.format(
            Locale.US,
            "CPU %d%% MX %d%% UI %d%% MEM %d/%dM N %dM S%d F%d A%d T%s H%s",
            (snapshot.processCpuFraction * 100.0).toInt(),
            (snapshot.mediaMtxCpuFraction * 100.0).toInt(),
            (snapshot.mainThreadCpuFraction * 100.0).toInt(),
            snapshot.javaHeapUsedMb,
            snapshot.javaHeapMaxMb,
            snapshot.nativeHeapUsedMb,
            snapshot.liveStreamCount,
            snapshot.ffmpegStreamCount,
            snapshot.anomalyEnabledCount,
            snapshot.thermalStatusLabel,
            snapshot.anomalyHeadroomLabel,
        )
    }

    fun streamPerformanceOverlayText(designator: String): String? {
        val runtime = runtimeSnapshotFor(designator) ?: return null
        val anomalyText = runtime.anomalyAvgProcessMs?.let {
            String.format(Locale.US, " ANO %.1fms", it)
        } ?: ""
        val realtimeText = realtimeStatus(runtime.localPlaybackRealtimeFactor)?.let {
            val shortDescriptor = when (it.descriptor) {
                "faster than realtime" -> "fast"
                "slower than realtime" -> "slow"
                else -> "even"
            }
            String.format(Locale.US, " RT %.2fx %s", it.factor, shortDescriptor)
        } ?: ""
        return String.format(
            Locale.US,
                "FPS d/r %.1f/%.1f FR %d/%d AGE %dms%s%s%s",
            runtime.avgDecodedFps,
            runtime.avgRenderedFps,
            runtime.decodedFrameCount,
            runtime.renderedFrameCount,
            runtime.lastFrameAgeMs,
            runtime.renderDelayMs?.let { " LAT ${it}ms" } ?: "",
            anomalyText,
            realtimeText,
        )
    }

    private fun currentRendererLabel(designator: String): String =
        if (renderRouteByDesignator[designator] == true) "FFmpeg" else "ExoPlayer"

    fun performancePanelText(focusedDesignator: String?): String {
        val lines = mutableListOf<String>()
        val snapshot = latestProcessLoadSnapshot
        if (snapshot == null) {
            lines += "Performance sample: not available yet."
        } else {
            val ageMs = System.currentTimeMillis() - snapshot.observedAtMs
            lines += "Device load"
            lines += String.format(Locale.US, "  App CPU load: %d%% of available core capacity", (snapshot.processCpuFraction * 100.0).toInt())
            lines += if (snapshot.mediaMtxPid > 0) {
                String.format(
                    Locale.US,
                    "  MediaMTX CPU load: %d%% of available core capacity (pid %d)",
                    (snapshot.mediaMtxCpuFraction * 100.0).toInt(),
                    snapshot.mediaMtxPid,
                )
            } else {
                "  MediaMTX CPU load: not available"
            }
            lines += String.format(Locale.US, "  Main-thread CPU load: %d%%", (snapshot.mainThreadCpuFraction * 100.0).toInt())
            lines += String.format(Locale.US, "  Java heap: %d MB used of %d MB max", snapshot.javaHeapUsedMb, snapshot.javaHeapMaxMb)
            lines += String.format(Locale.US, "  Native heap: %d MB", snapshot.nativeHeapUsedMb)
            lines += String.format(Locale.US, "  Live streams: %d", snapshot.liveStreamCount)
            lines += String.format(Locale.US, "  FFmpeg streams: %d", snapshot.ffmpegStreamCount)
            lines += String.format(Locale.US, "  Anomaly-enabled streams: %d", snapshot.anomalyEnabledCount)
            lines += "  Thermal status: ${snapshot.thermalStatusLabel}"
            lines += "  Estimated anomaly headroom: ${snapshot.anomalyHeadroomLabel}"
            lines += String.format(Locale.US, "  Sample age: %d ms over a %d ms window", ageMs, snapshot.sampleWindowMs)
        }

        if (!focusedDesignator.isNullOrBlank()) {
            lines += ""
            lines += "Focused stream: $focusedDesignator"
            val runtime = runtimeSnapshotFor(focusedDesignator)
            if (runtime == null) {
                lines += "  Stream runtime stats: not available for current renderer (${currentRendererLabel(focusedDesignator)})."
            } else {
                lines += String.format(Locale.US, "  Average decoded FPS: %.1f", runtime.avgDecodedFps)
                lines += String.format(Locale.US, "  Average rendered FPS: %.1f", runtime.avgRenderedFps)
                lines += String.format(Locale.US, "  Decoded frames: %d", runtime.decodedFrameCount)
                lines += String.format(Locale.US, "  Rendered frames: %d", runtime.renderedFrameCount)
                lines += String.format(Locale.US, "  Last frame age: %d ms", runtime.lastFrameAgeMs)
                runtime.renderDelayMs?.let {
                    lines += String.format(Locale.US, "  Render latency: %d ms", it)
                }
                runtime.anomalyAvgProcessMs?.let {
                    lines += String.format(
                        Locale.US,
                        "  Anomaly processing: avg %.2f ms max %.2f ms last %.2f ms",
                        it,
                        runtime.anomalyMaxProcessMs ?: 0.0,
                        runtime.anomalyLastProcessMs ?: 0.0,
                    )
                    lines += String.format(
                        Locale.US,
                        "  Anomaly analyzed frames: %d (%d annotated)",
                        runtime.anomalyAnalyzedFrameCount,
                        runtime.anomalyAnnotatedFrameCount,
                    )
                }
                realtimeStatus(runtime.localPlaybackRealtimeFactor)?.let { status ->
                    lines += String.format(
                        Locale.US,
                        "  Playback realtime: %.2fx realtime recent (%s)",
                        status.factor,
                        status.descriptor,
                    )
                }
                val mediaSpanMs = runtime.localPlaybackMediaSpanMs
                val wallSpanMs = runtime.localPlaybackWallSpanMs
                if (mediaSpanMs != null && wallSpanMs != null) {
                    val sessionRealtimeStatus = realtimeStatus(runtime.localPlaybackSessionRealtimeFactor)
                    lines += if (sessionRealtimeStatus != null) {
                        String.format(
                            Locale.US,
                            "  Playback span: %.1f s media over %.1f s wall time (session avg %.2fx)",
                            mediaSpanMs / 1000.0,
                            wallSpanMs / 1000.0,
                            sessionRealtimeStatus.factor,
                        )
                    } else {
                        String.format(
                            Locale.US,
                            "  Playback span: %.1f s media over %.1f s wall time",
                            mediaSpanMs / 1000.0,
                            wallSpanMs / 1000.0,
                        )
                    }
                }
                runtime.anomalyDebugSummary?.takeIf { it.isNotBlank() }?.let {
                    lines += "  Anomaly debug: $it"
                }
                val adRuntimeModeLabel =
                    when (runtime.adRuntimeMode) {
                        2 -> "threaded"
                        1 -> "inline"
                        else -> "bypassed"
                    }
                lines += "  AD mode: $adRuntimeModeLabel"
                lines += String.format(
                    Locale.US,
                    "  AD queue: depth %d max %d forwarded %d overload-disables %d",
                    runtime.adInputQueueDepth,
                    runtime.adInputQueueDepthMax,
                    runtime.adForwardedWithoutAnalysisCount,
                    runtime.adFullQueueDisableCount,
                )
                lines += String.format(
                    Locale.US,
                    "  AD render mix: analyzed %d bypassed %d",
                    runtime.adAnalyzedRenderedFrameCount,
                    runtime.adBypassedRenderedFrameCount,
                )
                if (anomalyConfigFor(focusedDesignator).troubleshootingDebug) {
                    lines += String.format(
                        Locale.US,
                        "  AD worker: processed %d annotated %d overlay-enqueued %d",
                        runtime.adWorkerProcessedFrameCount,
                        runtime.adWorkerAnnotatedFrameCount,
                        runtime.adWorkerOverlayEnqueuedCount,
                    )
                }
                lines += String.format(Locale.US, "  Idle poll count: %d", runtime.idlePollCount)
            }
        }

        lines += ""
        lines += "Headroom guide"
        lines += "  ok: device appears to have room for anomaly work."
        lines += "  limit: usable, but additional streams or anomaly load may cause lag."
        lines += "  hot: thermal or CPU pressure is high; anomaly work may be risky."
        return lines.joinToString("\n")
    }

    private fun currentThermalStatusLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        val pm = getApplication<Application>().getSystemService(PowerManager::class.java) ?: return "n/a"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "mod"
            PowerManager.THERMAL_STATUS_SEVERE -> "sev"
            PowerManager.THERMAL_STATUS_CRITICAL -> "crit"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emrg"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "sdwn"
            else -> "unk"
        }
    }

    private fun estimateAnomalyHeadroom(
        processCpuFraction: Double,
        mainThreadCpuFraction: Double,
        thermalStatusLabel: String,
        liveStreamCount: Int,
        ffmpegStreamCount: Int,
        anomalyEnabledCount: Int,
    ): String {
        if (thermalStatusLabel == "sev" || thermalStatusLabel == "crit" ||
            thermalStatusLabel == "emrg" || thermalStatusLabel == "sdwn") {
            return "hot"
        }
        if (processCpuFraction >= 0.85 || mainThreadCpuFraction >= 0.40) {
            return "hot"
        }
        if (processCpuFraction >= 0.65 || mainThreadCpuFraction >= 0.25 ||
            ffmpegStreamCount >= 2 || anomalyEnabledCount >= 1 || liveStreamCount >= 3 ||
            thermalStatusLabel == "mod") {
            return "limit"
        }
        return "ok"
    }

    private fun readProcCpuTicks(pid: Int): Long? {
        if (pid <= 0) return null
        return try {
            val stat = java.io.File("/proc/$pid/stat").readText()
            val closingParen = stat.lastIndexOf(')')
            if (closingParen < 0 || closingParen + 2 >= stat.length) return null
            val parts = stat.substring(closingParen + 2).trim().split(Regex("\\s+"))
            if (parts.size <= 12) return null
            val utimeTicks = parts[11].toLongOrNull() ?: return null
            val stimeTicks = parts[12].toLongOrNull() ?: return null
            utimeTicks + stimeTicks
        } catch (_: Exception) {
            null
        }
    }

    private fun applyFocusedAnomalyPolicy(liveDesignators: Set<String>) {
        val focused = _focusedPath.value
        val thermalPause = currentThermalStatusLabel() in setOf("sev", "crit", "emrg", "sdwn")
        val desiredDesignators = liveDesignators + _localPlaybackEntries.value.keys
        val updates = mutableListOf<AnomalyPolicyUpdate>()
        desiredDesignators.forEach { designator ->
            val config = effectiveAnomalyConfigFor(designator)
            val isLocalPlayback = _localPlaybackEntries.value.containsKey(designator)
            val enableForDesignator = StreamRenderRouter.shouldEnableNativeAnomaly(
                designator = designator,
                focusedDesignator = focused,
                isLocalPlayback = isLocalPlayback,
                configEnabled = config.enabled,
            )
            val update = AnomalyPolicyUpdate(
                designator = designator,
                thermalPaused = enableForDesignator && thermalPause,
                personRelevanceMode = config.personRelevanceMode,
                config = config.toNativeConfig(
                    enabledOverride = enableForDesignator
                )
            )
            if (anomalyPolicyChanged(lastAppliedAnomalyPolicyByDesignator[designator], update)) {
                updates += update
                lastAppliedAnomalyPolicyByDesignator[designator] = update
            }
        }
        lastAppliedAnomalyPolicyByDesignator.keys.retainAll(desiredDesignators)
        if (updates.isEmpty()) return
        anomalyPolicyApplyQueue.submit {
            // The policy is cached above, so every queued value must reach the native service.
            // Local playback startup can resync several times in quick succession.
            val probeService = ffmpegProbeService ?: return@submit
            updates.forEach { update ->
                probeService.setAnomalyThermalPaused(
                    designator = update.designator,
                    paused = update.thermalPaused
                )
                probeService.setAnomalyPolicy(
                    designator = update.designator,
                    config = update.config,
                    personRelevanceMode = update.personRelevanceMode,
                )
            }
        }
    }

    private fun stageCapturedVideoForPlayback(designator: String, sourceUri: Uri, displayName: String): Uri {
        val context = getApplication<Application>().applicationContext
        val startedAtMs = System.currentTimeMillis()
        val sanitizedBase = displayName
            .substringBeforeLast('.', displayName)
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "captured_video" }
        val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "mp4"
        val targetFile = File(
            context.cacheDir,
            "${sanitizedBase}_${designator.hashCode().toUInt().toString(16)}.$extension"
        )
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output, LOCAL_PLAYBACK_STAGE_BUFFER_BYTES)
            }
        } ?: error("Unable to read selected video")
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val sizeMb = targetFile.length().toDouble() / (1024.0 * 1024.0)
        CTDebug(
            tag,
            String.format(
                Locale.US,
                "Staged captured video for %s: %.1f MB in %d ms uri=%s",
                designator,
                sizeMb,
                elapsedMs,
                targetFile.toURI(),
            )
        )
        return Uri.fromFile(targetFile)
    }

    private fun annotationSidecarPathForPlaybackUri(playbackUri: Uri): String? {
        val playbackPath = playbackUri.path ?: return null
        val playbackFile = File(playbackPath)
        val baseName = playbackFile.name.substringBeforeLast('.', playbackFile.name)
        return File(playbackFile.parentFile, "$baseName.review.json").absolutePath
    }

    private fun loadLocalPlaybackReviewFromDisk(streamInfo: StreamInfo): LocalPlaybackReviewFile? {
        val sidecarPath = streamInfo.annotationSidecarPath ?: return null
        val reviewFile = File(sidecarPath)
        if (!reviewFile.exists()) return null
        return runCatching {
            localPlaybackReviewFromJson(JSONObject(reviewFile.readText()))
        }.getOrElse { error ->
            CTDebug(tag, "Local playback review read failed for $sidecarPath: ${error.message}")
            null
        }
    }

    private fun writeLocalPlaybackReviewToDisk(review: LocalPlaybackReviewFile, sidecarPath: String) {
        runCatching {
            val reviewFile = File(sidecarPath)
            reviewFile.parentFile?.mkdirs()
            reviewFile.writeText(review.toJson().toString(2))
        }.onFailure { error ->
            CTDebug(tag, "Local playback review write failed for $sidecarPath: ${error.message}")
        }
    }

    private fun queueLocalPlaybackReviewExportIfNeeded(streamInfo: StreamInfo) {
        val review = localPlaybackReviewByDesignator[streamInfo.designator] ?: return
        if (streamInfo.designator !in dirtyLocalPlaybackReviews) return
        if (review.frames.isEmpty()) return
        val baseName = streamInfo.designator.substringBeforeLast('.', streamInfo.designator)
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "captured_video" }
        pendingLocalPlaybackReviewExport = PendingLocalPlaybackReviewExport(
            designator = streamInfo.designator,
            suggestedFileName = "$baseName.review.json",
            jsonText = review.copy(updatedAtMs = System.currentTimeMillis()).toJson().toString(2),
        )
    }

    private fun formatPlaybackTimestampUs(timestampUs: Long): String {
        val totalMs = (timestampUs / 1000L).coerceAtLeast(0L)
        val minutes = totalMs / 60_000L
        val seconds = (totalMs % 60_000L) / 1000L
        val millis = totalMs % 1000L
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    init {
        defaultAnomalyConfig = AnomalyPrefs.loadSessionDefaults(application.applicationContext)
        refreshConfiguredStreamBindings()
        CaltopoMap.AddMapStatusListener(this)
        if (CaltopoMap.GetMapId().isNotBlank()) {
            _mapName.value = CaltopoMap.GetMapName()
            hydrateLocalClues(currentLocalClueMapKey())
        }
        CaltopoClient.AddDroneSpecsChangedListener(this)
        StreamRegistry.setAdmissionGuard(::admissionGuardDecision)
        startProcessLoadMonitor()

        viewModelScope.launch {
            combine(StreamRegistry.streams, _localPlaybackEntries) { liveStreams, localPlaybackEntries ->
                buildMap<String, StreamInfo> {
                    putAll(liveStreams)
                    putAll(localPlaybackEntries)
                }
            }.collect { map ->
                syncStreamSessions(map)
            }
        }
        ffmpegProbeService?.let { service ->
            viewModelScope.launch {
                service.renderDelayMsByDesignatorFlow.collect { delays ->
                    _renderDelayMsByDesignator.clear()
                    _renderDelayMsByDesignator.putAll(delays)
                }
            }
        }
    }

}
