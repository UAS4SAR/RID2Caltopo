package org.ncssar.rid2caltopo.video

import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.PilotDisplayPreference
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

internal const val MAP_PANE_TAG = "SplitMapPane"

internal enum class MapPanePresentationMode {
    Full,
    Inset
}

internal const val MAP_PANE_VERBOSE_LOGS = false
internal const val INSET_FOLLOW_INTERVAL_MS = 500L
internal const val INSET_FOLLOW_MIN_MOVE_METERS = 1.0
internal const val LOCAL_DEVICE_SYMBOL = "radiotower"
internal const val LOCAL_DEVICE_COLOR_HEALTHY = "2E7D32"
internal const val LOCAL_DEVICE_COLOR_STARTING = "F9A825"
internal const val LOCAL_DEVICE_COLOR_DEGRADED = "F9A825"
internal const val LOCAL_DEVICE_COLOR_UNCONFIGURED = "1976D2"
internal const val ICON_LATENCY_TAG = "RidIconLatency"
internal const val AGL_LIMIT_FT = 200.0
internal const val RANGE_LIMIT_FT = 5280.0
internal const val AGL_ICON_NEAR_DELTA_FT = 20.0
internal const val FT_TO_METERS = 0.3048
internal const val NEAR_LIMIT_RATIO = 0.90
internal const val NEAR_ALERT_COOLDOWN_MS = 30_000L
internal const val STARTUP_MY_LOCATION_FRESH_MS = 60_000L
internal const val STARTUP_MY_LOCATION_WAIT_MS = 20_000L
internal const val STARTUP_MY_LOCATION_MIN_ZOOM = 14.0
internal const val OVER_ALERT_COOLDOWN_MS = 12_000L
internal const val METERS_TO_FEET = 3.28084
internal const val DEM_RETRY_INTERVAL_MS = 2_000L
internal const val PREDICTIVE_HEAD_MIN_AGE_MS = 600L
internal const val PREDICTIVE_HEAD_MAX_AGE_MS = 5_000L
internal const val PREDICTIVE_HEAD_MAX_LOOKAHEAD_MS = 2_000L
internal const val PREDICTIVE_HEAD_MAX_SPEED_MPS = 45.0
internal const val PREDICTIVE_HEAD_MAX_VERTICAL_SPEED_MPS = 15.0
internal const val PREDICTIVE_HEAD_MAX_DISTANCE_M = 90.0
internal const val SIGNIFICANT_HEADING_MOVE_M = 16.0 * FT_TO_METERS
internal const val LABEL_MAX_ABS_FEET = 1000.0
internal const val DEFAULT_CAMERA_FOV_WIDTH_DEG = 80.0
internal const val OSM_MAX_ZOOM = 19.0
internal const val MAP_DISPLAY_MAX_ZOOM = 22.0
internal const val MAP_CACHE_PREFS_NAME = "map_cache"
internal const val MAP_CACHE_PREWARM_SIG_KEY = "prewarm_signature_v1"
internal const val OSM_TILE_DOWNLOAD_THREADS: Short = 1
internal const val OSM_TILE_DOWNLOAD_MAX_QUEUE: Short = 1000
internal const val OSM_OFFLINE_PREP_REQUEST_DELAY_MS = 1_250L
internal const val OFFLINE_TILE_FETCH_MAX_ATTEMPTS = 2
internal const val OFFLINE_TILE_FETCH_RETRY_DELAY_MSEC = 250L
internal const val TILE_FS_THREADS: Short = 4
internal const val TILE_FS_MAX_QUEUE: Short = 2000
internal const val TILE_IO_ACTIVE_GRACE_MS = 2_000L
internal const val WEB_MERCATOR_HALF_WORLD_METERS = 20_037_508.342789244

internal enum class BaseLayerOption(val label: String) {
    OpenStreetMap("OpenStreetMap"),
    Imagery("Imagery")
}

internal enum class OfflinePrepAreaMode(val label: String) {
    Viewport("Current visible map"),
    MapBoundary("Selected map shape")
}

internal enum class AlertSeverity {
    None,
    Near,
    Over
}

internal data class OfflinePrepPreset(
    val label: String,
    val minZoom: Int,
    val maxZoom: Int,
    val demStepMeters: Double
)

internal val OFFLINE_PREP_PRESETS = listOf(
    OfflinePrepPreset(label = "Overview (z8-z12)", minZoom = 8, maxZoom = 12, demStepMeters = 500.0),
    OfflinePrepPreset(label = "Ops (z12-z16)", minZoom = 12, maxZoom = 16, demStepMeters = 250.0),
    OfflinePrepPreset(label = "Full detail (z8-z19)", minZoom = 8, maxZoom = 19, demStepMeters = 120.0)
)

internal data class OfflinePrepProgress(
    val phase: String = "Idle",
    val total: Int = 0,
    val completed: Int = 0,
    val tileTotal: Int = 0,
    val tileCompleted: Int = 0,
    val demTotal: Int = 0,
    val demCompleted: Int = 0,
    val demHits: Int = 0,
    val demFetched: Int = 0,
    val hits: Int = 0,
    val fetched: Int = 0,
    val failed: Int = 0,
    val demFailed: Int = 0,
    val totalFailed: Int = 0,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSec: Double = 0.0,
    val etaSeconds: Long? = null
) {
    val fraction: Double
        get() = if (totalBytes > 0L) {
            (completedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
}

internal data class GeoBoundary(
    val ring: List<GeoPoint>,
    val bounds: BoundingBox
)

internal data class OfflineBoundaryOption(
    val id: String,
    val label: String,
    val boundary: GeoBoundary
)

internal data class OfflinePrepEstimate(
    val tileEstimate: Int = 0,
    val demEstimate: Int = 0,
    val estimatedTileCacheMb: Double = 0.0,
    val estimatedDemCacheMb: Double = 0.0,
    val ready: Boolean = false
)

internal data class OfflinePrepCacheStatus(
    val checked: Boolean = false,
    val tileMissing: Int = 0,
    val demMissing: Int = 0
) {
    val readyForPackage: Boolean
        get() = checked && tileMissing == 0 && demMissing == 0
}

internal data class MapPaneBackgroundWorkStatus(
    val label: String,
    val completed: Int = 0,
    val total: Int = 0
) {
    val progress: Float?
        get() = if (total > 0) (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
}

internal data class LiveTileRequest(
    val tileIndex: Long,
    val currentTileIndex: Long,
    val requiresCurrentCached: Boolean
)

internal data class TilePriorityPoint(
    val lat: Double,
    val lng: Double,
    val headingDeg: Double?
)

internal data class DroneMapPoint(
    val designator: String,
    val remoteId: String,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double,
    val timestampMsec: Long,
    val receivedAtMsec: Long? = null,
    val headingDeg: Double? = null,
    val speedKnots: Double? = null,
    val cameraAzimuthDeg: Double? = null,
    val horizontalCameraFovDeg: Double? = null,
    val droneSpec: CtDroneSpec? = null
)

/**
 * Chooses peer traffic only when its source sample is newer than every local
 * sample for the same aircraft. Equal timestamps deliberately retain the local
 * point, while an absent or stale local point yields immediately to the peer.
 */
internal fun selectFreshestDroneMapEntries(
    localEntries: List<Pair<DroneMapPoint, Boolean>>,
    peerEntries: List<Pair<DroneMapPoint, Boolean>>,
): List<Pair<DroneMapPoint, Boolean>> {
    val latestLocalTimestampByRemoteId = localEntries
        .groupBy { it.first.remoteId }
        .mapValues { (_, entries) -> entries.maxOf { it.first.timestampMsec } }
    val latestPeerByRemoteId = peerEntries
        .groupBy { it.first.remoteId }
        .mapValues { (_, entries) ->
            entries.maxWithOrNull(
                compareBy<Pair<DroneMapPoint, Boolean>> { it.first.timestampMsec }
                    .thenBy { it.first.receivedAtMsec ?: Long.MIN_VALUE }
            )!!
        }
    val peerPreferredRemoteIds = latestPeerByRemoteId
        .filter { (remoteId, peerEntry) ->
            peerEntry.first.timestampMsec >
                (latestLocalTimestampByRemoteId[remoteId] ?: Long.MIN_VALUE)
        }
        .keys

    return localEntries.filter { it.first.remoteId !in peerPreferredRemoteIds } +
        latestPeerByRemoteId
            .filterKeys { it in peerPreferredRemoteIds }
            .values
}

internal data class LabelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
    val centerX: Int
        get() = left + width / 2
    val centerY: Int
        get() = top + height / 2

    fun intersects(other: LabelRect): Boolean =
        left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top
}

internal data class LabelLeaderLine(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int
)

internal data class DroneLabelLayoutInput(
    val designator: String,
    val anchorX: Int,
    val anchorY: Int,
    val nameWidth: Int,
    val nameHeight: Int,
    val statusWidth: Int,
    val statusHeight: Int
)

internal data class DroneLabelLayout(
    val designator: String,
    val bounds: LabelRect,
    val nameBounds: LabelRect,
    val statusBounds: LabelRect,
    val leaderLine: LabelLeaderLine?
)

internal data class DroneComplianceState(
    val aglM: Double?,
    val rangeFromTakeoffM: Double?,
    val nearAgl: Boolean,
    val nearRange: Boolean,
    val overAgl: Boolean,
    val overRange: Boolean,
    val staleDem: Boolean
)

internal data class ArtifactPointSpec(
    val id: String,
    val lat: Double,
    val lng: Double,
    val title: String,
    val markerSymbol: String,
    val markerColor: String?,
    val description: String = ""
)

internal data class ArtifactLineSpec(
    val id: String,
    val points: List<GeoPoint>,
    val color: Int,
    val width: Float,
    val title: String,
    val description: String = ""
)

internal data class ArtifactPolygonSpec(
    val id: String,
    val points: List<GeoPoint>,
    val strokeColor: Int,
    val fillColor: Int,
    val strokeWidth: Float,
    val title: String,
    val description: String = ""
)

internal data class ArtifactInspection(
    val title: String,
    val description: String
)

internal data class ArtifactOverlayState(
    val totalFeatures: Int = 0,
    val ignoredTrackLikeFeatures: Int = 0,
    val points: List<ArtifactPointSpec> = emptyList(),
    val lines: List<ArtifactLineSpec> = emptyList(),
    val polygons: List<ArtifactPolygonSpec> = emptyList()
)

internal data class ArtifactHydrationProgress(
    val completed: Int,
    val total: Int
)

internal data class ArtifactFolderDefault(
    val folderId: String,
    val initiallyVisible: Boolean
)

internal data class ArtifactHydrationResult(
    val featuresById: LinkedHashMap<String, JSONObject>,
    val overlayState: ArtifactOverlayState,
    val folderDefaults: List<ArtifactFolderDefault>,
    val serverHiddenFolderIds: Set<String>
)

internal data class LocalTrackPoint(
    val mappedId: String,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double,
    val timestampMsec: Long,
    val receivedAtMsec: Long
)

internal data class PredictedHead(
    val lat: Double,
    val lng: Double
)

internal data class BadTileDialogState(
    val tileIndex: Long,
    val zoom: Int,
    val x: Int,
    val y: Int,
    val hash: String
)

internal data class PilotDisplaySettingsState(
    val pilotKey: String,
    val displayName: String,
    val preference: PilotDisplayPreference
)
