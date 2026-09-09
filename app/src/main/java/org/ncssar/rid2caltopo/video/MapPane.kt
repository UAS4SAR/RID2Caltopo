package org.ncssar.rid2caltopo.video

import StreamsViewModel
import LocalMapMarker
import localMapMarkerForArtifact
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.text.method.ScrollingMovementMethod
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import org.ncssar.rid2caltopo.ui.MapFoldersDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetrySample
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import androidx.documentfile.provider.DocumentFile
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.airspace.AirspaceMapOverlayAdapter
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.PeerTrafficAltitudeNormalizer
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.WaypointTrack
import org.ncssar.rid2caltopo.data.MutualAidPackageManager
import org.ncssar.rid2caltopo.data.MutualAidPackageTransferManager
import org.ncssar.rid2caltopo.data.MutualAidProfileManager
import org.ncssar.rid2caltopo.data.DEFAULT_ACTIVE_TRACK_COLOR
import org.ncssar.rid2caltopo.data.DEFAULT_ARCHIVE_TRACK_COLOR
import org.ncssar.rid2caltopo.data.PilotDisplayPreference
import org.ncssar.rid2caltopo.data.PilotDisplayPrefs
import org.ncssar.rid2caltopo.data.normalizePilotCallsign
import org.ncssar.rid2caltopo.data.sanitizeTrackColor
import org.ncssar.rid2caltopo.notam.NearbyNotam
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionMapOverlayAdapter
import org.ncssar.rid2caltopo.notam.NotamMapOverlayAdapter
import org.ncssar.rid2caltopo.video.mapcache.CaltopoIconCacheService
import org.ncssar.rid2caltopo.video.mapcache.BadTilePolicy
import org.ncssar.rid2caltopo.video.mapcache.MapCacheDebug
import org.ncssar.rid2caltopo.video.mapcache.MapCachePolicy
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.ncssar.rid2caltopo.video.mapcache.TileCacheMapProvider
import org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter
import org.ncssar.rid2caltopo.video.mapcache.TileFetchPriorityScheduler

internal fun cachedArtifactOverlayState(overlayState: Any?): ArtifactOverlayState =
    overlayState as? ArtifactOverlayState ?: ArtifactOverlayState()

internal fun demSamplingSummary(stepMeters: Double): String {
    if (stepMeters <= 0.0) return "DEM sampling disabled"
    val stepFeet = stepMeters * METERS_TO_FEET
    val densityPerKm2 = 1_000_000.0 / (stepMeters * stepMeters)
    return String.format(
        Locale.US,
        "DEM sample spacing ~%.0f m (%.0f ft), ~%.1f samples/km²",
        stepMeters,
        stepFeet,
        densityPerKm2
    )
}

// DroneAglState, AtoSeedSource, DroneAltitudeCalibration — defined in DroneAltitudeModels.kt

internal enum class PilotDisplayColorSlot {
    Active,
    Archive
}

internal data class PilotColorPickerTarget(
    val settings: PilotDisplaySettingsState,
    val slot: PilotDisplayColorSlot
)

private const val LOCAL_TRACK_RECENT_POINT_LIMIT = 500
private const val LOCAL_TRACK_FLIGHT_POINT_LIMIT = 10_000
private const val LOCAL_TRACK_DUPLICATE_COORD_EPSILON = 0.000001
private const val LOCAL_TRACK_DUPLICATE_ALT_EPSILON_METERS = 0.5
internal fun seedLocalTrackPointsFromSnapshot(
    mappedId: String,
    snapshot: List<WaypointTrack.TrackPoint>,
    receivedAtMsec: Long,
    recentPoints: MutableList<LocalTrackPoint>,
    flightPoints: MutableList<LocalTrackPoint>
): Boolean {
    val key = localTrackDesignator(mappedId)
    val snapshotPoints = snapshot.mapNotNull { point ->
        if (!point.lat.isFinite() || !point.lng.isFinite()) return@mapNotNull null
        if (point.lat == 0.0 && point.lng == 0.0) return@mapNotNull null
        LocalTrackPoint(
            mappedId = key,
            lat = point.lat,
            lng = point.lng,
            altitudeM = point.ele,
            timestampMsec = point.timestampMsec,
            receivedAtMsec = receivedAtMsec
        )
    }
    if (snapshotPoints.isEmpty()) return false

    var changed = false
    snapshotPoints.forEach { point ->
        if (flightPoints.none { existing -> existing.isSameTrackPoint(point) }) {
            flightPoints.add(point)
            changed = true
        }
    }
    while (flightPoints.size > LOCAL_TRACK_FLIGHT_POINT_LIMIT) {
        flightPoints.removeAt(0)
        changed = true
    }

    if (recentPoints.isEmpty()) {
        recentPoints.add(snapshotPoints.last())
        changed = true
    }
    while (recentPoints.size > LOCAL_TRACK_RECENT_POINT_LIMIT) {
        recentPoints.removeAt(0)
        changed = true
    }

    return changed
}

internal fun shouldSeedLocalTrackSnapshotForDesignator(
    mappedId: String,
    snapshot: List<WaypointTrack.TrackPoint>,
    lastSeededTimestampByMappedId: MutableMap<String, Long>
): Boolean {
    val key = localTrackDesignator(mappedId)
    val newestTimestamp = snapshot.asSequence()
        .filter { point ->
            point.lat.isFinite() &&
                point.lng.isFinite() &&
                !(point.lat == 0.0 && point.lng == 0.0)
        }
        .map { it.timestampMsec }
        .maxOrNull()
        ?: return false
    val lastSeededTimestamp = lastSeededTimestampByMappedId[key]
    if (lastSeededTimestamp != null && newestTimestamp <= lastSeededTimestamp) {
        return false
    }
    lastSeededTimestampByMappedId[key] = newestTimestamp
    return true
}

private fun LocalTrackPoint.isSameTrackPoint(other: LocalTrackPoint): Boolean {
    if (timestampMsec != other.timestampMsec) return false
    if (kotlin.math.abs(lat - other.lat) > LOCAL_TRACK_DUPLICATE_COORD_EPSILON) return false
    if (kotlin.math.abs(lng - other.lng) > LOCAL_TRACK_DUPLICATE_COORD_EPSILON) return false
    return kotlin.math.abs(altitudeM - other.altitudeM) <= LOCAL_TRACK_DUPLICATE_ALT_EPSILON_METERS
}

internal fun trackColorInt(rawColor: String?, fallback: String): Int =
    AndroidColor.parseColor(sanitizeTrackColor(rawColor, fallback))

private fun closedPolylinePoints(points: List<GeoPoint>): List<GeoPoint> {
    if (points.isEmpty()) return points
    val first = points.first()
    val last = points.last()
    return if (
        first.latitude == last.latitude &&
        first.longitude == last.longitude
    ) {
        points
    } else {
        points + GeoPoint(first.latitude, first.longitude)
    }
}

@Suppress("DEPRECATION")
private fun applyPolygonStyle(
    polygon: Polygon,
    strokeColor: Int,
    fillColor: Int,
    strokeWidth: Float
) {
    polygon.strokeColor = strokeColor
    polygon.fillColor = fillColor
    polygon.strokeWidth = strokeWidth
}

@Suppress("DEPRECATION")
private fun applyPolylineStyle(
    polyline: Polyline,
    color: Int,
    width: Float
) {
    polyline.color = color
    polyline.width = width
}

private data class LocalMarkerInfoContent(
    val titleText: String,
    val descriptionText: String,
    val thumbnail: Bitmap?,
    val onOpenSnapshot: (() -> Unit)?,
    val markerId: String?,
    val onDelete: ((String) -> Unit)?,
)

internal class MapOwnedReusable<T : Any> {
    private var owner: Any? = null
    private var value: T? = null

    fun getOrCreate(owner: Any, factory: () -> T): T {
        if (this.owner !== owner || value == null) {
            this.owner = owner
            value = factory()
        }
        return checkNotNull(value)
    }
}

private class LocalMarkerInfoWindow(
    mapView: MapView,
) : InfoWindow(R.layout.map_local_marker_info_window, mapView) {
    private var content: LocalMarkerInfoContent? = null

    fun bind(content: LocalMarkerInfoContent) {
        this.content = content
    }

    fun isOpenFor(marker: Marker): Boolean = isOpen && relatedObject === marker

    override fun onOpen(item: Any?) {
        val content = content ?: return
        mView.findViewById<TextView>(R.id.local_marker_title)?.text = content.titleText
        mView.findViewById<View>(R.id.local_marker_close)?.setOnClickListener { close() }
        mView.findViewById<TextView>(R.id.local_marker_description)?.apply {
            text = content.descriptionText
            visibility = if (content.descriptionText.isBlank()) View.GONE else View.VISIBLE
            movementMethod = ScrollingMovementMethod.getInstance()
            scrollTo(0, 0)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        mView.findViewById<ImageView>(R.id.local_marker_snapshot)?.apply {
            if (content.thumbnail != null) {
                setImageBitmap(content.thumbnail)
                visibility = View.VISIBLE
                isClickable = content.onOpenSnapshot != null
                setOnClickListener {
                    content.onOpenSnapshot?.invoke()
                }
            } else {
                setImageDrawable(null)
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
        mView.findViewById<Button>(R.id.local_marker_delete)?.apply {
            if (content.markerId != null && content.onDelete != null) {
                visibility = View.VISIBLE
                setOnClickListener {
                    close()
                    content.onDelete.invoke(content.markerId)
                }
            } else {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
    }

    override fun onClose() {
        mView.findViewById<View>(R.id.local_marker_close)?.setOnClickListener(null)
        mView.findViewById<TextView>(R.id.local_marker_description)?.apply {
            parent?.requestDisallowInterceptTouchEvent(false)
            setOnTouchListener(null)
        }
        mView.findViewById<ImageView>(R.id.local_marker_snapshot)?.setOnClickListener(null)
        mView.findViewById<Button>(R.id.local_marker_delete)?.setOnClickListener(null)
        content = null
    }

    override fun onDetach() {
        content = null
        super.onDetach()
    }
}

internal enum class MarkerInfoWindowTapAction {
    Show,
    Close
}

internal fun markerInfoWindowTapAction(isInfoWindowShown: Boolean): MarkerInfoWindowTapAction =
    if (isInfoWindowShown) MarkerInfoWindowTapAction.Close else MarkerInfoWindowTapAction.Show

private fun consumeInsetMarkerTaps(marker: Marker, isInsetMode: Boolean) {
    if (!isInsetMode) return
    marker.infoWindow = null
    marker.setOnMarkerClickListener { _, _ -> true }
}

internal fun mapPaneAttributionText(contoursEnabled: Boolean): String =
    buildString {
        append("© OpenStreetMap contributors · DEM: USGS")
        if (contoursEnabled) append(" · Contours: USGS")
    }

private fun openClueSnapshotInExternalViewer(
    context: Context,
    title: String,
    bitmap: Bitmap?,
    imagePath: String? = null,
) {
    val sourceFile = imagePath?.let(::File)?.takeIf { it.isFile }
    if (bitmap == null && sourceFile == null) {
        CaltopoClient.ShowToast("No clue snapshot available.")
        return
    }
    try {
        val snapshotDir = File(context.cacheDir, "clue-snapshots").apply { mkdirs() }
        val fileName = sanitizeClueSnapshotFileName(title)
        val snapshotFile = File(snapshotDir, fileName)
        if (sourceFile != null) {
            sourceFile.copyTo(snapshotFile, overwrite = true)
        } else {
            FileOutputStream(snapshotFile).use { output ->
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            snapshotFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        CTError(MAP_PANE_TAG, "openClueSnapshotInExternalViewer(): failed", e)
        CaltopoClient.ShowToast("No app found to open clue snapshot.")
    }
}

private fun sanitizeClueSnapshotFileName(title: String): String {
    val safeTitle = title.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "clue_snapshot" }
    return "$safeTitle.jpg"
}

private val localClueCapturedFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss", Locale.US)

private fun localClueDetailText(marker: LocalMapMarker): String = buildString {
    append("Aircraft: ")
    append(marker.sourceDesignator.ifBlank { "Unknown" })
    append("\nCaptured: ")
    append(
        Instant.ofEpochMilli(marker.createdAtMs)
            .atZone(ZoneId.systemDefault())
            .format(localClueCapturedFormatter)
    )
    append("\nLocation: ")
    append(String.format(Locale.US, "%.5f, %.5f", marker.lat, marker.lng))
    if (marker.description.isNotBlank()) {
        append("\n\n")
        append(marker.description.trim())
    }
}

internal fun configureOsmdroid(context: Context) {
    val cfg = Configuration.getInstance()
    val tileCacheMaxBytes = MapCachePolicy.tileCacheMaxBytes(context)
    cfg.load(context, context.getSharedPreferences("osmdroid", 0))
    cfg.userAgentValue = osmUserAgent()
    cfg.tileDownloadThreads = OSM_TILE_DOWNLOAD_THREADS
    cfg.tileDownloadMaxQueueSize = OSM_TILE_DOWNLOAD_MAX_QUEUE
    cfg.tileFileSystemThreads = TILE_FS_THREADS
    cfg.tileFileSystemMaxQueueSize = TILE_FS_MAX_QUEUE
    cfg.isDebugMapTileDownloader = MapCacheDebug.isLudicrousEnabled()
    cfg.tileFileSystemCacheMaxBytes = tileCacheMaxBytes
    cfg.tileFileSystemCacheTrimBytes = (tileCacheMaxBytes * 9L) / 10L
    cfg.expirationOverrideDuration = MapCachePolicy.TILE_TTL_MS
}

private fun buildTileMapProvider(
    context: Context,
    tileSource: ITileSource,
    tileWriter: TileDiskCacheWriter
): TileCacheMapProvider {
    configureOsmdroid(context)
    return TileCacheMapProvider(
        context = context,
        tileSource = tileSource,
        tileWriter = tileWriter
    ).apply {
        setUseDataConnection(true)
        setOfflineFirst(false)
    }
}

internal fun <T> replaceTileCompletionCallback(callbacks: MutableCollection<T>, callback: T) {
    callbacks.clear()
    callbacks.add(callback)
}

private fun restartTileProviderForViewportIntent(
    mapView: MapView,
    context: Context,
    tileSource: ITileSource,
    tileWriter: TileDiskCacheWriter,
    reason: String,
    zoom: Int
): TileCacheMapProvider {
    val nextProvider = buildTileMapProvider(context, tileSource, tileWriter)
    mapView.setTileProvider(nextProvider)
    mapView.setUseDataConnection(true)
    nextProvider.setUseDataConnection(true)
    MapCacheDebug.debug(
        MapCacheDebug.TAG_TILE,
        "tile provider restarted reason=$reason zoom=$zoom source=${tileSource.name()}"
    )
    return nextProvider
}

// SplitMapPane composable - extracted from StreamsScreen.kt (lines 648-2466)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitMapPane(
    viewModel: StreamsViewModel,
    modifier: Modifier = Modifier,
    onSingleTapFocus: (() -> Unit)? = null,
    presentationMode: MapPanePresentationMode = MapPanePresentationMode.Full
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val uiScope = rememberCoroutineScope()
    val isInsetMode = presentationMode == MapPanePresentationMode.Inset
    val markerScale = mapPaneMarkerScale(presentationMode)
    val lineScale = mapPaneLineScale(presentationMode)
    val restoredViewport = viewModel.mapViewportState()?.takeIf {
        isUsableMapViewportState(it.latitude, it.longitude, it.zoom)
    }
    val followFocusedDroneEnabled = viewModel.followFocusedDroneEnabled
    fun persistFullMapViewport(mapView: MapView) {
        if (isInsetMode) return
        if (mapView.width <= 0 || mapView.height <= 0) return
        val bounds = mapView.boundingBox
        viewModel.persistMapViewportState(
            center = mapView.mapCenter,
            zoom = mapView.zoomLevelDouble,
            widthPx = mapView.width,
            heightPx = mapView.height,
            bounds = MapViewportBounds(
                north = bounds.latNorth,
                east = bounds.lonEast,
                south = bounds.latSouth,
                west = bounds.lonWest
            )
        )
    }
    val baseLayer = viewModel.baseLayer
    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var mapManagementMenuExpanded by remember { mutableStateOf(false) }
    var baseLayerMenuExpanded by remember { mutableStateOf(false) }
    var badTilesMenuExpanded by remember { mutableStateOf(false) }
    var currentMapView by remember { mutableStateOf<MapView?>(null) }
    var mapReloadInFlight by remember { mutableStateOf(false) }
    var showMapCacheSizeDialog by remember { mutableStateOf(false) }
    var showMapTileAgeDialog by remember { mutableStateOf(false) }
    var mapCacheSizeInput by remember {
        mutableStateOf(
            String.format(
                Locale.US,
                "%.1f",
                MapCacheSettings.maxCacheBytes(context).toDouble() / 1_000_000_000.0
            )
        )
    }
    var mapTileAgeDaysInput by remember {
        mutableStateOf(MapCacheSettings.maxTileAgeDays(context).toString())
    }
    var showMapFoldersDialog by remember { mutableStateOf(false) }
    var pendingArtifactZoomFeatureId by remember { mutableStateOf<String?>(null) }
    val hiddenFolderIds = viewModel.hiddenFolderIds
    val hiddenItemIds = viewModel.hiddenItemIds
    var showBadTilesHowToDialog by remember { mutableStateOf(false) }
    val offlinePrepCoordinator = AndroidMapOfflinePrepCoordinator
    var showOfflinePrepDialog by offlinePrepCoordinator.showDialog
    var showMutualAidPackageDialog by remember { mutableStateOf(false) }
    var offlinePrepInFlight by offlinePrepCoordinator.inFlight
    var offlinePrepPreset by offlinePrepCoordinator.preset
    var offlinePrepIncludeDem by offlinePrepCoordinator.includeDem
    var offlinePrepDemResolution by offlinePrepCoordinator.demResolution
    var offlinePrepIncludeContours by offlinePrepCoordinator.includeContours
    var offlinePrepMaxThroughput by offlinePrepCoordinator.maximizeThroughput
    var offlinePrepAreaMode by offlinePrepCoordinator.areaMode
    var offlinePrepBoundaryId by offlinePrepCoordinator.boundaryId
    var offlinePrepProgress by offlinePrepCoordinator.progress
    var offlinePrepCancelRequested by offlinePrepCoordinator.cancelRequested
    var offlinePrepEstimate by remember { mutableStateOf(OfflinePrepEstimate()) }
    var offlinePrepEstimateRunning by remember { mutableStateOf(false) }
    var offlinePrepCacheStatus by remember { mutableStateOf(OfflinePrepCacheStatus()) }
    var offlinePrepCompletedSelectionKey by offlinePrepCoordinator.completedSelectionKey
    var offlinePrepAvailableBytes by remember { mutableStateOf<Long?>(null) }
    var offlinePrepCurrentTileCacheBytes by remember { mutableStateOf(0L) }
    var offlinePrepTileCacheCapBytes by remember { mutableStateOf(MapCachePolicy.tileCacheMaxBytes(context)) }
    var offlinePrepCacheLimitInput by remember {
        mutableStateOf(String.format(Locale.US, "%.1f", MapCachePolicy.tileCacheMaxBytes(context) / 1_000_000_000.0))
    }
    var offlinePrepJob by offlinePrepCoordinator.job
    var offlinePrepAutoCloseJob by offlinePrepCoordinator.autoCloseJob
    val offlinePrepActiveCalls = offlinePrepCoordinator.activeCalls
    var mapBounds by remember { mutableStateOf<BoundingBox?>(null) }

    val packageZoneId = remember { ZoneId.systemDefault() }
    val packageDateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val packageTimeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val defaultPackageExpiry = remember {
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(MutualAidProfileManager.defaultExpiryAtNextMidnight()),
            packageZoneId
        )
    }
    var maPackageDisplayName by remember { mutableStateOf("") }
    var maPackageIncident by remember { mutableStateOf(CaltopoClient.GetIncident()) }
    var maPackageOpPeriod by remember { mutableStateOf(CaltopoClient.GetOpPeriod()) }
    var maPackageMapId by remember { mutableStateOf(CaltopoMap.GetMapId()) }
    var maPackageMapTitle by remember { mutableStateOf(CaltopoMap.GetMapName()) }
    var maPackageExpiryDateText by remember { mutableStateOf(defaultPackageExpiry.format(packageDateFormatter)) }
    var maPackageExpiryTimeText by remember { mutableStateOf(defaultPackageExpiry.format(packageTimeFormatter)) }
    var maPackageUseMapPaneExtents by remember { mutableStateOf(true) }
    val activeShareSession by MutualAidPackageTransferManager.shareSession.collectAsState()
    var preparingMutualAidShare by remember { mutableStateOf(false) }
    val maximizeThroughputBlockedForOsm = baseLayer == BaseLayerOption.OpenStreetMap
    var predictiveHeadEnabled by remember { mutableStateOf(CaltopoClient.GetPredictiveHeadEnabled()) }
    var contourOverlayEnabled by remember { mutableStateOf(MapCacheSettings.contourOverlayEnabled(context)) }
    var autoRemoveBadTiles by remember { mutableStateOf(BadTilePolicy.isAutoRemoveEnabled(context)) }
    var badTileDialogState by remember { mutableStateOf<BadTileDialogState?>(null) }
    var quarantineMatchingHash by remember { mutableStateOf(true) }
    val mapName = viewModel.mapName
    val artifactRenderCache = viewModel.mapArtifactRenderCache
    val artifactStoreById = artifactRenderCache.featuresById
    fun cachedOverlayState(): ArtifactOverlayState = cachedArtifactOverlayState(artifactRenderCache.overlayState)
    val localTrackPointsByMappedId = remember { mutableStateMapOf<String, MutableList<LocalTrackPoint>>() }
    val currentFlightTrackPointsByMappedId = remember { mutableStateMapOf<String, MutableList<LocalTrackPoint>>() }
    val seiTelemetryByMappedId = remember { mutableStateMapOf<String, StreamCameraTelemetrySample>() }
    val localTrackMappedIdsByRemoteId = remember { mutableStateMapOf<String, MutableSet<String>>() }
    val localTrackLastSeededTimestampByMappedId = remember { mutableMapOf<String, Long>() }
    var trackOverlayRefreshToken by remember { mutableIntStateOf(0) }
    val pilotDisplayPrefsByKey = remember { mutableStateMapOf<String, PilotDisplayPreference>() }
    var pilotDisplayRefreshToken by remember { mutableIntStateOf(0) }
    var colorPickerTarget by remember { mutableStateOf<PilotColorPickerTarget?>(null) }
    var localDeviceRefreshToken by remember { mutableIntStateOf(0) }
    var positionFreshnessRefreshToken by remember { mutableIntStateOf(0) }
    val managedOverlays = remember { mutableListOf<Overlay>() }
    val markerInfoWindow = remember { MapOwnedReusable<LocalMarkerInfoWindow>() }
    var artifactOverlayState: ArtifactOverlayState by remember {
        mutableStateOf(cachedOverlayState())
    }
    var mapBackgroundWorkStatus by remember { mutableStateOf<MapPaneBackgroundWorkStatus?>(null) }
    var artifactHydrationJob by remember { mutableStateOf<Job?>(null) }
    var artifactHydrationRunId by remember { mutableIntStateOf(0) }
    var artifactOverlayRebuildJob by remember { mutableStateOf<Job?>(null) }
    var artifactOverlayRebuildRunId by remember { mutableIntStateOf(0) }
    var lastRenderStats by remember { mutableStateOf("") }
    var lastAlignmentStats by remember { mutableStateOf("") }
    var initialViewportApplied by remember { mutableStateOf(restoredViewport != null) }
    val viewportRestoreTracker = remember(presentationMode) { MapViewportRestoreTracker() }
    var initialViewportArtifactCount by remember { mutableStateOf(-1) }
    var restoredViewportStartupCheckComplete by remember { mutableStateOf(true) }
    var restoredViewportStartupWaitLogged by remember { mutableStateOf(false) }
    var restoredViewportStartupCheckStartedAtMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var operatorAdjustedViewport by remember { mutableStateOf(false) }
    var lastLocalDeviceMarkerStats by remember { mutableStateOf("") }
    var localDeviceLocationMissingLogged by remember { mutableStateOf(false) }
    var localDeviceViewportRescueApplied by remember { mutableStateOf(false) }
    val droneMarkerIcon = remember(context) { ContextCompat.getDrawable(context, R.drawable.ic_drone_marker) }
    val symbolMarkerCache = remember { LinkedHashMap<String, Drawable>() }
    val caltopoMarkerCache = remember { mutableStateMapOf<String, Drawable>() }
    val scaledRemoteMarkerCache = remember { mutableStateMapOf<String, Drawable>() }
    val localDeviceOutlinedMarkerCache = remember { mutableStateMapOf<String, Drawable>() }
    val caltopoMarkerPending = remember { HashSet<String>() }
    val unknownSymbolsSeen = remember { LinkedHashSet<String>() }
    val iconCacheService = remember(context) { CaltopoIconCacheService(context) }
    // DemElevationService is owned by the coordinator (created once at ViewModel init).
    val demElevationService = viewModel.altitudeCoordinator.demElevationService
    val liveTilePriorityJobRef = remember { AtomicReference<Job?>(null) }
    val liveTilePriorityGeneration = remember { AtomicLong(0L) }
    // Register MapPane as an altitude consumer so the coordinator's update loop stays active.
    DisposableEffect(viewModel) {
        val removeConsumer = viewModel.addAltitudeConsumer()
        onDispose { removeConsumer() }
    }
    DisposableEffect(Unit) {
        onDispose {
            liveTilePriorityJobRef.getAndSet(null)?.cancel()
        }
    }
    val tileCacheWriter = remember(context) { TileDiskCacheWriter(context) }
    LaunchedEffect(mapManagementMenuExpanded, showMapCacheSizeDialog) {
        if (!mapManagementMenuExpanded && !showMapCacheSizeDialog) return@LaunchedEffect
        offlinePrepAvailableBytes = withContext(Dispatchers.IO) { queryAvailableCacheBytes(context) }
        offlinePrepCurrentTileCacheBytes = withContext(Dispatchers.IO) { tileCacheWriter.currentSizeBytes() }
    }
    val tileFetchPriorityScheduler = remember { TileFetchPriorityScheduler() }
    val baseTileSource = tileSourceForBaseLayer(baseLayer)
    val latestBaseTileSource by rememberUpdatedState(baseTileSource)
    var tileMapProvider by remember(context) {
        mutableStateOf(buildTileMapProvider(context, baseTileSource, tileCacheWriter))
    }
    val contourTileMapProvider = remember(context) {
        buildTileMapProvider(context, UsgsContoursTileSource, tileCacheWriter)
    }
    val latestTileMapProvider by rememberUpdatedState(tileMapProvider)
    val latestTileFetchPriorityScheduler by rememberUpdatedState(tileFetchPriorityScheduler)
    val offlineHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .build()
    }
    var lastCacheStats by remember { mutableStateOf("") }
    var nextCacheStatsLogAtMs by remember { mutableStateOf(0L) }
    var cacheStatsQueryInFlight by remember { mutableStateOf(false) }
    var prevIconHits by remember { mutableStateOf(0L) }
    var prevIconMisses by remember { mutableStateOf(0L) }
    var prevTileHits by remember { mutableStateOf(0L) }
    var prevTileMisses by remember { mutableStateOf(0L) }
    var prevDemHits by remember { mutableStateOf(0L) }
    var prevDemMisses by remember { mutableStateOf(0L) }
    var tileIoActiveUntilMs by remember { mutableStateOf(System.currentTimeMillis() + 12_000L) }
    var visibleTileZoom by remember { mutableIntStateOf(14) }
    var firstLiveTilePriorityPassComplete by remember { mutableStateOf(false) }
    var lastViewportSignature by remember { mutableStateOf<String?>(null) }
    val autoPrefetchedMapTiles = remember { Collections.synchronizedSet(HashSet<String>()) }
    val demAutoFetchClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
    val renderLatencyKeyByDesignator = remember { mutableStateMapOf<String, String>() }
    val complianceByDesignator = remember { mutableStateMapOf<String, DroneComplianceState>() }
    var fullArtifactHydrationQueued by remember { mutableStateOf(false) }
    // Tracks which drone's info-window bubble is open so it can be restored after each overlay rebuild.
    var openBubbleDesignator by remember { mutableStateOf<String?>(null) }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val latestFocusedPath by rememberUpdatedState(focusedPath)
    var lastInsetFollowAtMs by remember { mutableStateOf(0L) }
    var lastInsetFollowDesignator by remember { mutableStateOf<String?>(null) }
    var lastInsetFollowPoint by remember { mutableStateOf<GeoPoint?>(null) }
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
    val airspaceUiState by AirspaceCenter.uiState.collectAsStateWithLifecycle()
    val landRestrictionUiState by LandRestrictionCenter.uiState.collectAsStateWithLifecycle()
    val proximityMapFocusTarget by viewModel.proximityMapFocusTarget.collectAsStateWithLifecycle()
    val peerTrafficMapPoints by PeerTrafficMapRegistry.points.collectAsStateWithLifecycle()
    val staleTrackCutoffMs = System.currentTimeMillis() - (CaltopoClient.GetNewTrackDelayInSeconds() * 1000L)
    val peerTrafficStaleCutoffMs = System.currentTimeMillis() - 30_000L
    var selectedNotam by remember { mutableStateOf<NearbyNotam?>(null) }
    var selectedNotamGroup by remember { mutableStateOf<List<NearbyNotam>?>(null) }
    var selectedArtifact by remember { mutableStateOf<ArtifactInspection?>(null) }
    LaunchedEffect(viewModel) {
        while (isActive) {
            val nowMs = System.currentTimeMillis()
            val activeMappedIds = linkedSetOf<String>()
            viewModel.droneStates.forEach { (designator, state) ->
                activeMappedIds.add(designator)
                val seiSample = viewModel.cameraTelemetryDesignatorsFor(state.remoteId, state.mappedId)
                    .firstNotNullOfOrNull { streamDesignator ->
                        StreamCameraTelemetryRegistry.freshPositionAfterRidValidation(
                            designator = streamDesignator,
                            anchorLatitudeDeg = state.lastLat,
                            anchorLongitudeDeg = state.lastLng,
                            anchorAltitudeMeters = state.lastAlt,
                            takeoffReportedAltitudeMeters = state.source.impliedTakeoffAltM,
                            nowMs = nowMs,
                        )
                    }
                if (seiSample == null) {
                    seiTelemetryByMappedId.remove(designator)
                    return@forEach
                }
                seiTelemetryByMappedId[designator] = seiSample

                val seiLat = seiSample.latitudeDeg
                val seiLng = seiSample.longitudeDeg
                if (seiLat != null && seiLng != null &&
                    seiLat.isFinite() && seiLng.isFinite() &&
                    !(seiLat == 0.0 && seiLng == 0.0)
                ) {
                    val list = localTrackPointsByMappedId.getOrPut(designator) { mutableStateListOf() }
                    if (list.lastOrNull()?.receivedAtMsec != seiSample.receivedAtMs) {
                        val seiReportedAltitude = PeerTrafficAltitudeNormalizer
                            .reportedAltitudeForRelativeUp(state.remoteId, seiSample.relativeUpMeters)
                        val trafficAltitude = seiReportedAltitude ?: state.lastAlt
                        val trafficAltitudeTimestamp = if (seiReportedAltitude != null) {
                            seiSample.receivedAtMs
                        } else {
                            state.source.mostRecentMsecTimestamp
                        }
                        val flightList = currentFlightTrackPointsByMappedId.getOrPut(designator) {
                            mutableStateListOf()
                        }
                        val point = LocalTrackPoint(
                            mappedId = designator,
                            lat = seiLat,
                            lng = seiLng,
                            altitudeM = trafficAltitude,
                            timestampMsec = seiSample.receivedAtMs,
                            receivedAtMsec = seiSample.receivedAtMs,
                        )
                        list.add(point)
                        flightList.add(point)
                        R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.onSEIPositionReceived(
                            state.remoteId,
                            designator,
                            seiLat,
                            seiLng,
                            trafficAltitude,
                            seiSample.receivedAtMs,
                            trafficAltitudeTimestamp,
                            seiSample.courseDeg,
                        )
                        trackOverlayRefreshToken++
                        if (list.size > LOCAL_TRACK_RECENT_POINT_LIMIT) list.removeAt(0)
                        if (flightList.size > LOCAL_TRACK_FLIGHT_POINT_LIMIT) flightList.removeAt(0)
                    }
                }
            }
            seiTelemetryByMappedId.keys
                .filter { it !in activeMappedIds }
                .forEach { seiTelemetryByMappedId.remove(it) }
            delay(250L)
        }
    }
    val localDronePointEntries = viewModel.droneStates.mapNotNull { (designator, state) ->
        val stateTs = state.source.mostRecentMsecTimestamp
        var lat = state.lastLat
        var lng = state.lastLng
        var altitudeM = state.lastAlt
        var timestampMsec = stateTs
        var receivedAtMsec: Long? = null
        var usingLocalTail = false

        localTrackPointsByMappedId[designator]?.lastOrNull()?.let { localTail ->
            val localTailIsUsable =
                localTail.receivedAtMsec >= staleTrackCutoffMs &&
                    localTail.lat.isFinite() &&
                    localTail.lng.isFinite() &&
                    !(localTail.lat == 0.0 && localTail.lng == 0.0)
            if (localTailIsUsable) {
                lat = localTail.lat
                lng = localTail.lng
                altitudeM = localTail.altitudeM
                timestampMsec = localTail.timestampMsec
                receivedAtMsec = localTail.receivedAtMsec
                usingLocalTail = true
            }
        }

        if ((lat == 0.0 && lng == 0.0) || timestampMsec <= staleTrackCutoffMs) {
            null
        } else {
            val cameraTelemetry = seiTelemetryByMappedId[designator]
            val cameraAzimuthDeg = cameraTelemetry?.fovAzimuthDeg?.takeIf { it.isFinite() }
            val horizontalCameraFovDeg = cameraTelemetry?.horizontalFovDeg
                ?.takeIf { it.isFinite() && it > 0.0 && it <= 180.0 }
            // Preserve the pre-FOV status-label heading selection. The marker arrow and
            // optional long bearing line are both derived from recent movement below.
            val headingDeg = cameraTelemetry?.azimuthDeg?.takeIf { it.isFinite() }
                ?: viewModel.droneDisplayStateFor(designator)?.headingDeg?.takeIf { it.isFinite() }
            Pair(
                DroneMapPoint(
                    designator = designator,
                    remoteId = state.source?.remoteId ?: designator,
                    lat = lat,
                    lng = lng,
                    altitudeM = altitudeM,
                    timestampMsec = timestampMsec,
                    receivedAtMsec = receivedAtMsec,
                    headingDeg = headingDeg,
                    cameraAzimuthDeg = cameraAzimuthDeg,
                    horizontalCameraFovDeg = horizontalCameraFovDeg,
                    droneSpec = state.source
                ),
                usingLocalTail
            )
        }
    }
    val peerDronePointEntries = peerTrafficMapPoints.values
        .filter { it.sampleTimestampMsec >= peerTrafficStaleCutoffMs }
        .groupBy { it.remoteId }
        .values
        .mapNotNull { candidates ->
            val point = candidates.maxWithOrNull(
                compareBy<PeerTrafficMapPoint> { it.sampleTimestampMsec }
                    .thenBy { it.receivedAtMsec }
                    .thenBy { it.sequence }
            ) ?: return@mapNotNull null
            Pair(
                DroneMapPoint(
                    designator = point.mappedId,
                    remoteId = point.remoteId,
                    lat = point.latitude,
                    lng = point.longitude,
                    altitudeM = point.altitudeMslMeters ?: 0.0,
                    timestampMsec = point.sampleTimestampMsec,
                    receivedAtMsec = point.receivedAtMsec,
                    headingDeg = point.headingDeg,
                    speedKnots = point.speedKnots,
                ),
                false
            )
        }
    val dronePointEntries = selectFreshestDroneMapEntries(
        localEntries = localDronePointEntries,
        peerEntries = peerDronePointEntries,
    )

    MapPaneNotamDialogs(
        selectedNotam = selectedNotam,
        onSelectedNotamChange = { selectedNotam = it },
        selectedNotamGroup = selectedNotamGroup,
        onSelectedNotamGroupChange = { selectedNotamGroup = it }
    )
    selectedArtifact?.let { artifact ->
        AlertDialog(
            onDismissRequest = { selectedArtifact = null },
            title = { Text(artifact.title) },
            text = {
                Text(
                    text = artifact.description.ifBlank { "No description is available for this map item." },
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedArtifact = null }) { Text("Close") }
            }
        )
    }
    val dronePoints = dronePointEntries.map { it.first }
    val localTailHeadOverrideCount = dronePointEntries.count { it.second }
    var offlineBoundaryOptions by remember { mutableStateOf<List<OfflineBoundaryOption>>(emptyList()) }
    LaunchedEffect(artifactOverlayState) {
        if (artifactOverlayState.totalFeatures == 0) {
            offlineBoundaryOptions = emptyList()
            return@LaunchedEffect
        }
        mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus("Preparing map boundaries")
        val computed = withContext(Dispatchers.Default) {
            buildOfflineBoundaryOptions(artifactOverlayState)
        }
        offlineBoundaryOptions = computed
        if (mapBackgroundWorkStatus?.label == "Preparing map boundaries") {
            mapBackgroundWorkStatus = null
        }
    }
    LaunchedEffect(offlineBoundaryOptions) {
        val selectedStillExists = offlineBoundaryOptions.any { it.id == offlinePrepBoundaryId }
        if (!selectedStillExists) {
            offlinePrepBoundaryId = offlineBoundaryOptions.firstOrNull()?.id
        }
        if (offlineBoundaryOptions.isEmpty() && offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
            offlinePrepAreaMode = OfflinePrepAreaMode.Viewport
        }
    }
    LaunchedEffect(baseLayer) {
        if (baseLayer == BaseLayerOption.OpenStreetMap) {
            offlinePrepMaxThroughput = false
        }
        tileIoActiveUntilMs = System.currentTimeMillis() + TILE_IO_ACTIVE_GRACE_MS
    }
    LaunchedEffect(autoRemoveBadTiles) {
        BadTilePolicy.setAutoRemoveEnabled(context, autoRemoveBadTiles)
    }
    LaunchedEffect(context, firstLiveTilePriorityPassComplete) {
        if (!firstLiveTilePriorityPassComplete) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences(MAP_CACHE_PREFS_NAME, 0)
                val signature =
                    "tile=${MapCachePolicy.TILE_CACHE_VERSION}|icon=${MapCachePolicy.ICON_CACHE_VERSION}|dem=v1|safIndex=2|root=${mapCacheRootSignature(appContext)}"
                if (prefs.getString(MAP_CACHE_PREWARM_SIG_KEY, null) == signature) return@withContext
                val startMs = System.currentTimeMillis()
                tileCacheWriter.prewarm()
                iconCacheService.prewarm()
                prefs.edit().putString(MAP_CACHE_PREWARM_SIG_KEY, signature).apply()
                val elapsedMs = System.currentTimeMillis() - startMs
                MapCacheDebug.log("prewarm auto complete elapsedMs=$elapsedMs sig=$signature")
            } catch (e: Exception) {
                MapCacheDebug.log("prewarm auto failed err=${e.javaClass.simpleName}:${e.message}")
            }
        }
    }
    LaunchedEffect(baseLayer, visibleTileZoom, localDeviceRefreshToken, dronePoints, offlinePrepInFlight) {
        val generation = liveTilePriorityGeneration.incrementAndGet()
        val passZoom = visibleTileZoom
        val passBaseLayer = baseLayer
        val passDronePoints = dronePoints
        val previousJob = liveTilePriorityJobRef.getAndSet(
            uiScope.launch(Dispatchers.IO) {
                val source = tileSourceForBaseLayer(passBaseLayer)
                val tabletLocation = CaltopoMap.GetMyLocation()?.takeIf {
                    it.latitude.isFinite() && it.longitude.isFinite()
                }?.let { GeoPoint(it.latitude, it.longitude) }
                val requests = liveTilePriorityRequests(
                    tabletLocation = tabletLocation,
                    dronePoints = passDronePoints.map { TilePriorityPoint(it.lat, it.lng, it.headingDeg) },
                    visibleZoom = passZoom
                )
                if (requests.isEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (liveTilePriorityGeneration.get() == generation) {
                            firstLiveTilePriorityPassComplete = true
                        }
                    }
                    return@launch
                }
                val passStartMs = System.currentTimeMillis()
                var attemptedRequests = 0
                var availableRequests = 0
                MapCacheDebug.debug(
                    MapCacheDebug.TAG_TILE,
                    "live-priority pass start zoom=$passZoom requests=${requests.size} source=${source.name()}"
                )
                try {
                    for (request in requests) {
                        currentCoroutineContext().ensureActive()
                        if (request.requiresCurrentCached && !tileCacheWriter.exists(source, request.currentTileIndex)) {
                            continue
                        }
                        val key = "${source.name()}:${request.tileIndex}"
                        if (key in autoPrefetchedMapTiles) continue
                        attemptedRequests++
                        val taskStartMs = System.currentTimeMillis()
                        val available = tileFetchPriorityScheduler.highPriority {
                            prefetchMapTileIfMissing(
                                source,
                                request.tileIndex,
                                tileCacheWriter,
                                offlineHttpClient,
                                reason = "live-priority"
                            )
                        }
                        val taskElapsedMs = System.currentTimeMillis() - taskStartMs
                        if (taskElapsedMs >= 1_000L) {
                            MapCacheDebug.warn(
                                MapCacheDebug.TAG_TILE,
                                "live-priority tile slow zoom=$passZoom source=${source.name()} " +
                                    "tile=${request.tileIndex} elapsedMs=$taskElapsedMs"
                            )
                        }
                        if (available) {
                            autoPrefetchedMapTiles.add(key)
                            availableRequests++
                        }
                    }
                } catch (e: CancellationException) {
                    val elapsedMs = System.currentTimeMillis() - passStartMs
                    MapCacheDebug.debug(
                        MapCacheDebug.TAG_TILE,
                        "live-priority pass cancelled zoom=$passZoom requests=${requests.size} " +
                            "attempted=$attemptedRequests elapsedMs=$elapsedMs"
                    )
                    throw e
                } finally {
                    val elapsedMs = System.currentTimeMillis() - passStartMs
                    if (elapsedMs >= 1_000L) {
                        MapCacheDebug.warn(
                            MapCacheDebug.TAG_TILE,
                            "live-priority pass slow zoom=$passZoom requests=${requests.size} " +
                                "attempted=$attemptedRequests available=$availableRequests elapsedMs=$elapsedMs"
                        )
                    } else {
                        MapCacheDebug.debug(
                            MapCacheDebug.TAG_TILE,
                            "live-priority pass done zoom=$passZoom requests=${requests.size} elapsedMs=$elapsedMs"
                        )
                    }
                    if (currentCoroutineContext().isActive) {
                        withContext(Dispatchers.Main.immediate) {
                            if (liveTilePriorityGeneration.get() == generation) {
                                firstLiveTilePriorityPassComplete = true
                            }
                        }
                    }
                }
            }
        )
        previousJob?.cancel()
    }
    LaunchedEffect(
        showOfflinePrepDialog,
        offlinePrepAreaMode,
        offlinePrepBoundaryId,
        offlinePrepPreset,
        offlinePrepIncludeDem,
        offlinePrepDemResolution,
        offlinePrepIncludeContours,
        mapBounds,
        offlineBoundaryOptions
    ) {
        if (!showOfflinePrepDialog) return@LaunchedEffect
        offlinePrepEstimateRunning = true
        offlinePrepEstimate = OfflinePrepEstimate(ready = false)
        val selectedBoundary =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val estimateBounds = selectedBoundary?.bounds ?: mapBounds
        if (estimateBounds == null) {
            offlinePrepEstimateRunning = false
            offlinePrepEstimate = OfflinePrepEstimate(ready = false)
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.Default) {
            val baseTileEstimate = estimateTileCountApproximate(
                bounds = estimateBounds,
                minZoom = offlinePrepPreset.minZoom,
                maxZoom = offlinePrepPreset.maxZoom,
                clipBoundary = selectedBoundary
            )
            val tileEstimate = offlinePrepTileOperationCount(baseTileEstimate, offlinePrepIncludeContours)
            val demEstimate = if (offlinePrepIncludeDem) {
                estimateDemDownloadCount(estimateBounds, offlinePrepDemResolution)
            } else 0
            val tileCacheMb = (tileEstimate.toLong() * 20_000L) / (1024.0 * 1024.0)
            val demCacheMb = if (offlinePrepIncludeDem) {
                conservativeDemBytes(estimateBounds, offlinePrepDemResolution) / (1024.0 * 1024.0)
            } else 0.0
            OfflinePrepEstimate(
                tileEstimate = tileEstimate,
                demEstimate = demEstimate,
                estimatedTileCacheMb = tileCacheMb,
                estimatedDemCacheMb = demCacheMb,
                ready = true
            )
        }
        offlinePrepEstimate = computed
        offlinePrepEstimateRunning = false
        offlinePrepAvailableBytes = withContext(Dispatchers.IO) {
            queryAvailableCacheBytes(context)
        }
        offlinePrepCurrentTileCacheBytes = withContext(Dispatchers.IO) {
            tileCacheWriter.currentSizeBytes()
        }
        offlinePrepTileCacheCapBytes = withContext(Dispatchers.IO) {
            MapCachePolicy.tileCacheMaxBytes(context)
        }
    }
    LaunchedEffect(
        showOfflinePrepDialog,
        offlinePrepAreaMode,
        offlinePrepBoundaryId,
        offlinePrepPreset,
        offlinePrepIncludeDem,
        offlinePrepDemResolution,
        offlinePrepIncludeContours,
        baseLayer,
        mapBounds,
        offlineBoundaryOptions,
        offlinePrepInFlight,
        offlinePrepProgress.phase
    ) {
        if (!showOfflinePrepDialog) {
            offlinePrepCacheStatus = OfflinePrepCacheStatus()
            return@LaunchedEffect
        }
        if (offlinePrepInFlight) {
            offlinePrepCacheStatus = OfflinePrepCacheStatus(checked = false)
            return@LaunchedEffect
        }
        val selectedBoundary =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val prepBounds = selectedBoundary?.bounds ?: mapBounds
        if (prepBounds == null) {
            offlinePrepCacheStatus = OfflinePrepCacheStatus()
            return@LaunchedEffect
        }
        offlinePrepCacheStatus = OfflinePrepCacheStatus(checked = false)
        val tileSources = offlinePrepTileSources(baseLayer, offlinePrepIncludeContours)
        val includeDem = offlinePrepIncludeDem
        val computed = withContext(Dispatchers.IO) {
            var tileMissing = 0
            forEachTileIndexForBounds(
                bounds = prepBounds,
                minZoom = offlinePrepPreset.minZoom,
                maxZoom = offlinePrepPreset.maxZoom,
                clipBoundary = selectedBoundary
            ) { tileIndex ->
                for (tileSource in tileSources) {
                    if (!tileCacheWriter.exists(tileSource, tileIndex)) {
                        tileMissing++
                    }
                }
            }
            var demMissing = 0
            if (includeDem) {
                val archiveRoot = CaltopoClient.GetArchiveDir()
                val demDir = archiveRoot?.findFile("cache")?.findFile("dem")
                val downloads = runCatching {
                    resolveDemDownloads(prepBounds, offlinePrepDemResolution, demAutoFetchClient)
                }.getOrDefault(emptyList())
                for (download in downloads) {
                    val demFile = demDir?.findFile(download.fileName)
                    if (demFile?.isFile != true) {
                        demMissing++
                    }
                }
            }
            OfflinePrepCacheStatus(
                checked = true,
                tileMissing = tileMissing,
                demMissing = demMissing
            )
        }
        offlinePrepCacheStatus = computed
    }

    fun selectedTileSource(): org.osmdroid.tileprovider.tilesource.ITileSource {
        return tileSourceForBaseLayer(baseLayer)
    }

    fun parseMutualAidPackageExpiry(): Long =
        parseMutualAidPackageExpiry(
            dateText = maPackageExpiryDateText,
            timeText = maPackageExpiryTimeText,
            zoneId = packageZoneId,
            dateFormatter = packageDateFormatter,
            timeFormatter = packageTimeFormatter
        )

    fun currentOfflinePrepSelectionKey(): String? {
        val boundary =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val bounds = boundary?.bounds ?: mapBounds ?: return null
        val areaKey =
            if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                "boundary:${offlinePrepBoundaryId ?: "none"}"
            } else {
                "viewport"
            }
        return listOf(
            "base=${baseLayer.name}",
            "preset=${offlinePrepPreset.label}",
            "dem=$offlinePrepIncludeDem",
            "demResolution=${offlinePrepDemResolution.meters}m",
            "contours=$offlinePrepIncludeContours",
            areaKey,
            "n=${"%.5f".format(Locale.US, bounds.latNorth)}",
            "s=${"%.5f".format(Locale.US, bounds.latSouth)}",
            "w=${"%.5f".format(Locale.US, bounds.lonWest)}",
            "e=${"%.5f".format(Locale.US, bounds.lonEast)}"
        ).joinToString("|")
    }

    val offlinePrepReadyByCompletion = remember(
        offlinePrepInFlight,
        offlinePrepCompletedSelectionKey,
        offlinePrepAreaMode,
        offlinePrepBoundaryId,
        offlinePrepPreset,
        offlinePrepIncludeDem,
        offlinePrepDemResolution,
        offlinePrepIncludeContours,
        baseLayer,
        mapBounds,
        offlineBoundaryOptions
    ) {
        !offlinePrepInFlight && offlinePrepCompletedSelectionKey != null &&
            offlinePrepCompletedSelectionKey == currentOfflinePrepSelectionKey()
    }
    val offlinePackageReady = offlinePrepCacheStatus.readyForPackage || offlinePrepReadyByCompletion

    fun startMutualAidShare() {
        val boundary =
            if (!maPackageUseMapPaneExtents && offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
            } else {
                null
            }
        val prepBounds = boundary?.bounds ?: mapBounds
        if (prepBounds == null) {
            CaltopoClient.ShowToast("Mutual aid package export needs visible map bounds.")
            return
        }
        preparingMutualAidShare = true
        uiScope.launch(Dispatchers.IO) {
            val packageName = buildString {
                append(maPackageIncident.ifBlank { "incident" }.replace(' ', '_'))
                append("_op")
                append(maPackageOpPeriod.ifBlank { "1" })
            }
            val exportResult = MutualAidPackageManager.exportPackageToTempFile(
                context = context,
                packageName = packageName,
                displayName = maPackageDisplayName.trim(),
                incident = maPackageIncident.trim(),
                opPeriod = maPackageOpPeriod.trim(),
                targetMapId = maPackageMapId.trim(),
                targetMapTitle = maPackageMapTitle.trim(),
                expiresAtEpochMs = parseMutualAidPackageExpiry(),
                bounds = prepBounds,
                minZoom = offlinePrepPreset.minZoom,
                maxZoom = offlinePrepPreset.maxZoom,
                tileSource = selectedTileSource(),
                includeDem = offlinePrepIncludeDem,
                clipBoundary = boundary
            )
            val result = if (exportResult.first && exportResult.second != null) {
                MutualAidPackageTransferManager.startShareSession(context, exportResult.second!!, packageName)
            } else {
                false to "Failed to build MA package."
            }
            withContext(Dispatchers.Main.immediate) {
                preparingMutualAidShare = false
                CaltopoClient.ShowToast(result.second)
            }
        }
    }

    fun openMutualAidPackageDialog() {
        maPackageIncident = CaltopoClient.GetIncident()
        maPackageOpPeriod = CaltopoClient.GetOpPeriod()
        maPackageMapId = CaltopoMap.GetMapId()
        maPackageMapTitle = CaltopoMap.GetMapName()
        maPackageUseMapPaneExtents = true
        val nextMidnight = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(MutualAidProfileManager.defaultExpiryAtNextMidnight()),
            packageZoneId
        )
        maPackageExpiryDateText = nextMidnight.format(packageDateFormatter)
        maPackageExpiryTimeText = nextMidnight.format(packageTimeFormatter)
        showMutualAidPackageDialog = true
    }

    fun startOfflinePrep(bounds: BoundingBox, clipBoundary: GeoBoundary?) {
        if (offlinePrepInFlight) return
        offlinePrepAutoCloseJob?.cancel()
        offlinePrepAutoCloseJob = null
        offlinePrepActiveCalls.clear()
        offlinePrepCancelRequested = false
        offlinePrepInFlight = true
        offlinePrepProgress = OfflinePrepProgress(phase = "Preparing", total = 0, completed = 0)
        val offlinePrepTileFetchScheduler = offlinePrepCoordinator.begin(context)
        val preset = offlinePrepPreset
        val includeDem = offlinePrepIncludeDem
        val demResolution = offlinePrepDemResolution
        val includeContours = offlinePrepIncludeContours
        val tileSources = offlinePrepTileSources(baseLayer, includeContours)
        val isOsmDownload = baseLayer == BaseLayerOption.OpenStreetMap
        val maximizeThroughput = offlinePrepMaxThroughput && !isOsmDownload
        val estimatedTileOps = offlinePrepEstimate.tileEstimate
        var estimatedDemOps = if (includeDem) estimateDemDownloadCount(bounds, demResolution) else 0
        var estimatedTotalOps = (estimatedTileOps + estimatedDemOps).coerceAtLeast(1)
        val selectionKey = currentOfflinePrepSelectionKey()
        val tabletLocation = CaltopoMap.GetMyLocation()?.takeIf {
            it.latitude.isFinite() && it.longitude.isFinite()
        }?.let { GeoPoint(it.latitude, it.longitude) }
        val dronePathPoints = dronePoints.mapNotNull { point ->
            if (point.lat.isFinite() && point.lng.isFinite()) GeoPoint(point.lat, point.lng) else null
        }
        offlinePrepJob = offlinePrepCoordinator.scope.launch(Dispatchers.IO) {
            if (tileSources.isEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    offlinePrepCoordinator.finish()
                    CaltopoClient.ShowToast("Selected base layer does not support map download.")
                }
                return@launch
            }
            tileCacheWriter.prewarm()
            val demDownloads = if (includeDem) {
                try {
                    resolveDemDownloads(bounds, demResolution, demAutoFetchClient)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        offlinePrepInFlight = false
                        offlinePrepJob = null
                        offlinePrepCoordinator.finish()
                        offlinePrepProgress = offlinePrepProgress.copy(phase = "Failed")
                        CaltopoClient.ShowToast("DEM planning failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                    return@launch
                }
            } else emptyList()
            estimatedDemOps = demDownloads.size
            estimatedTotalOps = (estimatedTileOps + estimatedDemOps).coerceAtLeast(1)
            val estimatedTileBytes = estimatedTileOps.toLong() * 20_000L
            val demEstimatedBytes = demDownloads.associateWith {
                estimatedDemDownloadBytes(it, demResolution)
            }
            val estimatedTotalBytes = (
                estimatedTileBytes + demEstimatedBytes.values.sum()
            ).coerceAtLeast(1L)
            // Resolve the GeoTIFF DEM storage directory once for this download job.
            // archiveDemDir is null when no archive directory is configured.
            val archiveDemDir: DocumentFile? = if (includeDem) {
                val archiveRoot = CaltopoClient.GetArchiveDir()
                val cacheDir = archiveRoot?.findFile("cache")
                cacheDir?.findFile("dem") ?: cacheDir?.createDirectory("dem")
            } else null
            // Dedicated HTTP client for large USGS GeoTIFF downloads.
            val geoTiffHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            val completed = AtomicInteger(0)
            val tileCompleted = AtomicInteger(0)
            val demCompleted = AtomicInteger(0)
            val hits = AtomicInteger(0)
            val fetched = AtomicInteger(0)
            val tileFailed = AtomicInteger(0)
            val demFailed = AtomicInteger(0)
            val demHits = AtomicInteger(0)
            val demFetched = AtomicInteger(0)
            val totalFailed = AtomicInteger(0)
            val completedEstimatedBytes = AtomicLong(0L)
            val activeDemBytes = ConcurrentHashMap<String, Long>()
            val tileFailureLogCount = AtomicInteger(0)
            val demFailureLogCount = AtomicInteger(0)
            val startedAt = System.currentTimeMillis()
            var lastUiUpdateMs = 0L
            var phase = "Downloading map tiles"
            suspend fun pushProgress(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastUiUpdateMs < 400L) return
                lastUiUpdateMs = now
                val done = completed.get()
                val tileDone = tileCompleted.get()
                val demDone = demCompleted.get()
                val demHit = demHits.get()
                val demFetch = demFetched.get()
                val hit = hits.get()
                val fetch = fetched.get()
                val tileFail = tileFailed.get()
                val demFail = demFailed.get()
                val failTotal = totalFailed.get()
                val elapsedSec = ((now - startedAt).coerceAtLeast(1L)).toDouble() / 1000.0
                val byteDone = (completedEstimatedBytes.get() + activeDemBytes.values.sum())
                    .coerceIn(0L, estimatedTotalBytes)
                val rate = byteDone.toDouble() / elapsedSec
                val displayTotal = maxOf(estimatedTotalOps, done)
                val displayTileTotal = maxOf(estimatedTileOps, tileDone)
                val displayDemTotal = maxOf(estimatedDemOps, demDone)
                val remainingBytes = (estimatedTotalBytes - byteDone).coerceAtLeast(0L)
                val eta = if (rate > 1.0) kotlin.math.ceil(remainingBytes / rate).toLong() else null
                withContext(Dispatchers.Main.immediate) {
                    offlinePrepProgress = OfflinePrepProgress(
                        phase = phase,
                        total = displayTotal,
                        completed = done,
                        tileTotal = displayTileTotal,
                        tileCompleted = tileDone,
                        demTotal = displayDemTotal,
                        demCompleted = demDone,
                        demHits = demHit,
                        demFetched = demFetch,
                        hits = hit,
                        fetched = fetch,
                        failed = tileFail,
                        demFailed = demFail,
                        totalFailed = failTotal,
                        completedBytes = byteDone,
                        totalBytes = estimatedTotalBytes,
                        bytesPerSec = rate,
                        etaSeconds = eta
                    )
                }
            }

            try {
                coroutineScope {
                    val progressTicker = launch {
                        while (isActive) {
                            pushProgress(force = true)
                            delay(500L)
                        }
                    }
                    suspend fun processTile(tileSource: OnlineTileSourceBase, tileIndex: Long) {
                        ensureActive()
                        val exists = tileCacheWriter.exists(tileSource, tileIndex)
                        if (exists) {
                            hits.incrementAndGet()
                        } else {
                            val z = MapTileIndex.getZoom(tileIndex)
                            val x = MapTileIndex.getX(tileIndex)
                            val y = MapTileIndex.getY(tileIndex)
                            var failureDetail = ""
                            var ok = false
                            for (attempt in 1..OFFLINE_TILE_FETCH_MAX_ATTEMPTS) {
                                var retryable = false
                                ok = try {
                                    val url = tileSource.getTileURLString(tileIndex)
                                    val req = buildOfflineTileRequest(tileSource, url)
                                    val call = offlineHttpClient.newCall(req)
                                    offlinePrepActiveCalls += call
                                    try {
                                        offlinePrepTileFetchScheduler.lowPriority {
                                            call.execute().use { resp ->
                                                if (!resp.isSuccessful) {
                                                    retryable = resp.code == 408 || resp.code == 429 || resp.code >= 500
                                                    failureDetail = "http=${resp.code} z=$z x=$x y=$y source=${tileSource.name()}"
                                                    return@use false
                                                }
                                                val body = resp.body ?: run {
                                                    retryable = true
                                                    return@use false
                                                }
                                                val bytes = body.bytes()
                                                val saved = tileCacheWriter.saveFile(
                                                    tileSource,
                                                    tileIndex,
                                                    ByteArrayInputStream(bytes),
                                                    null
                                                )
                                                if (!saved && failureDetail.isBlank()) {
                                                    val rejection = tileCacheWriter.describeRejectedWrite(tileSource, tileIndex, bytes)
                                                    failureDetail =
                                                        (rejection ?: "save-rejected") + " z=$z x=$x y=$y source=${tileSource.name()}"
                                                }
                                                saved
                                            }
                                        }
                                    } finally {
                                        offlinePrepActiveCalls.remove(call)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    retryable = true
                                    failureDetail = "ex=${e.javaClass.simpleName} z=$z x=$x y=$y source=${tileSource.name()}"
                                    false
                                }
                                if (ok || !retryable || attempt == OFFLINE_TILE_FETCH_MAX_ATTEMPTS) break
                                ensureActive()
                                delay(OFFLINE_TILE_FETCH_RETRY_DELAY_MSEC)
                            }
                            if (tileSource.name() == OsmStandardTileSource.name()) {
                                delay(OSM_OFFLINE_PREP_REQUEST_DELAY_MS)
                            }
                            if (ok) {
                                fetched.incrementAndGet()
                            } else {
                                tileFailed.incrementAndGet()
                                totalFailed.incrementAndGet()
                                val n = tileFailureLogCount.incrementAndGet()
                                if (n <= 12 || (n % 50) == 0) {
                                    if (CTDebugEnabled(MAP_PANE_TAG))  CTDebug(MAP_PANE_TAG, "DownloadMap tile failure#$n $failureDetail")
                                    MapCacheDebug.log("download tile failure#$n $failureDetail")
                                }
                            }
                        }
                        tileCompleted.incrementAndGet()
                        completed.incrementAndGet()
                        completedEstimatedBytes.addAndGet(20_000L)
                        MapOfflinePrepRuntime.noteProgress()
                    }
                    suspend fun processDem(lat: Double, lng: Double) {
                        ensureActive()
                        val wasCached = demElevationService.hasCachedSample(lat, lng)
                        var failureDetail = "no-sample"
                        val sample = try {
                            demElevationService.sampleElevationMeters(lat, lng)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failureDetail = "ex=${e.javaClass.simpleName}:${e.message}"
                            null
                        }
                        if (sample == null) {
                            demFailed.incrementAndGet()
                            totalFailed.incrementAndGet()
                            val n = demFailureLogCount.incrementAndGet()
                            if (n <= 12 || (n % 50) == 0) {
                                val msg = "download dem failure#$n lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)} reason=$failureDetail"
                                CTError(MAP_PANE_TAG, "DownloadMap DEM $msg")
                                MapCacheDebug.warn(MapCacheDebug.TAG_DEM, msg)
                            }
                        }
                        if (sample != null) {
                            if (wasCached) demHits.incrementAndGet() else demFetched.incrementAndGet()
                        }
                        demCompleted.incrementAndGet()
                        completed.incrementAndGet()
                        MapOfflinePrepRuntime.noteProgress()
                    }

                    // Downloads one planner-selected USGS GeoTIFF to archiveDir/cache/dem/.
                    // The descriptor covers geographic 30/10 m tiles and catalog-resolved 1 m tiles.
                    // Stream directly to disk because the finer products can be hundreds of MB.
                    suspend fun processGeoTiffTile(download: DemDownload) {
                        ensureActive()
                        val estimatedBytes = demEstimatedBytes.getValue(download)
                        val demDir = archiveDemDir
                        if (demDir == null) {
                            demFailed.incrementAndGet()
                            totalFailed.incrementAndGet()
                            demCompleted.incrementAndGet()
                            completed.incrementAndGet()
                            completedEstimatedBytes.addAndGet(estimatedBytes)
                            MapOfflinePrepRuntime.noteProgress()
                            return
                        }
                        val fileName = download.fileName
                        val existing = demDir.findFile(fileName)
                        val minimumCompleteBytes = download.expectedBytes?.let { maxOf(100_000L, it * 95L / 100L) } ?: 5_000_000L
                        if (existing != null && existing.isFile && existing.length() >= minimumCompleteBytes) {
                            demHits.incrementAndGet()
                            demCompleted.incrementAndGet()
                            completed.incrementAndGet()
                            completedEstimatedBytes.addAndGet(estimatedBytes)
                            MapOfflinePrepRuntime.noteProgress()
                            MapCacheDebug.log("geotiff dem hit file=$fileName bytes=${existing.length()}")
                            return
                        }
                        var failureDetail = "unknown"
                        val ok = try {
                            val req = Request.Builder().url(download.url).build()
                            val call = geoTiffHttpClient.newCall(req)
                            offlinePrepActiveCalls += call
                            try {
                                call.execute().use { resp ->
                                    if (!resp.isSuccessful) {
                                        failureDetail = "http=${resp.code}"
                                        return@use false
                                    }
                                    val body = resp.body ?: run { failureDetail = "no-body"; return@use false }
                                    val destFile = demDir.findFile(fileName) ?: demDir.createFile("image/tiff", fileName)
                                    if (destFile == null) { failureDetail = "create-failed"; return@use false }
                                    val expectedResponseBytes = body.contentLength().takeIf { it > 0L }
                                    context.contentResolver.openOutputStream(destFile.uri, "wt")?.use { out ->
                                        body.byteStream().use { input ->
                                            val buffer = ByteArray(128 * 1024)
                                            var transferred = 0L
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                out.write(buffer, 0, count)
                                                transferred += count
                                                activeDemBytes[fileName] = weightedTransferredBytes(
                                                    estimatedBytes = estimatedBytes,
                                                    transferredBytes = transferred,
                                                    expectedBytes = expectedResponseBytes,
                                                )
                                                MapOfflinePrepRuntime.noteProgress()
                                            }
                                        }
                                    } ?: run { failureDetail = "stream-open-failed"; return@use false }
                                    MapCacheDebug.log("geotiff dem fetched file=$fileName uri=${destFile.uri}")
                                    true
                                }
                            } finally {
                                offlinePrepActiveCalls.remove(call)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failureDetail = "ex=${e.javaClass.simpleName}:${e.message}"
                            false
                        }
                        if (ok) {
                            demFetched.incrementAndGet()
                        } else {
                            demFailed.incrementAndGet()
                            totalFailed.incrementAndGet()
                            val n = demFailureLogCount.incrementAndGet()
                            if (n <= 12 || (n % 50) == 0) {
                                val msg = "GeoTIFF download failure#$n file=$fileName reason=$failureDetail"
                                CTError(MAP_PANE_TAG, "DownloadMap DEM $msg")
                                MapCacheDebug.warn(MapCacheDebug.TAG_DEM, msg)
                            }
                        }
                        demCompleted.incrementAndGet()
                        completed.incrementAndGet()
                        activeDemBytes.remove(fileName)
                        completedEstimatedBytes.addAndGet(estimatedBytes)
                        MapOfflinePrepRuntime.noteProgress()
                    }

                    if (!maximizeThroughput) {
                        val workerCount = if (isOsmDownload) 1 else 3
                        val tileQueue = Channel<Pair<OnlineTileSourceBase, Long>>(capacity = workerCount * 3)
                        val workers = List(workerCount) {
                            launch {
                                for ((source, tileIndex) in tileQueue) {
                                    processTile(source, tileIndex)
                                }
                            }
                        }
                        val orderedTileIndexes = orderedTileIndexesForOfflinePrep(
                            bounds = bounds,
                            minZoom = preset.minZoom,
                            maxZoom = preset.maxZoom,
                            clipBoundary = clipBoundary,
                            tabletLocation = tabletLocation,
                            dronePathPoints = dronePathPoints
                        )
                        for (tileIndex in orderedTileIndexes) {
                            currentCoroutineContext().ensureActive()
                            for (source in tileSources) {
                                tileQueue.send(source to tileIndex)
                            }
                        }
                        tileQueue.close()
                        workers.forEach { it.join() }

                        if (includeDem) {
                            if (archiveDemDir == null) {
                                withContext(Dispatchers.Main.immediate) {
                                    CaltopoClient.ShowToast("DEM tile download requires a configured archive directory.")
                                }
                            } else {
                                phase = "Downloading DEM tiles"
                                for (download in demDownloads) {
                                    currentCoroutineContext().ensureActive()
                                    processGeoTiffTile(download)
                                }
                                if (demFetched.get() > 0) demElevationService.refreshGeoTiffCatalog()
                            }
                        }
                    } else {
                        val maxWorkers = 16
                        val minWorkers = 2
                        val tileQueue = Channel<Pair<OnlineTileSourceBase, Long>>(capacity = maxWorkers * 4)
                        val demQueue = Channel<DemDownload>(capacity = maxWorkers * 3)
                        val tileWorkers = mutableListOf<Job>()
                        val demWorkers = mutableListOf<Job>()

                        val tileProducer = launch {
                            val orderedTileIndexes = orderedTileIndexesForOfflinePrep(
                                bounds = bounds,
                                minZoom = preset.minZoom,
                                maxZoom = preset.maxZoom,
                                clipBoundary = clipBoundary,
                                tabletLocation = tabletLocation,
                                dronePathPoints = dronePathPoints
                            )
                            for (tileIndex in orderedTileIndexes) {
                                currentCoroutineContext().ensureActive()
                                for (source in tileSources) {
                                    tileQueue.send(source to tileIndex)
                                }
                            }
                            tileQueue.close()
                        }
                        val demProducer = if (includeDem && archiveDemDir != null) {
                            launch {
                                for (download in demDownloads) {
                                    currentCoroutineContext().ensureActive()
                                    demQueue.send(download)
                                }
                                demQueue.close()
                            }
                        } else {
                            if (includeDem && archiveDemDir == null) {
                                withContext(Dispatchers.Main.immediate) {
                                    CaltopoClient.ShowToast("DEM tile download requires a configured archive directory.")
                                }
                            }
                            demQueue.close()
                            null
                        }

                        fun addTileWorker() {
                            tileWorkers += launch {
                                for ((source, tileIndex) in tileQueue) {
                                    processTile(source, tileIndex)
                                }
                            }
                        }
                        fun addDemWorker() {
                            demWorkers += launch {
                                for (download in demQueue) {
                                    processGeoTiffTile(download)
                                }
                            }
                        }
                        fun removeTileWorker() {
                            val worker = tileWorkers.removeLastOrNull() ?: return
                            worker.cancel()
                        }
                        fun removeDemWorker() {
                            val worker = demWorkers.removeLastOrNull() ?: return
                            worker.cancel()
                        }

                        var initialTileWorkers = if (includeDem) 6 else 10
                        // Fine-resolution GeoTIFFs can be hundreds of MB; two concurrent downloads is plenty.
                        var initialDemWorkers = if (includeDem && archiveDemDir != null) 2 else 0
                        if (initialTileWorkers + initialDemWorkers > maxWorkers) {
                            initialTileWorkers = maxWorkers - initialDemWorkers
                        }
                        repeat(initialTileWorkers) { addTileWorker() }
                        repeat(initialDemWorkers) { addDemWorker() }

                        var priorRate = 0.0
                        var priorCompleted = 0
                        var ramping = true
                        val adaptiveManager = launch {
                            while (isActive) {
                                delay(8_000L)
                                val done = completed.get()
                                val delta = (done - priorCompleted).coerceAtLeast(0)
                                priorCompleted = done
                                val currentRate = delta / 8.0
                                val totalWorkers = tileWorkers.size + demWorkers.size
                                val demRemaining = (estimatedDemOps - demCompleted.get()).coerceAtLeast(0)
                                val tileRemaining = (estimatedTileOps - tileCompleted.get()).coerceAtLeast(0)

                                if (demRemaining <= 0) {
                                    while (demWorkers.isNotEmpty()) removeDemWorker()
                                    while (tileWorkers.size < maxWorkers) addTileWorker()
                                    phase = "Downloading map tiles"
                                    continue
                                }

                                phase = if (demRemaining > 0) "Downloading map + DEM" else "Downloading map tiles"
                                if (totalWorkers < minWorkers) {
                                    if (tileWorkers.isEmpty()) addTileWorker()
                                    else if (includeDem && demWorkers.isEmpty()) addDemWorker()
                                }

                                if (ramping && totalWorkers < maxWorkers) {
                                    if (priorRate > 0.0 && currentRate < priorRate * 1.03) {
                                        ramping = false
                                        if (demWorkers.size > 1) removeDemWorker() else if (tileWorkers.size > 1) removeTileWorker()
                                    } else {
                                        if (tileRemaining >= demRemaining) addTileWorker() else addDemWorker()
                                    }
                                } else if (!ramping && totalWorkers > minWorkers) {
                                    if (priorRate > 0.0 && currentRate < priorRate * 0.92) {
                                        if (demWorkers.size > 1) removeDemWorker() else if (tileWorkers.size > 1) removeTileWorker()
                                    }
                                }
                                priorRate = currentRate
                            }
                        }

                        tileProducer.join()
                        tileWorkers.toList().forEach { it.join() }
                        if (includeDem && demProducer != null) {
                            phase = "Downloading DEM tiles"
                            demProducer.join()
                            demWorkers.toList().forEach { it.join() }
                            if (demFetched.get() > 0) demElevationService.refreshGeoTiffCatalog()
                        }
                        adaptiveManager.cancel()
                    }
                    progressTicker.cancel()
                }

                val elapsedMs = System.currentTimeMillis() - startedAt
                val failTotal = totalFailed.get()
                phase = if (failTotal > 0) "Complete with failures" else "Complete"
                pushProgress(force = true)
                val hit = hits.get()
                val fetch = fetched.get()
                val tileFail = tileFailed.get()
                val demFail = demFailed.get()
                withContext(Dispatchers.Main.immediate) {
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    offlinePrepCompletedSelectionKey = selectionKey
                    offlinePrepActiveCalls.clear()
                    offlinePrepCancelRequested = false
                    offlinePrepCoordinator.finish()
                    val doneMsg =
                        if (failTotal > 0) {
                            "Download map finished with failures: hit=$hit fetched=$fetch tileFail=$tileFail demFail=$demFail totalFail=$failTotal in ${elapsedMs}ms"
                        } else {
                            "Download map done: hit=$hit fetched=$fetch tileFail=$tileFail demFail=$demFail totalFail=$failTotal in ${elapsedMs}ms"
                        }
                    CaltopoClient.ShowToast(doneMsg)
                    MapCacheDebug.log(doneMsg)
                    offlinePrepAutoCloseJob?.cancel()
                    if (failTotal == 0) {
                        offlinePrepAutoCloseJob = offlinePrepCoordinator.scope.launch {
                            delay(1500L)
                            if (!offlinePrepInFlight && offlinePrepProgress.phase == "Complete") {
                                showOfflinePrepDialog = false
                            }
                        }
                    } else {
                        offlinePrepAutoCloseJob = null
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    phase = "Cancelled"
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    offlinePrepActiveCalls.clear()
                    offlinePrepCancelRequested = false
                    offlinePrepCoordinator.finish()
                    offlinePrepAutoCloseJob?.cancel()
                    offlinePrepAutoCloseJob = null
                    offlinePrepProgress = offlinePrepProgress.copy(phase = "Cancelled")
                    CaltopoClient.ShowToast("Download map cancelled.")
                }
                MapCacheDebug.log("download map cancelled at completed=${completed.get()}")
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    phase = "Failed"
                    offlinePrepInFlight = false
                    offlinePrepJob = null
                    offlinePrepActiveCalls.clear()
                    offlinePrepCancelRequested = false
                    offlinePrepCoordinator.finish()
                    offlinePrepAutoCloseJob?.cancel()
                    offlinePrepAutoCloseJob = null
                    offlinePrepProgress = offlinePrepProgress.copy(phase = "Failed")
                    CaltopoClient.ShowToast("Download map failed: ${e.javaClass.simpleName}")
                }
                MapCacheDebug.log("download map failed err=${e.javaClass.simpleName}:${e.message}")
            }
        }
    }

    fun startArtifactHydration(
        reason: String,
        replaceWhenSnapshotEmpty: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        artifactHydrationJob?.cancel()
        val runId = artifactHydrationRunId + 1
        artifactHydrationRunId = runId
        val replaceIfEmpty = artifactStoreById.isEmpty()
        val hydrationStartVersion = artifactRenderCache.featureVersion
        val hiddenFoldersSnapshot = hiddenFolderIds.toSet()
        val hiddenItemsSnapshot = hiddenItemIds.toSet()
        val folderVisibilityOverridesSnapshot = viewModel.folderVisibilityOverrides.toMap()
        val appContext = context.applicationContext
        mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus("Reading map items")
        artifactHydrationJob = uiScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val snapshot = CaltopoMap.GetArtifactFeatureSnapshot()
                    val shouldReplace = snapshot.isNotEmpty() || replaceIfEmpty || replaceWhenSnapshotEmpty
                    if (!shouldReplace) {
                        return@withContext null
                    }
                    buildArtifactHydrationResult(
                        snapshot = snapshot,
                        hiddenFolderIds = hiddenFoldersSnapshot,
                        hiddenItemIds = hiddenItemsSnapshot,
                        folderVisibilityOverrides = folderVisibilityOverridesSnapshot,
                        pilotArchiveTrackColorForCallsign = { pilotKey ->
                            PilotDisplayPrefs.load(appContext, pilotKey).archiveTrackColor
                        }
                    ) { progress ->
                        uiScope.launch(Dispatchers.Main.immediate) {
                            if (artifactHydrationRunId == runId) {
                                mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus(
                                    label = "Hydrating map items",
                                    completed = progress.completed,
                                    total = progress.total
                                )
                            }
                        }
                    }
                }
                if (artifactHydrationRunId != runId) return@launch
                if (result != null) {
                    val autoHiddenMovedMarkerIds = movedDroneFolderMarkerIds(
                        previousFeatures = artifactStoreById,
                        incomingFeatures = result.featuresById,
                        expectedDroneFolderId = CaltopoMap.GetFolderId()
                    )
                    if (autoHiddenMovedMarkerIds.isNotEmpty()) {
                        hiddenItemIds.addAll(autoHiddenMovedMarkerIds)
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
                            MAP_PANE_TAG,
                            "Auto-hid moved Drone Tracks marker(s) from full snapshot: $autoHiddenMovedMarkerIds"
                        )
                    }
                    val currentHiddenFolders = hiddenFolderIds.toSet()
                    val currentHiddenItems = hiddenItemIds.toSet()
                    val currentFolderVisibilityOverrides = viewModel.folderVisibilityOverrides.toMap()
                    val overlayHiddenFolderIds = resolveHiddenFolderIds(
                        localHiddenFolderIds = currentHiddenFolders,
                        defaultHiddenFolderIds = result.serverHiddenFolderIds,
                        operatorVisibilityOverrides = currentFolderVisibilityOverrides
                    )
                    val overlayHiddenItemIds = currentHiddenItems + autoHiddenMovedMarkerIds
                    val cacheChangedDuringHydration = artifactRenderCache.featureVersion != hydrationStartVersion
                    val visibilityChangedDuringHydration =
                        currentHiddenFolders != hiddenFoldersSnapshot ||
                            currentHiddenItems != hiddenItemsSnapshot ||
                            currentFolderVisibilityOverrides != folderVisibilityOverridesSnapshot
                    val mergedFeatures = artifactRenderCache.mergedHydrationFeatures(
                        hydratedFeatures = result.featuresById,
                        hydrationStartVersion = hydrationStartVersion
                    )
                    val mergedOverlayState = if (
                        cacheChangedDuringHydration ||
                        visibilityChangedDuringHydration ||
                        autoHiddenMovedMarkerIds.isNotEmpty()
                    ) {
                        withContext(Dispatchers.Default) {
                            buildArtifactOverlayState(
                                mergedFeatures.values.toList(),
                                overlayHiddenFolderIds,
                                overlayHiddenItemIds,
                                pilotArchiveTrackColorForCallsign = { pilotKey ->
                                    PilotDisplayPrefs.load(appContext, pilotKey).archiveTrackColor
                                }
                            )
                        }
                    } else {
                        result.overlayState
                    }
                    artifactRenderCache.replace(mergedFeatures, mergedOverlayState)
                    result.folderDefaults.forEach { folderDefault ->
                        viewModel.applyCaltopoFolderDefault(
                            folderDefault.folderId,
                            folderDefault.initiallyVisible
                        )
                    }
                    artifactOverlayState = mergedOverlayState
                    if (MAP_PANE_VERBOSE_LOGS || mergedFeatures.isNotEmpty() || artifactOverlayState.totalFeatures > 0) {
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
                            MAP_PANE_TAG,
                            "Hydrated artifacts from snapshot ($reason): cached=${mergedFeatures.size}, " +
                                "renderable=${artifactOverlayState.totalFeatures} mergedDeltas=$cacheChangedDuringHydration"
                        )
                    }
                    if (mergedFeatures.values.any {
                            it.optJSONObject("properties")?.optString("class") == "Assignment"
                        }
                    ) {
                        CTInfo(
                            MAP_PANE_TAG,
                            "Assignment hydration ($reason): " + assignmentDiagnosticSummary(
                                features = mergedFeatures,
                                hiddenFolderIds = overlayHiddenFolderIds,
                                hiddenItemIds = overlayHiddenItemIds,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapCacheDebug.log("artifact hydration failed reason=$reason err=${e.javaClass.simpleName}:${e.message}")
            } finally {
                if (artifactHydrationRunId == runId && mapBackgroundWorkStatus?.label?.contains("map items") == true) {
                    mapBackgroundWorkStatus = null
                }
                if (artifactHydrationRunId == runId) {
                    artifactHydrationJob = null
                    onComplete?.invoke()
                    artifactHydrationRunId = runId + 1
                }
            }
        }
    }

    fun startArtifactOverlayRebuild(reason: String) {
        artifactOverlayRebuildJob?.cancel()
        val runId = artifactOverlayRebuildRunId + 1
        artifactOverlayRebuildRunId = runId
        val featuresSnapshot = artifactStoreById.values.toList()
        val hiddenFoldersSnapshot = hiddenFolderIds.toSet()
        val hiddenItemsSnapshot = hiddenItemIds.toSet()
        val appContext = context.applicationContext
        mapBackgroundWorkStatus = MapPaneBackgroundWorkStatus("Updating map display")
        artifactOverlayRebuildJob = uiScope.launch {
            try {
                val computed = withContext(Dispatchers.Default) {
                    buildArtifactOverlayState(
                        featuresSnapshot,
                        hiddenFoldersSnapshot,
                        hiddenItemsSnapshot,
                        pilotArchiveTrackColorForCallsign = { pilotKey ->
                            PilotDisplayPrefs.load(appContext, pilotKey).archiveTrackColor
                        }
                    )
                }
                if (artifactOverlayRebuildRunId != runId) return@launch
                artifactRenderCache.updateOverlay(computed)
                artifactOverlayState = computed
                if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) CTInfo(
                    MAP_PANE_TAG,
                    "Artifact overlay rebuilt reason=$reason total=${artifactOverlayState.totalFeatures} " +
                        "points=${artifactOverlayState.points.size} lines=${artifactOverlayState.lines.size} " +
                        "polygons=${artifactOverlayState.polygons.size} ignoredTrackLike=${artifactOverlayState.ignoredTrackLikeFeatures}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapCacheDebug.log("artifact overlay rebuild failed reason=$reason err=${e.javaClass.simpleName}:${e.message}")
            } finally {
                if (artifactOverlayRebuildRunId == runId && mapBackgroundWorkStatus?.label == "Updating map display") {
                    mapBackgroundWorkStatus = null
                }
                if (artifactOverlayRebuildRunId == runId) {
                    artifactOverlayRebuildJob = null
                }
            }
        }
    }

    LaunchedEffect(mapName) {
        if (artifactRenderCache.resetIfMapChanged(mapName)) {
            artifactOverlayState = cachedOverlayState()
        } else {
            val cached = cachedOverlayState()
            if (artifactOverlayState.totalFeatures == 0 && cached.totalFeatures > 0) {
                artifactOverlayState = cached
            }
        }
        val persistedViewport = viewModel.mapViewportState()?.takeIf {
            isUsableMapViewportState(it.latitude, it.longitude, it.zoom)
        }
        localTrackPointsByMappedId.clear()
        currentFlightTrackPointsByMappedId.clear()
        localTrackMappedIdsByRemoteId.clear()
        localTrackLastSeededTimestampByMappedId.clear()
        trackOverlayRefreshToken++
        lastRenderStats = ""
        lastAlignmentStats = ""
        lastCacheStats = ""
        lastLocalDeviceMarkerStats = ""
        viewportRestoreTracker.reset()
        localDeviceViewportRescueApplied = false
        viewModel.altitudeCoordinator.onMapReconnect()
        renderLatencyKeyByDesignator.clear()
        complianceByDesignator.clear()
        initialViewportApplied = persistedViewport != null
        initialViewportArtifactCount = -1
        restoredViewportStartupCheckComplete = true
        restoredViewportStartupWaitLogged = false
        restoredViewportStartupCheckStartedAtMs = System.currentTimeMillis()
        operatorAdjustedViewport = false
        localDeviceLocationMissingLogged = false
        startArtifactHydration("mapName=$mapName")
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2_000L)
            localDeviceRefreshToken++
        }
    }
    LaunchedEffect(dronePoints) {
        while (isActive) {
            val nowWallMsec = System.currentTimeMillis()
            val nextStaleAtMsec = dronePoints
                .flatMap { point ->
                    point.receivedAtMsec?.let { receivedAtMsec ->
                        listOf(
                            receivedAtMsec + DISPLAYED_POSITION_STALE_AFTER_MSEC,
                            receivedAtMsec + DISPLAYED_POSITION_VERY_STALE_AFTER_MSEC,
                        )
                    }.orEmpty()
                }
                .filter { staleAtMsec -> staleAtMsec > nowWallMsec }
                .minOrNull()
                ?: break
            delay((nextStaleAtMsec - nowWallMsec).coerceAtLeast(1L))
            positionFreshnessRefreshToken++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            artifactHydrationJob?.cancel()
            artifactOverlayRebuildJob?.cancel()
            currentMapView?.let(::persistFullMapViewport)
            currentMapView = null
            latestTileMapProvider.detach()
            latestTileFetchPriorityScheduler.close()
        }
    }

    fun pilotDisplayPreferenceFor(pilotKey: String?): PilotDisplayPreference {
        val normalized = normalizePilotCallsign(pilotKey) ?: return PilotDisplayPreference()
        return pilotDisplayPrefsByKey[normalized] ?: PilotDisplayPrefs.load(context, normalized).also {
            pilotDisplayPrefsByKey[normalized] = it
        }
    }

    fun updatePilotDisplayPreference(
        settings: PilotDisplaySettingsState,
        preference: PilotDisplayPreference
    ) {
        val normalized = normalizePilotCallsign(settings.pilotKey) ?: return
        val sanitized = preference.copy(
            activeTrackColor = sanitizeTrackColor(preference.activeTrackColor, DEFAULT_ACTIVE_TRACK_COLOR),
            archiveTrackColor = sanitizeTrackColor(preference.archiveTrackColor, DEFAULT_ARCHIVE_TRACK_COLOR)
        )
        if (PilotDisplayPrefs.save(context, normalized, sanitized)) {
            pilotDisplayPrefsByKey[normalized] = sanitized
            colorPickerTarget = colorPickerTarget?.takeIf { it.settings.pilotKey == normalized }
                ?.copy(settings = settings.copy(preference = sanitized))
            pilotDisplayRefreshToken++
            startArtifactOverlayRebuild("pilot-display-preference")
        }
    }

    fun resetPilotDisplayPreference(settings: PilotDisplaySettingsState) {
        val normalized = normalizePilotCallsign(settings.pilotKey) ?: return
        if (PilotDisplayPrefs.reset(context, normalized)) {
            val defaults = PilotDisplayPreference()
            pilotDisplayPrefsByKey[normalized] = defaults
            colorPickerTarget = null
            pilotDisplayRefreshToken++
            startArtifactOverlayRebuild("pilot-display-reset")
        }
    }

    MapPaneManagementDialogs(
        context = context,
        badTileDialogState = badTileDialogState,
        quarantineMatchingHash = quarantineMatchingHash,
        onQuarantineMatchingHashChange = { quarantineMatchingHash = it },
        onBadTileDialogStateChange = { badTileDialogState = it },
        onRemoveBadTile = { dlg, quarantine ->
            val source = tileMapProvider.tileSource
            tileCacheWriter.remove(source, dlg.tileIndex)
            if (quarantine) {
                BadTilePolicy.addBlockedHash(context, dlg.hash)
                CaltopoClient.ShowToast("Tile removed and hash quarantined.")
            } else {
                CaltopoClient.ShowToast("Tile removed from cache.")
            }
            badTileDialogState = null
        },
        showMapFoldersDialog = showMapFoldersDialog,
        onShowMapFoldersDialogChange = { showMapFoldersDialog = it },
        artifactStoreById = artifactStoreById,
        hiddenFolderIds = hiddenFolderIds,
        hiddenItemIds = hiddenItemIds,
        onFolderVisibilityChanged = { folderId, visible ->
            viewModel.setMapFolderVisibility(folderId, visible)
            startArtifactOverlayRebuild("folder-visibility")
        },
        onItemVisibilityChanged = { itemId, visible ->
            if (visible) hiddenItemIds.remove(itemId) else hiddenItemIds.add(itemId)
            startArtifactOverlayRebuild("item-visibility")
        },
        onAllItemsToggled = { itemIds, visible ->
            if (visible) hiddenItemIds.removeAll(itemIds.toSet())
            else hiddenItemIds.addAll(itemIds)
            startArtifactOverlayRebuild("bulk-item-visibility")
        },
        onZoomToItem = { itemId ->
            pendingArtifactZoomFeatureId = itemId
            operatorAdjustedViewport = true
            viewModel.clearFocus()
        },
        showBadTilesHowToDialog = showBadTilesHowToDialog,
        onShowBadTilesHowToDialogChange = { showBadTilesHowToDialog = it },
        showMapCacheSizeDialog = showMapCacheSizeDialog,
        onShowMapCacheSizeDialogChange = { showMapCacheSizeDialog = it },
        mapCacheSizeInput = mapCacheSizeInput,
        mapCacheAvailableBytes = offlinePrepAvailableBytes,
        mapCacheCurrentBytes = offlinePrepCurrentTileCacheBytes,
        onMapCacheSizeInputChange = { mapCacheSizeInput = it },
        onMapCacheSizeSaved = { bytes ->
            val reasonableMaximum = reasonableCacheMaximumBytes(
                offlinePrepCurrentTileCacheBytes,
                offlinePrepAvailableBytes
            )
            if (reasonableMaximum != null && bytes > reasonableMaximum) {
                CaltopoClient.ShowToast(
                    "Requested cache size exceeds the space reasonably available on this volume " +
                        "(${MapCacheSettings.formatDecimalGb(reasonableMaximum)} maximum recommended)."
                )
                return@MapPaneManagementDialogs
            }
            MapCacheSettings.setMaxCacheBytes(context, bytes)
            offlinePrepTileCacheCapBytes = MapCachePolicy.tileCacheMaxBytes(context)
            mapCacheSizeInput = String.format(
                Locale.US,
                "%.1f",
                MapCacheSettings.maxCacheBytes(context).toDouble() / 1_000_000_000.0
            )
            val savedBytes = MapCacheSettings.maxCacheBytes(context)
            showMapCacheSizeDialog = false
            CaltopoClient.ShowToast(
                if (savedBytes == bytes) {
                    "Map cache size saved as ${MapCacheSettings.formatDecimalGb(savedBytes)}. Startup maintenance will use it next launch."
                } else {
                    "Map cache size adjusted to ${MapCacheSettings.formatDecimalGb(savedBytes)}. Startup maintenance will use it next launch."
                }
            )
        },
        showMapTileAgeDialog = showMapTileAgeDialog,
        onShowMapTileAgeDialogChange = { showMapTileAgeDialog = it },
        mapTileAgeDaysInput = mapTileAgeDaysInput,
        onMapTileAgeDaysInputChange = { mapTileAgeDaysInput = it },
        onMapTileAgeSaved = { days ->
            MapCacheSettings.setMaxTileAgeDays(context, days)
            mapTileAgeDaysInput = MapCacheSettings.maxTileAgeDays(context).toString()
            showMapTileAgeDialog = false
            CaltopoClient.ShowToast("Maximum tile age saved. Startup cache maintenance will use the new limit next launch.")
        }
    )

    colorPickerTarget?.let { target ->
        PilotTrackColorPickerDialog(
            target = target,
            onDismiss = { colorPickerTarget = null },
            onColorSelected = { selectedColor ->
                val updatedPreference = when (target.slot) {
                    PilotDisplayColorSlot.Active -> target.settings.preference.copy(activeTrackColor = selectedColor)
                    PilotDisplayColorSlot.Archive -> target.settings.preference.copy(archiveTrackColor = selectedColor)
                }
                updatePilotDisplayPreference(target.settings, updatedPreference)
                colorPickerTarget = null
            }
        )
    }

    openBubbleDesignator?.takeIf { colorPickerTarget == null }?.let { designator ->
        val point = dronePoints.firstOrNull { it.designator == designator }
        if (point != null) {
            val coordinateDisplayFormat = viewModel.coordinateDisplayFormat
            var coordinateMenuExpanded by remember(designator, coordinateDisplayFormat) { mutableStateOf(false) }
            // AGL, ATO — read from coordinator (same values shown in the map label).
            val bubbleDisplayState = viewModel.droneDisplayStateFor(point.designator)
            val aglFeet  = bubbleDisplayState?.aglFt
            val aglStale = bubbleDisplayState?.aglStale ?: false
            val atoFeet  = bubbleDisplayState?.atoFt
            val rangeFeet = distanceFeetFromTakeoff(point)
            val telemetry = point.droneSpec?.lastPositionTelemetry
            val headingDeg = point.headingDeg ?: bubbleDisplayState?.headingDeg ?: telemetry?.aircraftTrackDeg
            val detailLines = droneDetailLines(
                locationText = CoordinateFormatter.format(point.lat, point.lng, coordinateDisplayFormat),
                coordinateFormatLabel = coordinateDisplayFormat.label,
                atoFeet = atoFeet,
                aglFeet = aglFeet,
                aglStale = aglStale,
                rangeFeet = rangeFeet,
                headingDeg = headingDeg,
                speedKnots = point.speedKnots ?: telemetry?.aircraftGsKnots,
                climbFpm = telemetry?.aircraftAltitudeRateFpm
            )
            val pilotKey = normalizePilotCallsign(point.droneSpec?.owner)
            val pilotSettings = pilotKey?.let {
                PilotDisplaySettingsState(
                    pilotKey = it,
                    displayName = point.droneSpec?.owner?.trim()?.takeIf { owner -> owner.isNotBlank() } ?: it,
                    preference = pilotDisplayPreferenceFor(it)
                )
            }
            AlertDialog(
                onDismissRequest = { openBubbleDesignator = null },
                title = { Text(point.droneSpec?.displayLabel ?: point.designator) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = detailLines.first(),
                            modifier = Modifier.clickable { coordinateMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = coordinateMenuExpanded,
                            onDismissRequest = { coordinateMenuExpanded = false }
                        ) {
                            CoordinateDisplayFormat.values().forEach { format ->
                                DropdownMenuItem(
                                    text = { Text(format.label) },
                                    onClick = {
                                        coordinateMenuExpanded = false
                                        viewModel.setCoordinateDisplayFormat(format)
                                    }
                                )
                            }
                        }
                        detailLines.drop(1).forEach { line ->
                            Text(line)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = followFocusedDroneEnabled,
                                onCheckedChange = viewModel::setFollowFocusedDroneEnabled
                            )
                            Text("Follow focused drone")
                        }
                        pilotSettings?.let { settings ->
                            Spacer(Modifier.height(12.dp))
                            PilotDisplaySettingsContent(
                                settings = settings,
                                onPreferenceChange = ::updatePilotDisplayPreference,
                                onPickColor = { slot ->
                                    colorPickerTarget = PilotColorPickerTarget(settings, slot)
                                },
                                onReset = {
                                    resetPilotDisplayPreference(settings)
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { openBubbleDesignator = null }) { Text("Close") }
                },
                dismissButton = {}
            )
        }
    }

    DisposableEffect(Unit) {
        startArtifactHydration("listener-init")
        val listener = CaltopoMap.ArtifactListener { feature, source, _ ->
            if (source == "full") {
                uiScope.launch(Dispatchers.Main.immediate) {
                    if (fullArtifactHydrationQueued) {
                        return@launch
                    }
                    fullArtifactHydrationQueued = true
                    startArtifactHydration("ingest-full", replaceWhenSnapshotEmpty = true) {
                        fullArtifactHydrationQueued = false
                    }
                }
                return@ArtifactListener
            }
            uiScope.launch(Dispatchers.Main.immediate) {
                val featureId = feature.optString("id")
                if (featureId.isBlank()) {
                    return@launch
                }

                if (isArtifactDelete(feature)) {
                    artifactRenderCache.removeFeature(featureId)
                } else {
                    val previousFeature = artifactRenderCache.featuresById[featureId]
                    val autoHiddenMovedMarkerIds = movedDroneFolderMarkerIds(
                        previousFeatures = if (previousFeature == null) emptyMap() else mapOf(featureId to previousFeature),
                        incomingFeatures = mapOf(featureId to feature),
                        expectedDroneFolderId = CaltopoMap.GetFolderId()
                    )
                    if (autoHiddenMovedMarkerIds.isNotEmpty()) {
                        hiddenItemIds.addAll(autoHiddenMovedMarkerIds)
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(
                            MAP_PANE_TAG,
                            "Auto-hid moved Drone Tracks marker(s) from artifact delta: $autoHiddenMovedMarkerIds"
                        )
                    }
                    artifactRenderCache.putFeature(featureId, feature)
                    // Auto-hide folders the server marks as not visible, on first encounter.
                    // Delegates to ViewModel so the choice persists across navigation.
                    val props = feature.optJSONObject("properties")
                    if (props?.optString("class") == "Folder") {
                        viewModel.applyCaltopoFolderDefault(featureId, props.optBoolean("visible", true))
                    } else {
                        applySyntheticArtifactFolderDefault(props, viewModel::applyCaltopoFolderDefault)
                    }
                }

                startArtifactOverlayRebuild("ingest-$source")
                if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) CTInfo(
                    MAP_PANE_TAG,
                    "Artifact ingest source=$source ${artifactLogSummary(feature)} queued overlay rebuild"
                )
            }
        }

        val cachedFeatureCount = artifactRenderCache.featuresById.size
        val replayCachedArtifacts = mapPaneShouldReplayCachedArtifacts(
            presentationMode = presentationMode,
            cachedFeatureCount = cachedFeatureCount
        )
        CaltopoMap.AddArtifactListener(listener, replayCachedArtifacts)
        CaltopoMap.SetMapArtifactForegroundVisible(true)
        if (mapPaneShouldRequestArtifactRefreshOnMount(presentationMode, cachedFeatureCount)) {
            CaltopoMap.RequestMapRefreshNow()
        }
        onDispose {
            CaltopoMap.RemoveArtifactListener(listener)
            CaltopoMap.SetMapArtifactForegroundVisible(false)
        }
    }

    fun seedActiveLocalTrackSnapshots(seedTimeMs: Long, reason: String) {
        viewModel.droneStates.forEach { (key, state) ->
            val snapshot = WaypointTrack.GetTrackPointsSnapshot(state.source)
            if (snapshot.isEmpty()) return@forEach
            if (!shouldSeedLocalTrackSnapshotForDesignator(
                    key,
                    snapshot,
                    localTrackLastSeededTimestampByMappedId
                )) {
                return@forEach
            }
            val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
            val flightList = currentFlightTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
            if (seedLocalTrackPointsFromSnapshot(key, snapshot, seedTimeMs, list, flightList)) {
                trackOverlayRefreshToken++
                if (CTDebugEnabled(ICON_LATENCY_TAG)) CTDebug(
                    ICON_LATENCY_TAG,
                    "track_seed_snapshot designator=$key points=${snapshot.size} reason=$reason"
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            viewModel.droneStates.map { (key, state) ->
                listOf(
                    key,
                    state.mappedId,
                    state.flightStartMsec.toString(),
                    state.lastTimestamp,
                    state.lastLat.toString(),
                    state.lastLng.toString()
                ).joinToString(":")
            }.sorted()
        }.collect {
            seedActiveLocalTrackSnapshots(System.currentTimeMillis(), "state")
        }
    }

    DisposableEffect(Unit) {
        val localTrackListener = CaltopoLiveTrack.LocalTrackListener { remoteId, mappedId, lat, lng, altitudeMeters, timestampMsec ->
            uiScope.launch(Dispatchers.Main.immediate) {
                if (!lat.isFinite() || !lng.isFinite()) return@launch
                if (lat == 0.0 && lng == 0.0) return@launch
                val nowWallMsec = System.currentTimeMillis()
                val key = localTrackDesignator(mappedId)
                localTrackMappedIdsByRemoteId.getOrPut(remoteId) { mutableSetOf() }.add(key)
                val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                val flightList = currentFlightTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                val point = LocalTrackPoint(
                    mappedId = key,
                    lat = lat,
                    lng = lng,
                    altitudeM = altitudeMeters,
                    timestampMsec = timestampMsec,
                    receivedAtMsec = nowWallMsec
                )
                list.add(point)
                flightList.add(point)
                trackOverlayRefreshToken++
                if (CTDebugEnabled(ICON_LATENCY_TAG))  CTDebug(
                    ICON_LATENCY_TAG,
                    "track_ingest designator=$key wall=$nowWallMsec droneTs=$timestampMsec " +
                        "lat=${"%.6f".format(Locale.US, lat)} lng=${"%.6f".format(Locale.US, lng)} alt=${"%.1f".format(Locale.US, altitudeMeters)}"
                )
                if (list.size > LOCAL_TRACK_RECENT_POINT_LIMIT) {
                    list.removeAt(0)
                }
                if (flightList.size > LOCAL_TRACK_FLIGHT_POINT_LIMIT) {
                    flightList.removeAt(0)
                }
            }
        }
        val localTrackFinishedListener = CaltopoLiveTrack.LocalTrackFinishedListener { remoteId, mappedId, _ ->
            uiScope.launch(Dispatchers.Main.immediate) {
                val mappedIds = localTrackMappedIdsByRemoteId.remove(remoteId).orEmpty() +
                    localTrackDesignator(mappedId)
                mappedIds.forEach { key ->
                    localTrackPointsByMappedId.remove(key)
                    currentFlightTrackPointsByMappedId.remove(key)
                    localTrackLastSeededTimestampByMappedId.remove(key)
                }
                trackOverlayRefreshToken++
            }
        }
        // Seed from the active WaypointTrack so reopening MapPane mid-flight preserves
        // points collected while this composable was not active.
        seedActiveLocalTrackSnapshots(System.currentTimeMillis(), "mount")
        viewModel.droneStates.forEach { (key, state) ->
            val snapshot = WaypointTrack.GetTrackPointsSnapshot(state.source)
            if (snapshot.isNotEmpty()) {
                return@forEach
            }

            // Fall back to the current drone state if no active local track snapshot exists yet.
            val seedLat = state.lastLat
            val seedLng = state.lastLng
            if (seedLat.isFinite() && seedLng.isFinite() && !(seedLat == 0.0 && seedLng == 0.0)
                    && state.source.mostRecentMsecTimestamp > 0) {
                val list = localTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                val flightList = currentFlightTrackPointsByMappedId.getOrPut(key) { mutableStateListOf() }
                if (list.isEmpty()) {
                    val point = LocalTrackPoint(
                        mappedId = key,
                        lat = seedLat,
                        lng = seedLng,
                        altitudeM = state.lastAlt,
                        timestampMsec = state.source.mostRecentMsecTimestamp,
                        receivedAtMsec = System.currentTimeMillis()
                    )
                    list.add(point)
                    if (flightList.isEmpty()) {
                        flightList.add(point)
                    }
                    trackOverlayRefreshToken++
                }
            }
        }
        CaltopoLiveTrack.AddLocalTrackListener(localTrackListener)
        CaltopoLiveTrack.AddLocalTrackFinishedListener(localTrackFinishedListener)
        onDispose {
            CaltopoLiveTrack.RemoveLocalTrackListener(localTrackListener)
            CaltopoLiveTrack.RemoveLocalTrackFinishedListener(localTrackFinishedListener)
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF1E222A))
            .clipToBounds()
    ) {
        val operatorMapGestureHandler by rememberUpdatedState<(OperatorMapGesture) -> Unit> { gesture ->
            if (isInsetMode) return@rememberUpdatedState
            if (!operatorAdjustedViewport) {
                operatorAdjustedViewport = true
                CTDebug(MAP_PANE_TAG, "Map viewport operator-adjusted by ${gesture.name.lowercase()}")
            }
            val focus = latestFocusedPath
            if (shouldReleaseFocusedDroneForMapGesture(
                    presentationMode = presentationMode,
                    hasFocusedDrone = focus != null,
                    gesture = gesture
                )
            ) {
                viewModel.clearFocus()
                CTInfo(MAP_PANE_TAG, "Map ${gesture.name.lowercase()} released focused drone $focus")
            }
        }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = {
                configureOsmdroid(context)
                MapView(context).apply {
                    currentMapView = this
                    setMultiTouchControls(!isInsetMode)
                    setTileProvider(tileMapProvider)
                    // This provider is rendered by a separate TilesOverlay, so MapView does not
                    // register its invalidation handler automatically as it does for the base
                    // provider. Redraw when an asynchronously downloaded contour tile arrives.
                    replaceTileCompletionCallback(
                        contourTileMapProvider.tileRequestCompleteHandlers,
                        tileRequestCompleteHandler
                    )
                    setUseDataConnection(true)
                    tileMapProvider.setUseDataConnection(true)
                    setMaxZoomLevel(MAP_DISPLAY_MAX_ZOOM)
                    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    var touchDownX = 0f
                    var touchDownY = 0f
                    var panGestureStarted = false
                    val doubleTapDetector = GestureDetector(
                        context,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(event: MotionEvent): Boolean = true

                            override fun onDoubleTap(event: MotionEvent): Boolean {
                                operatorMapGestureHandler(OperatorMapGesture.Zoom)
                                return false
                            }
                        }
                    )
                    setOnTouchListener { _, event ->
                        if (event == null) return@setOnTouchListener false
                        doubleTapDetector.onTouchEvent(event)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                touchDownX = event.x
                                touchDownY = event.y
                                panGestureStarted = false
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                operatorMapGestureHandler(OperatorMapGesture.Zoom)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (!panGestureStarted &&
                                    hypot(event.x - touchDownX, event.y - touchDownY) >= touchSlop
                                ) {
                                    panGestureStarted = true
                                    operatorMapGestureHandler(OperatorMapGesture.Pan)
                                }
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> panGestureStarted = false
                        }
                        false
                    }
                    val initialViewport = restoredViewport
                    if (initialViewport != null) {
                        controller.setCenter(GeoPoint(initialViewport.latitude, initialViewport.longitude))
                        controller.setZoom(
                            mapPaneInitialViewportZoom(
                                presentationMode = presentationMode,
                                restoredZoom = initialViewport.zoom,
                                maxZoom = tileMapProvider.tileSource.maximumZoomLevel.toDouble()
                            )
                        )
                    } else {
                        controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                    }
                    addMapListener(
                        object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                if (!isInsetMode) {
                                    persistFullMapViewport(this@apply)
                                }
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                if (!isInsetMode) {
                                    persistFullMapViewport(this@apply)
                                }
                                val eventZoom = event?.zoomLevel ?: zoomLevelDouble
                                val eventTileZoom = TileSystem.getInputTileZoomLevel(eventZoom)
                                if (visibleTileZoom != eventTileZoom) {
                                    val previousTileZoom = visibleTileZoom
                                    visibleTileZoom = eventTileZoom
                                    if (needsViewportTileProviderRestart(
                                            previousTileZoom = previousTileZoom,
                                            currentTileZoom = eventTileZoom,
                                            baseSourceChanged = false
                                        )
                                    ) {
                                        tileMapProvider = restartTileProviderForViewportIntent(
                                            mapView = this@apply,
                                            context = context,
                                            tileSource = latestBaseTileSource,
                                            tileWriter = tileCacheWriter,
                                            reason = "zoom-change",
                                            zoom = eventTileZoom
                                        )
                                    }
                                }
                                return false
                            }
                        }
                    )
                }
            },
            update = { mapView ->
                trackOverlayRefreshToken
                pilotDisplayRefreshToken
                localDeviceRefreshToken
                positionFreshnessRefreshToken
                val sharedMarkerInfoWindow = if (isInsetMode) {
                    null
                } else {
                    markerInfoWindow.getOrCreate(mapView) { LocalMarkerInfoWindow(mapView) }
                }
                val uiNowWallMsec = System.currentTimeMillis()
                mapBounds = mapView.boundingBox
                val tileSource = baseTileSource
                val maxZoom = MAP_DISPLAY_MAX_ZOOM
                if (mapView.maxZoomLevel != maxZoom) {
                    mapView.setMaxZoomLevel(maxZoom)
                }
                if (viewportRestoreTracker.needsRestore(mapView) && restoredViewport != null) {
                    val viewportWidth = mapView.width
                    val viewportHeight = mapView.height
                    if (viewportWidth > 0 && viewportHeight > 0) {
                        val bounds = restoredViewport.bounds
                        if (bounds?.isUsable == true) {
                            mapView.zoomToBoundingBox(
                                BoundingBox(bounds.north, bounds.east, bounds.south, bounds.west),
                                false,
                                0
                            )
                        } else {
                            mapView.controller.setCenter(
                                GeoPoint(restoredViewport.latitude, restoredViewport.longitude)
                            )
                            mapView.controller.setZoom(
                                if (isInsetMode) {
                                    mapPaneInsetViewportZoom(
                                        fullWidthPx = restoredViewport.widthPx,
                                        fullHeightPx = restoredViewport.heightPx,
                                        insetWidthPx = viewportWidth,
                                        insetHeightPx = viewportHeight,
                                        fullZoom = restoredViewport.zoom,
                                        maxZoom = baseTileSource.maximumZoomLevel.toDouble()
                                    )
                                } else {
                                    restoredViewport.zoom
                                }
                            )
                        }
                        viewportRestoreTracker.markRestored(mapView)
                        CTDebug(
                            MAP_PANE_TAG,
                            String.format(
                                Locale.US,
                                "Restored viewport bounds mode=%s measured=%dx%d center=%.6f,%.6f zoom=%.2f bounds=%s",
                                presentationMode.name,
                                viewportWidth,
                                viewportHeight,
                                mapView.mapCenter.latitude,
                                mapView.mapCenter.longitude,
                                mapView.zoomLevelDouble,
                                bounds?.isUsable == true
                            )
                        )
                    }
                }
                val currentTileZoom = TileSystem.getInputTileZoomLevel(mapView.zoomLevelDouble)
                val baseSourceChanged = needsBaseTileProviderRestart(
                    currentSourceName = mapView.tileProvider.tileSource.name(),
                    desiredTileSource = tileSource
                )
                if (baseSourceChanged) {
                    tileMapProvider = restartTileProviderForViewportIntent(
                        mapView = mapView,
                        context = context,
                        tileSource = tileSource,
                        tileWriter = tileCacheWriter,
                        reason = "base-layer-change",
                        zoom = currentTileZoom
                    )
                    tileIoActiveUntilMs = uiNowWallMsec + TILE_IO_ACTIVE_GRACE_MS
                }
                if (visibleTileZoom != currentTileZoom) {
                    val previousTileZoom = visibleTileZoom
                    visibleTileZoom = currentTileZoom
                    if (needsViewportTileProviderRestart(
                            previousTileZoom = previousTileZoom,
                            currentTileZoom = currentTileZoom,
                            baseSourceChanged = baseSourceChanged
                        )
                    ) {
                        tileMapProvider = restartTileProviderForViewportIntent(
                            mapView = mapView,
                            context = context,
                            tileSource = tileSource,
                            tileWriter = tileCacheWriter,
                            reason = "viewport-update",
                            zoom = currentTileZoom
                        )
                    }
                }

                val center = mapView.mapCenter
                val viewportSignature = String.format(
                    Locale.US,
                    "%.5f|%.5f|%.3f|%d|%d|%s|contours=%s",
                    center.latitude,
                    center.longitude,
                    mapView.zoomLevelDouble,
                    mapView.width,
                    mapView.height,
                    tileSource.name(),
                    contourOverlayEnabled
                )
                if (lastViewportSignature != viewportSignature) {
                    lastViewportSignature = viewportSignature
                    tileIoActiveUntilMs = uiNowWallMsec + TILE_IO_ACTIVE_GRACE_MS
                }
                val suppressLiveMapNetwork = offlinePrepInFlight && baseLayer == BaseLayerOption.OpenStreetMap
                val tileNetworkActive = visibleTileNetworkActive(suppressLiveMapNetwork)
                if (mapView.useDataConnection() != tileNetworkActive) {
                    mapView.setUseDataConnection(tileNetworkActive)
                }
                if (tileMapProvider.useDataConnection() != tileNetworkActive) {
                    tileMapProvider.setUseDataConnection(tileNetworkActive)
                }
                tileMapProvider.setOfflineFirst(offlineFirstForVisibleTiles(tileNetworkActive))
                tileMapProvider.setCacheLookupEnabled(true)
                contourTileMapProvider.setUseDataConnection(tileNetworkActive)
                contourTileMapProvider.setOfflineFirst(offlineFirstForVisibleTiles(tileNetworkActive))
                contourTileMapProvider.setCacheLookupEnabled(true)

                if (managedOverlays.isNotEmpty()) {
                    mapView.overlays.removeAll(managedOverlays)
                    managedOverlays.clear()
                }

                val tapOverlay = MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            InfoWindow.closeAllInfoWindowsOn(mapView)
                            if (!isInsetMode) {
                                onSingleTapFocus?.invoke()
                            }
                            return false
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            val press = p ?: return false
                            val zoom = TileSystem.getInputTileZoomLevel(mapView.zoomLevelDouble)
                            val tx = lonToTileX(press.longitude, zoom)
                            val ty = latToTileY(press.latitude, zoom)
                            val tileIndex = MapTileIndex.getTileIndex(zoom, tx, ty)
                            val source = tileMapProvider.tileSource
                            val hash = tileCacheWriter.tileHash(source, tileIndex)
                            if (hash == null) {
                                CaltopoClient.ShowToast("Selected tile is not cached yet.")
                                return false
                            }
                            quarantineMatchingHash = true
                            badTileDialogState = BadTileDialogState(
                                tileIndex = tileIndex,
                                zoom = zoom,
                                x = tx,
                                y = ty,
                                hash = hash
                            )
                            return true
                        }
                    }
                )
                mapView.overlays.add(tapOverlay)
                managedOverlays.add(tapOverlay)

                if (contourOverlayEnabled) {
                    val contourOverlay = TilesOverlay(contourTileMapProvider, context).apply {
                        setLoadingDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                        setUseDataConnection(tileNetworkActive)
                    }
                    mapView.overlays.add(contourOverlay)
                    managedOverlays.add(contourOverlay)
                }

                artifactOverlayState.polygons.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        applyPolygonStyle(
                            polygon = this,
                            strokeColor = AndroidColor.TRANSPARENT,
                            fillColor = polygonSpec.fillColor,
                            strokeWidth = 0f
                        )
                        if (!isInsetMode) {
                            setOnClickListener { _, _, _ ->
                                selectedArtifact = ArtifactInspection(polygonSpec.title, polygonSpec.description)
                                true
                            }
                        } else {
                            setOnClickListener { _, _, _ -> false }
                        }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        applyPolylineStyle(this, polygonSpec.strokeColor, polygonSpec.strokeWidth * lineScale)
                        if (!isInsetMode) {
                            setOnClickListener { _, _, _ ->
                                selectedArtifact = ArtifactInspection(polygonSpec.title, polygonSpec.description)
                                true
                            }
                        }
                    }
                    mapView.overlays.add(polygonBoundary)
                    managedOverlays.add(polygonBoundary)
                }

                val notamOverlayState = NotamMapOverlayAdapter.build(notamUiState, CaltopoMap.GetMyLocation())
                val airspaceOverlays = AirspaceMapOverlayAdapter.build(airspaceUiState)
                val landRestrictionOverlays = LandRestrictionMapOverlayAdapter.build(
                    landRestrictionUiState,
                    CaltopoClient.GetLandRestrictionsShowOnMap()
                )
                airspaceOverlays.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        applyPolygonStyle(
                            polygon = this,
                            strokeColor = AndroidColor.TRANSPARENT,
                            fillColor = polygonSpec.fillColor,
                            strokeWidth = 0f
                        )
                        setOnClickListener { _, _, _ -> false }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        applyPolylineStyle(this, polygonSpec.strokeColor, polygonSpec.strokeWidth * lineScale)
                    }
                    mapView.overlays.add(polygonBoundary)
                    managedOverlays.add(polygonBoundary)
                }
                landRestrictionOverlays.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        applyPolygonStyle(
                            polygon = this,
                            strokeColor = AndroidColor.TRANSPARENT,
                            fillColor = polygonSpec.fillColor,
                            strokeWidth = 0f
                        )
                        setOnClickListener { _, _, _ -> false }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        applyPolylineStyle(this, polygonSpec.strokeColor, polygonSpec.strokeWidth * lineScale)
                    }
                    mapView.overlays.add(polygonBoundary)
                    managedOverlays.add(polygonBoundary)
                }
                notamOverlayState.polygons.forEach { polygonSpec ->
                    val polygonFill = Polygon(mapView).apply {
                        points = polygonSpec.points
                        title = ""
                        applyPolygonStyle(
                            polygon = this,
                            strokeColor = AndroidColor.TRANSPARENT,
                            fillColor = polygonSpec.fillColor,
                            strokeWidth = 0f
                        )
                        setOnClickListener { _, _, _ -> false }
                    }
                    mapView.overlays.add(polygonFill)
                    managedOverlays.add(polygonFill)

                    val polygonBoundary = Polyline(mapView).apply {
                        setPoints(closedPolylinePoints(polygonSpec.points))
                        title = polygonSpec.title
                        applyPolylineStyle(this, polygonSpec.strokeColor, polygonSpec.strokeWidth * lineScale)
                        polygonSpec.notice?.let { notice ->
                            if (!isInsetMode) {
                                setOnClickListener { _, _, _ ->
                                    selectedNotam = notice
                                    true
                                }
                            }
                        }
                    }
                    mapView.overlays.add(polygonBoundary)
                    managedOverlays.add(polygonBoundary)
                }

                artifactOverlayState.lines.forEach { lineSpec ->
                    val line = Polyline(mapView).apply {
                        setPoints(lineSpec.points)
                        title = lineSpec.title
                        applyPolylineStyle(this, lineSpec.color, lineSpec.width * lineScale)
                        if (!isInsetMode) {
                            setOnClickListener { _, _, _ ->
                                selectedArtifact = ArtifactInspection(lineSpec.title, lineSpec.description)
                                true
                            }
                        }
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                notamOverlayState.lines.forEach { lineSpec ->
                    val line = Polyline(mapView).apply {
                        setPoints(lineSpec.points)
                        title = lineSpec.title
                        applyPolylineStyle(this, lineSpec.color, lineSpec.width * lineScale)
                        if (!isInsetMode) {
                            setOnClickListener { _, _, _ ->
                                selectedNotam = lineSpec.notice
                                true
                            }
                        }
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                val trackPointMinTimestampMs = System.currentTimeMillis() - (CaltopoClient.GetNewTrackDelayInSeconds() * 1000L)
                val expiredTrackIds = mutableListOf<String>()
                localTrackPointsByMappedId.forEach { (mappedId, points) ->
                    while (points.isNotEmpty() && points.first().receivedAtMsec <= trackPointMinTimestampMs) {
                        points.removeAt(0)
                    }
                    if (points.isEmpty()) {
                        expiredTrackIds += mappedId
                    }
                }
                expiredTrackIds.forEach { localTrackPointsByMappedId.remove(it) }

                val confirmedCurrentFlightMappedIds = confirmedCurrentFlightMappedIds(dronePoints)
                val fullFlightTrackMappedIds = fullFlightTrackMappedIds(
                    dronePoints = dronePoints,
                    eligibleMappedIds = confirmedCurrentFlightMappedIds,
                    mappedIdsByRemoteId = localTrackMappedIdsByRemoteId
                )
                val pilotPreferencesByMappedId = pilotDisplayPreferencesByMappedId(
                    dronePoints = dronePoints,
                    mappedIdsByRemoteId = localTrackMappedIdsByRemoteId,
                    preferenceForPilotKey = ::pilotDisplayPreferenceFor
                )
                currentFlightTrackPointsByMappedId.forEach { (mappedId, points) ->
                    if (mappedId !in fullFlightTrackMappedIds) return@forEach
                    if (points.size < 2) return@forEach
                    val trackColor = trackColorInt(
                        pilotPreferencesByMappedId[mappedId]?.archiveTrackColor,
                        DEFAULT_ARCHIVE_TRACK_COLOR
                    )
                    val line = Polyline(mapView).apply {
                        setPoints(points.map { GeoPoint(it.lat, it.lng) })
                        title = "Flight track: $mappedId (${points.size})"
                        applyPolylineStyle(this, trackColor, 2.0f * lineScale)
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                localTrackPointsByMappedId.forEach { (mappedId, points) ->
                    if (points.size < 2) return@forEach
                    val trackColor = trackColorInt(
                        pilotPreferencesByMappedId[mappedId]?.activeTrackColor,
                        DEFAULT_ACTIVE_TRACK_COLOR
                    )
                    val line = Polyline(mapView).apply {
                        setPoints(points.map { GeoPoint(it.lat, it.lng) })
                        title = "Local track: $mappedId (${points.size})"
                        applyPolylineStyle(this, trackColor, 4.0f * lineScale)
                    }
                    mapView.overlays.add(line)
                    managedOverlays.add(line)
                }

                viewModel.localMapMarkers.forEach { point ->
                    val markerTitle = "Local: ${point.title}"
                    val markerSnippet = localClueDetailText(point)
                    val snapshot = viewModel.clueSnapshotForTitle(point.title)
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(point.lat, point.lng)
                        icon = markerIconForArtifactSymbol(
                            resources = context.resources,
                            symbol = "clue",
                            colorHex = "#F9A825",
                            cache = symbolMarkerCache,
                            scale = markerScale
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = markerTitle
                        snippet = markerSnippet
                        if (!isInsetMode && sharedMarkerInfoWindow != null) {
                            infoWindow = sharedMarkerInfoWindow
                            setOnMarkerClickListener { tappedMarker, _ ->
                                when (markerInfoWindowTapAction(sharedMarkerInfoWindow.isOpenFor(tappedMarker))) {
                                    MarkerInfoWindowTapAction.Close -> tappedMarker.closeInfoWindow()
                                    MarkerInfoWindowTapAction.Show -> {
                                        sharedMarkerInfoWindow.close()
                                        sharedMarkerInfoWindow.bind(
                                            LocalMarkerInfoContent(
                                                titleText = markerTitle,
                                                descriptionText = markerSnippet,
                                                thumbnail = snapshot?.thumbnail,
                                                onOpenSnapshot = snapshot?.takeIf {
                                                    it.fullImage != null || it.fullImagePath != null
                                                }?.let {
                                                    {
                                                        openClueSnapshotInExternalViewer(
                                                            context,
                                                            it.title,
                                                            it.fullImage,
                                                            it.fullImagePath,
                                                        )
                                                    }
                                                },
                                                markerId = point.id,
                                                onDelete = { markerId ->
                                                    if (viewModel.deleteLocalMapMarker(markerId)) {
                                                        mapView.invalidate()
                                                    }
                                                },
                                            )
                                        )
                                        tappedMarker.showInfoWindow()
                                    }
                                }
                                true
                            }
                        }
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                artifactOverlayState.points.forEach { point ->
                    val remoteCacheKey = iconCacheService.cacheKey(point.markerSymbol, point.markerColor)
                    val remoteIcon = caltopoMarkerCache[remoteCacheKey]
                    if (remoteIcon == null && !caltopoMarkerPending.contains(remoteCacheKey)) {
                        caltopoMarkerPending.add(remoteCacheKey)
                        uiScope.launch(Dispatchers.IO) {
                            val loaded = iconCacheService.loadBestAvailableDrawable(
                                resources = context.resources,
                                markerSymbol = point.markerSymbol,
                                markerColor = point.markerColor
                            )
                            withContext(Dispatchers.Main.immediate) {
                                if (loaded != null) {
                                    caltopoMarkerCache[remoteCacheKey] = loaded
                                }
                                caltopoMarkerPending.remove(remoteCacheKey)
                            }
                        }
                    }
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(point.lat, point.lng)
                        icon = remoteIcon?.let { icon ->
                            cachedScaledRemoteMarkerDrawable(
                                resources = context.resources,
                                source = icon,
                                cache = scaledRemoteMarkerCache,
                                cacheKey = remoteCacheKey,
                                scale = markerScale
                            )
                        }
                            ?: markerIconForArtifactSymbol(
                                resources = context.resources,
                                symbol = point.markerSymbol,
                                colorHex = point.markerColor,
                                cache = symbolMarkerCache,
                                scale = markerScale
                            )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = point.title
                        snippet = point.description
                    }
                    if (!isInsetMode && sharedMarkerInfoWindow != null) {
                        val snapshot = viewModel.clueSnapshotForTitle(point.title)
                        val localCopy = localMapMarkerForArtifact(
                            markers = viewModel.localMapMarkers,
                            artifactTitle = point.title,
                            artifactLat = point.lat,
                            artifactLng = point.lng,
                        )
                        val viewableSnapshot = snapshot?.takeIf {
                            it.fullImage != null || it.fullImagePath != null
                        }
                        marker.infoWindow = sharedMarkerInfoWindow
                        marker.setOnMarkerClickListener { tappedMarker, _ ->
                            when (markerInfoWindowTapAction(sharedMarkerInfoWindow.isOpenFor(tappedMarker))) {
                                MarkerInfoWindowTapAction.Close -> tappedMarker.closeInfoWindow()
                                MarkerInfoWindowTapAction.Show -> {
                                    sharedMarkerInfoWindow.close()
                                    sharedMarkerInfoWindow.bind(
                                        LocalMarkerInfoContent(
                                            titleText = point.title,
                                            descriptionText = point.description,
                                            thumbnail = snapshot?.thumbnail,
                                            onOpenSnapshot = viewableSnapshot?.let { captured ->
                                                {
                                                    openClueSnapshotInExternalViewer(
                                                        context,
                                                        captured.title,
                                                        captured.fullImage,
                                                        captured.fullImagePath,
                                                    )
                                                }
                                            },
                                            markerId = localCopy?.id,
                                            onDelete = localCopy?.let {
                                                { markerId ->
                                                    if (viewModel.deleteLocalMapMarker(markerId)) {
                                                        mapView.invalidate()
                                                    }
                                                }
                                            },
                                        )
                                    )
                                    tappedMarker.showInfoWindow()
                                }
                            }
                            true
                        }
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    if (!isKnownArtifactSymbol(point.markerSymbol) && unknownSymbolsSeen.add(point.markerSymbol)) {
                        if (CTDebugEnabled(MAP_PANE_TAG))  CTDebug(MAP_PANE_TAG, "Unknown marker-symbol encountered: '${point.markerSymbol}'")
                    }
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                notamOverlayState.points.forEach { point ->
                    val marker = Marker(mapView).apply {
                        position = point.point
                        icon = scaleDrawableBitmap(
                            resources = context.resources,
                            drawable = buildNotamMarkerIcon(context, point.color),
                            scale = markerScale
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = point.title
                        if (!isInsetMode) {
                            setOnMarkerClickListener { _, _ ->
                                if (point.notices.size == 1) {
                                    selectedNotamGroup = null
                                    selectedNotam = point.notices.first()
                                } else {
                                    selectedNotam = null
                                    selectedNotamGroup = point.notices
                                }
                                true
                            }
                        }
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)
                }

                val myLocation = CaltopoMap.GetMyLocation()
                if (myLocation != null && myLocation.latitude.isFinite() && myLocation.longitude.isFinite()) {
                    CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()
                    val localDeviceColor = localDeviceMarkerColor()
                    val localCacheKey = iconCacheService.cacheKey(LOCAL_DEVICE_SYMBOL, localDeviceColor)
                    val localRemoteIcon = caltopoMarkerCache[localCacheKey]
                    if (localRemoteIcon == null && !caltopoMarkerPending.contains(localCacheKey)) {
                        caltopoMarkerPending.add(localCacheKey)
                        uiScope.launch(Dispatchers.IO) {
                            val loaded = iconCacheService.loadBestAvailableDrawable(
                                resources = context.resources,
                                markerSymbol = LOCAL_DEVICE_SYMBOL,
                                markerColor = localDeviceColor
                            )
                            withContext(Dispatchers.Main.immediate) {
                                if (loaded != null) {
                                    caltopoMarkerCache[localCacheKey] = loaded
                                }
                                caltopoMarkerPending.remove(localCacheKey)
                            }
                        }
                    }
                    val localMarker = Marker(mapView).apply {
                        position = GeoPoint(myLocation.latitude, myLocation.longitude)
                        val baseIcon = localRemoteIcon?.let { icon ->
                            cachedScaledRemoteMarkerDrawable(
                                resources = context.resources,
                                source = icon,
                                cache = scaledRemoteMarkerCache,
                                cacheKey = localCacheKey,
                                scale = markerScale
                            )
                        }
                            ?: markerIconForArtifactSymbol(
                                resources = context.resources,
                                symbol = LOCAL_DEVICE_SYMBOL,
                                colorHex = localDeviceColor,
                                cache = symbolMarkerCache,
                                scale = markerScale
                            )
                        icon = cachedWhiteOutlinedMarkerDrawable(
                            resources = context.resources,
                            source = baseIcon,
                            cache = localDeviceOutlinedMarkerCache,
                            cacheKey = "$localCacheKey|$markerScale"
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        val localDeviceName = R2CActivity.MyDeviceName
                        title = if (localDeviceName.isBlank() || localDeviceName == "<unknown>") {
                            "RID2Caltopo Device"
                        } else {
                            localDeviceName
                        }
                        val statusLines = localDeviceStatusLines()
                        snippet = statusLines.firstOrNull().orEmpty()
                        setSubDescription(statusLines.drop(1).joinToString("<br/>"))
                    }
                    consumeInsetMarkerTaps(localMarker, isInsetMode)
                    mapView.overlays.add(localMarker)
                    managedOverlays.add(localMarker)
                    val mapCenter = mapView.mapCenter
                    val localDeviceVisible = mapView.boundingBox.containsLocation(myLocation)
                    val defaultViewportCenter =
                        kotlin.math.abs(mapCenter.latitude) < 0.000001 &&
                            kotlin.math.abs(mapCenter.longitude) < 0.000001
                    val operationalContentPresent =
                        dronePoints.isNotEmpty() || artifactOverlayState.totalFeatures > 0
                    if (shouldRescueLocalDeviceViewport(
                            hasRestoredViewport = restoredViewport != null,
                            presentationMode = presentationMode,
                            rescueAlreadyApplied = localDeviceViewportRescueApplied,
                            localDeviceVisible = localDeviceVisible,
                            defaultViewportCenter = defaultViewportCenter,
                            operationalContentPresent = operationalContentPresent,
                            operatorAdjustedViewport = operatorAdjustedViewport
                        )
                    ) {
                        mapView.controller.setCenter(GeoPoint(myLocation.latitude, myLocation.longitude))
                        mapView.controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                        if (!isInsetMode) {
                            persistFullMapViewport(mapView)
                        }
                        initialViewportApplied = true
                        restoredViewportStartupCheckComplete = true
                        localDeviceViewportRescueApplied = true
                        CTDebug(
                            MAP_PANE_TAG,
                            String.format(
                                Locale.US,
                                "Local device viewport rescue: lat=%.6f lng=%.6f previousCenter=%.6f,%.6f zoom=%.2f",
                                myLocation.latitude,
                                myLocation.longitude,
                                mapCenter.latitude,
                                mapCenter.longitude,
                                mapView.zoomLevelDouble
                            )
                        )
                    }
                    val localDeviceMarkerStats = String.format(
                        Locale.US,
                        "lat=%.6f lng=%.6f center=%.6f,%.6f zoom=%.2f contains=%s",
                        myLocation.latitude,
                        myLocation.longitude,
                        mapView.mapCenter.latitude,
                        mapView.mapCenter.longitude,
                        mapView.zoomLevelDouble,
                        mapView.boundingBox.containsLocation(myLocation)
                    )
                    if (localDeviceMarkerStats != lastLocalDeviceMarkerStats) {
                        lastLocalDeviceMarkerStats = localDeviceMarkerStats
                        localDeviceLocationMissingLogged = false
                        if (CTDebugEnabled(MAP_PANE_TAG)) {
                            CTDebug(
                                MAP_PANE_TAG,
                                "Local device marker: $localDeviceMarkerStats ageMs=${locationAgeMs(myLocation, uiNowWallMsec)}"
                            )
                        }
                    }
                } else if (!localDeviceLocationMissingLogged) {
                    localDeviceLocationMissingLogged = true
                    CTDebug(MAP_PANE_TAG, "Local device marker skipped: CaltopoMap.GetMyLocation() unavailable.")
                }

                val iconLimitAglM = AGL_LIMIT_FT * FT_TO_METERS
                val nearIconAglM = (AGL_LIMIT_FT - AGL_ICON_NEAR_DELTA_FT) * FT_TO_METERS
                val droneLabelSpecs = mutableListOf<DroneLabelDrawSpec>()
                val cameraFovSpecs = mutableListOf<CameraFovDrawSpec>()
                dronePoints.forEach { point ->
                    val positionStale = isDisplayedPositionStale(
                        nowWallMsec = uiNowWallMsec,
                        receivedAtMsec = point.receivedAtMsec,
                    )
                    val positionIconAlpha = displayedPositionIconAlpha(
                        nowWallMsec = uiNowWallMsec,
                        receivedAtMsec = point.receivedAtMsec,
                    )
                    val pointLatencyKey =
                        "${point.timestampMsec}|${"%.6f".format(Locale.US, point.lat)}|${"%.6f".format(Locale.US, point.lng)}|${"%.1f".format(Locale.US, point.altitudeM)}|${point.headingDeg?.let { "%.1f".format(Locale.US, it) } ?: "n/a"}|${point.cameraAzimuthDeg?.let { "%.1f".format(Locale.US, it) } ?: "n/a"}|${point.horizontalCameraFovDeg?.let { "%.1f".format(Locale.US, it) } ?: "n/a"}"
                    if (renderLatencyKeyByDesignator[point.designator] != pointLatencyKey) {
                        val renderWallMsec = System.currentTimeMillis()
                        val ingestToRenderMs = point.receivedAtMsec?.let { renderWallMsec - it }
                        if (CTDebugEnabled(ICON_LATENCY_TAG)) CTDebug(
                            ICON_LATENCY_TAG,
                            "icon_render designator=${point.designator} wall=$renderWallMsec droneTs=${point.timestampMsec} " +
                                "lat=${"%.6f".format(Locale.US, point.lat)} lng=${"%.6f".format(Locale.US, point.lng)} " +
                                "alt=${"%.1f".format(Locale.US, point.altitudeM)} heading=${point.headingDeg?.let { "%.1f".format(Locale.US, it) } ?: "n/a"} " +
                                "cameraAzimuth=${point.cameraAzimuthDeg?.let { "%.1f".format(Locale.US, it) } ?: "n/a"} horizontalFov=${point.horizontalCameraFovDeg?.let { "%.1f".format(Locale.US, it) } ?: "n/a"} " +
                                "trackToRenderMs=${ingestToRenderMs?.toString() ?: "n/a"}"
                        )
                        renderLatencyKeyByDesignator[point.designator] = pointLatencyKey
                    }
                    val predictedHead = if (predictiveHeadEnabled) {
                        predictedHeadPoint(
                            designator = point.designator,
                            nowWallMsec = uiNowWallMsec,
                            dronePointTimestampMsec = point.timestampMsec,
                            tracksByMappedId = localTrackPointsByMappedId
                        )
                    } else {
                        null
                    }
                    val renderLat = predictedHead?.lat ?: point.lat
                    val renderLng = predictedHead?.lng ?: point.lng
                    val cameraFov = cameraFovBoundaryBearings(
                        point.cameraAzimuthDeg,
                        point.horizontalCameraFovDeg
                    )
                    if (cameraFov != null) {
                        cameraFovSpecs.add(
                            CameraFovDrawSpec(
                                position = GeoPoint(renderLat, renderLng),
                                leftBearingDeg = cameraFov.leftBearingDeg,
                                rightBearingDeg = cameraFov.rightBearingDeg,
                                scale = markerScale
                            )
                        )
                    }
                    // AGL, ATO, heading — all computed by DroneAltitudeCoordinator.
                    val displayState = viewModel.droneDisplayStateFor(point.designator)
                    val headingDeg  = point.headingDeg ?: displayState?.headingDeg
                    val labelAglFeet = displayState?.aglFt
                    val labelAglStale = displayState?.aglStale ?: false
                    val labelAtoFeet = displayState?.atoFt
                    val labelRangeFeet = distanceFeetFromTakeoff(point, renderLat, renderLng)
                    val pilotKey = normalizePilotCallsign(point.droneSpec?.owner)
                    val pilotPreference = pilotDisplayPreferenceFor(pilotKey)
                    // Always compute the short marker arrow from recent visible movement.
                    // The optional long bearing line uses this exact same value.
                    val travelBearingDeg = travelBearingDegrees(
                        localTrackPointsByMappedId[localTrackDesignator(point.designator)].orEmpty()
                    )
                    if (pilotPreference.bearingEnabled) {
                        val markerGeoPoint = GeoPoint(renderLat, renderLng)
                        val startPoint = Point()
                        mapView.projection.toPixels(markerGeoPoint, startPoint)
                        if (startPoint.x in 0..mapView.width && startPoint.y in 0..mapView.height) {
                            val bearingLine = bearingLineToViewportEdge(
                                startX = startPoint.x.toDouble(),
                                startY = startPoint.y.toDouble(),
                                headingDeg = travelBearingDeg,
                                viewportWidth = mapView.width,
                                viewportHeight = mapView.height
                            )
                            val endPoint = bearingLine?.let {
                                mapView.projection.fromPixels(
                                    it.endX.roundToInt(),
                                    it.endY.roundToInt()
                                )
                            }
                            if (endPoint != null &&
                                endPoint.latitude.isFinite() &&
                                endPoint.longitude.isFinite()
                            ) {
                                val bearingOverlay = Polyline(mapView).apply {
                                    setPoints(
                                        listOf(
                                            markerGeoPoint,
                                            GeoPoint(endPoint.latitude, endPoint.longitude)
                                        )
                                    )
                                    title = "Bearing: ${point.designator}"
                                    applyPolylineStyle(
                                        this,
                                        trackColorInt(pilotPreference.activeTrackColor, DEFAULT_ACTIVE_TRACK_COLOR),
                                        2.0f * lineScale
                                    )
                                }
                                mapView.overlays.add(bearingOverlay)
                                managedOverlays.add(bearingOverlay)
                            }
                        }
                    }
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(renderLat, renderLng)
                        val effectiveAglM = labelAglFeet?.let { it / METERS_TO_FEET }
                            ?: Double.NEGATIVE_INFINITY
                        val markerTint = when {
                            effectiveAglM >= iconLimitAglM -> AndroidColor.parseColor("#D32F2F")
                            effectiveAglM >= nearIconAglM -> AndroidColor.parseColor("#FBC02D")
                            else -> null
                        }
                        icon = buildDroneMarkerDrawable(
                            resources = context.resources,
                            baseIcon = droneMarkerIcon,
                            tint = markerTint,
                            headingDeg = travelBearingDeg,
                            scale = markerScale,
                            positionIconAlpha = positionIconAlpha,
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        if (!isInsetMode) {
                            setOnMarkerClickListener { tappedMarker, _ ->
                                if (focusedPath != point.designator) {
                                    viewModel.toggleFocus(point.designator)
                                }
                                if (tappedMarker.isInfoWindowShown) {
                                    tappedMarker.closeInfoWindow()
                                    openBubbleDesignator = null
                                } else {
                                    openBubbleDesignator = point.designator
                                }
                                true
                            }
                        }
                    }
                    consumeInsetMarkerTaps(marker, isInsetMode)
                    mapView.overlays.add(marker)
                    managedOverlays.add(marker)

                    if (!isInsetMode) {
                        val labelText = droneStatusLabelText(
                            atoFeet = labelAtoFeet,
                            aglFeet = labelAglFeet,
                            aglStale = labelAglStale,
                            rangeFeet = labelRangeFeet,
                            headingDeg = headingDeg,
                            positionStale = positionStale,
                        )
                        val nameDrawable = buildDroneNameLabelDrawable(
                            context.resources,
                            point.droneSpec?.displayLabel ?: point.designator
                        )
                        val statusDrawable = buildDroneStatusLabelDrawable(context.resources, labelText)
                        droneLabelSpecs.add(
                            DroneLabelDrawSpec(
                                designator = point.designator,
                                position = GeoPoint(renderLat, renderLng),
                                nameDrawable = nameDrawable,
                                statusDrawable = statusDrawable
                            )
                        )
                    }
                }
                if (cameraFovSpecs.isNotEmpty()) {
                    val cameraFovOverlay = CameraFovOverlay(cameraFovSpecs, context.resources)
                    mapView.overlays.add(cameraFovOverlay)
                    managedOverlays.add(cameraFovOverlay)
                }
                if (droneLabelSpecs.isNotEmpty()) {
                    val labelOverlay = DroneLabelOverlay(droneLabelSpecs)
                    mapView.overlays.add(labelOverlay)
                    managedOverlays.add(labelOverlay)
                }

                val limitAglM = AGL_LIMIT_FT * FT_TO_METERS
                val limitRangeM = RANGE_LIMIT_FT * FT_TO_METERS
                val nearAglM = limitAglM * NEAR_LIMIT_RATIO
                val nearRangeM = limitRangeM * NEAR_LIMIT_RATIO

                val activeDesignators = dronePoints.map { it.designator }.toSet()
                complianceByDesignator.keys.toList().forEach { key ->
                    if (!activeDesignators.contains(key)) {
                        complianceByDesignator.remove(key)
                    }
                }
                dronePoints.forEach { point ->
                    val compDisplayState = viewModel.droneDisplayStateFor(point.designator)
                    val aglM    = compDisplayState?.aglFt?.let { it / METERS_TO_FEET }
                    val staleDem = compDisplayState?.aglStale ?: false
                    val rangeM = distanceFeetFromTakeoff(point)?.let { it * FT_TO_METERS }
                    val altitudeAlarmEnabled = point.droneSpec?.isLocalArchiveOnly != true
                    val nearAgl = altitudeAlarmEnabled && aglM != null && aglM >= nearAglM
                    val overAgl = altitudeAlarmEnabled && aglM != null && aglM >= limitAglM
                    val nearRange = rangeM != null && rangeM >= nearRangeM
                    val overRange = rangeM != null && rangeM >= limitRangeM
                    complianceByDesignator[point.designator] = DroneComplianceState(
                        aglM = aglM,
                        rangeFromTakeoffM = rangeM,
                        nearAgl = nearAgl,
                        nearRange = nearRange,
                        overAgl = overAgl,
                        overRange = overRange,
                        staleDem = staleDem
                    )
                }

                val focusedCompliance = focusedPath?.let { complianceByDesignator[it] }
                val anyOver = complianceByDesignator.values.any { it.overAgl || it.overRange }
                val anyNear = complianceByDesignator.values.any { it.nearAgl || it.nearRange }
                val severity = when {
                    focusedCompliance != null && (focusedCompliance.overAgl || focusedCompliance.overRange) -> AlertSeverity.Over
                    focusedCompliance != null && (focusedCompliance.nearAgl || focusedCompliance.nearRange) -> AlertSeverity.Near
                    anyOver -> AlertSeverity.Over
                    anyNear -> AlertSeverity.Near
                    else -> AlertSeverity.None
                }

                val renderStats =
                    "features=${artifactOverlayState.totalFeatures} points=${artifactOverlayState.points.size} " +
                        "lines=${artifactOverlayState.lines.size} polygons=${artifactOverlayState.polygons.size} " +
                        "ignoredTrackLike=${artifactOverlayState.ignoredTrackLikeFeatures} " +
                        "localTracks=${localTrackPointsByMappedId.size} drones=${dronePoints.size} " +
                        "localHeadOverrides=$localTailHeadOverrideCount"
                if (MAP_PANE_VERBOSE_LOGS && renderStats != lastRenderStats) {
                    lastRenderStats = renderStats
                    if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(MAP_PANE_TAG, "Artifact render stats: $renderStats")
                }

                val focusPoint = dronePoints.firstOrNull { it.designator == focusedPath }
                    ?: dronePoints.firstOrNull()
                if (focusPoint != null) {
                    val nearestArtifactMeters =
                        nearestDistanceMeters(focusPoint, allArtifactGeoPoints(artifactOverlayState))
                    val tailDeltaMeters = nearestLocalTrackTailDistanceMeters(focusPoint, localTrackPointsByMappedId)
                    val alignStats = if (nearestArtifactMeters != null) {
                        "focus=${focusPoint.designator} lat=${"%.6f".format(focusPoint.lat)} " +
                            "lng=${"%.6f".format(focusPoint.lng)} nearestArtifactM=${"%.1f".format(nearestArtifactMeters)} " +
                            "localTailDeltaM=${tailDeltaMeters?.let { "%.1f".format(it) } ?: "n/a"} " +
                            "artifactFeatures=${artifactOverlayState.totalFeatures}"
                    } else {
                        "focus=${focusPoint.designator} lat=${"%.6f".format(focusPoint.lat)} " +
                            "lng=${"%.6f".format(focusPoint.lng)} nearestArtifactM=n/a " +
                            "localTailDeltaM=${tailDeltaMeters?.let { "%.1f".format(it) } ?: "n/a"} " +
                            "artifactFeatures=${artifactOverlayState.totalFeatures}"
                    }
                    if (MAP_PANE_VERBOSE_LOGS && alignStats != lastAlignmentStats) {
                        lastAlignmentStats = alignStats
                        if (CTDebugEnabled(MAP_PANE_TAG)) CTDebug(MAP_PANE_TAG, "Drone alignment: $alignStats")
                    }
                }

                val restoredStartupViewport = restoredViewport
                if (!isInsetMode && !restoredViewportStartupCheckComplete && restoredStartupViewport != null) {
                    val startupMyLocation = CaltopoMap.GetMyLocation()
                    val startupLocationValid =
                        startupMyLocation != null &&
                            startupMyLocation.latitude.isFinite() &&
                            startupMyLocation.longitude.isFinite()
                    val startupLocationAgeMs =
                        startupMyLocation?.let { locationAgeMs(it, uiNowWallMsec) } ?: Long.MAX_VALUE
                    val startupLocationFresh =
                        startupLocationValid && startupLocationAgeMs <= STARTUP_MY_LOCATION_FRESH_MS
                    val operationalContentPresent =
                        dronePoints.isNotEmpty() || artifactOverlayState.totalFeatures > 0
                    val viewportContainsMyLocation =
                        startupMyLocation?.let { mapView.boundingBox.containsLocation(it) } ?: false
                    val restoredViewportUsefulForMyLocation =
                        viewportContainsMyLocation && mapView.zoomLevelDouble >= STARTUP_MY_LOCATION_MIN_ZOOM
                    val timedOut =
                        uiNowWallMsec - restoredViewportStartupCheckStartedAtMs >= STARTUP_MY_LOCATION_WAIT_MS
                    var action: String? = null

                    when {
                        operationalContentPresent -> {
                            action = "kept-operational-content"
                            restoredViewportStartupCheckComplete = true
                        }

                        startupLocationFresh && restoredViewportUsefulForMyLocation -> {
                            action = "kept-my-location-visible"
                            restoredViewportStartupCheckComplete = true
                        }

                        operatorAdjustedViewport -> {
                            action = "kept-operator-adjusted-viewport"
                            restoredViewportStartupCheckComplete = true
                        }

                        startupLocationValid && !restoredViewportUsefulForMyLocation -> {
                            mapView.controller.setCenter(GeoPoint(startupMyLocation.latitude, startupMyLocation.longitude))
                            mapView.controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            initialViewportApplied = true
                            initialViewportArtifactCount = artifactOverlayState.totalFeatures
                            action = if (startupLocationFresh) {
                                "centered-on-my-location"
                            } else {
                                "centered-on-stale-my-location"
                            }
                            restoredViewportStartupCheckComplete = true
                        }

                        timedOut -> {
                            action = "kept-restored-no-fresh-location"
                            restoredViewportStartupCheckComplete = true
                        }

                        !restoredViewportStartupWaitLogged -> {
                            action = "waiting-for-fresh-location"
                            restoredViewportStartupWaitLogged = true
                        }
                    }

                    action?.let {
                        CTDebug(
                            MAP_PANE_TAG,
                            String.format(
                                Locale.US,
                                "Startup viewport check: restoredLat=%.6f restoredLng=%.6f restoredZoom=%.2f " +
                                    "myLocation=%s locationAgeMs=%d locationFresh=%s operationalContent=%s " +
                                    "viewportContainsMyLocation=%s restoredUseful=%s currentZoom=%.2f action=%s",
                                restoredStartupViewport.latitude,
                                restoredStartupViewport.longitude,
                                restoredStartupViewport.zoom,
                                startupMyLocation?.let { loc ->
                                    String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
                                } ?: "none",
                                startupLocationAgeMs,
                                startupLocationFresh,
                                operationalContentPresent,
                                viewportContainsMyLocation,
                                restoredViewportUsefulForMyLocation,
                                mapView.zoomLevelDouble,
                                it
                            )
                        )
                    }
                }

                val shouldApplyInitialViewport =
                    !isInsetMode && !operatorAdjustedViewport && (
                        !initialViewportApplied ||
                        (
                            initialViewportArtifactCount == 0 &&
                                artifactOverlayState.totalFeatures > 0 &&
                                viewModel.mapViewportState() == null
                        )
                    )
                if (shouldApplyInitialViewport) {
                    val myLocation = CaltopoMap.GetMyLocation()
                    val artifactPoints = allArtifactGeoPoints(artifactOverlayState)
                    val viewportPoints = ArrayList<GeoPoint>(artifactPoints.size + 1).apply {
                        addAll(artifactPoints)
                        if (myLocation != null) add(GeoPoint(myLocation.latitude, myLocation.longitude))
                    }
                    var appliedInitialViewportThisUpdate = false
                    when {
                        mapPaneCanZoomToBoundingBox(
                            mapWidthPx = mapView.width,
                            mapHeightPx = mapView.height,
                            pointCount = viewportPoints.size
                        ) -> {
                            val bounds = boundingBoxFromPoints(viewportPoints)
                            mapView.zoomToBoundingBox(bounds, true, 96)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            CTDebug(
                                MAP_PANE_TAG,
                                String.format(
                                    Locale.US,
                                    "Initial viewport: mode=bounds myLocation=%s artifactPts=%d center=%.6f,%.6f zoom=%.2f",
                                    myLocation != null,
                                    artifactPoints.size,
                                    mapView.mapCenter.latitude,
                                    mapView.mapCenter.longitude,
                                    mapView.zoomLevelDouble
                                )
                            )
                            appliedInitialViewportThisUpdate = true
                        }

                        viewportPoints.isNotEmpty() -> {
                            val point = viewportPoints.first()
                            mapView.controller.setCenter(point)
                            mapView.controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            CTDebug(
                                MAP_PANE_TAG,
                                String.format(
                                    Locale.US,
                                    "Initial viewport: mode=single-point pointCount=%d measured=%sx%s center=%.6f,%.6f zoom=%.2f",
                                    viewportPoints.size,
                                    mapView.width,
                                    mapView.height,
                                    mapView.mapCenter.latitude,
                                    mapView.mapCenter.longitude,
                                    mapView.zoomLevelDouble
                                )
                            )
                            appliedInitialViewportThisUpdate = mapView.width > 0 && mapView.height > 0
                        }

                        myLocation != null -> {
                            mapView.controller.setCenter(GeoPoint(myLocation.latitude, myLocation.longitude))
                            mapView.controller.setZoom(15.0)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            CTDebug(
                                MAP_PANE_TAG,
                                String.format(
                                    Locale.US,
                                    "Initial viewport: mode=my-location lat=%.6f lng=%.6f ageMs=%d center=%.6f,%.6f zoom=%.2f",
                                    myLocation.latitude,
                                    myLocation.longitude,
                                    locationAgeMs(myLocation, uiNowWallMsec),
                                    mapView.mapCenter.latitude,
                                    mapView.mapCenter.longitude,
                                    mapView.zoomLevelDouble
                                )
                            )
                            appliedInitialViewportThisUpdate = true
                        }

                        focusPoint != null -> {
                            mapView.controller.setCenter(GeoPoint(focusPoint.lat, focusPoint.lng))
                            mapView.controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            CTDebug(
                                MAP_PANE_TAG,
                                String.format(
                                    Locale.US,
                                    "Initial viewport: mode=focused-drone lat=%.6f lng=%.6f center=%.6f,%.6f zoom=%.2f",
                                    focusPoint.lat,
                                    focusPoint.lng,
                                    mapView.mapCenter.latitude,
                                    mapView.mapCenter.longitude,
                                    mapView.zoomLevelDouble
                                )
                            )
                            appliedInitialViewportThisUpdate = true
                        }
                    }
                    initialViewportApplied = appliedInitialViewportThisUpdate
                    initialViewportArtifactCount = artifactOverlayState.totalFeatures
                }

                if (!isInsetMode) proximityMapFocusTarget?.let { focusTarget ->
                    val focusPoints = listOf(
                        GeoPoint(focusTarget.firstLat, focusTarget.firstLng),
                        GeoPoint(focusTarget.secondLat, focusTarget.secondLng)
                    )
                    val samePoint =
                        kotlin.math.abs(focusTarget.firstLat - focusTarget.secondLat) < 1e-7 &&
                            kotlin.math.abs(focusTarget.firstLng - focusTarget.secondLng) < 1e-7
                    if (samePoint || !mapPaneCanZoomToBoundingBox(mapView.width, mapView.height, focusPoints.size)) {
                        mapView.controller.setCenter(focusPoints.first())
                        mapView.controller.setZoom(MAP_DISPLAY_MAX_ZOOM)
                    } else {
                        mapView.zoomToBoundingBox(boundingBoxFromPoints(focusPoints), true, 96)
                    }
                    if (mapView.width > 0 && mapView.height > 0) {
                        if (!isInsetMode) {
                            persistFullMapViewport(mapView)
                        }
                        initialViewportApplied = true
                        viewModel.clearProximityMapFocus(focusTarget.requestId)
                    }
                }

                pendingArtifactZoomFeatureId?.let { featureId ->
                    val points = artifactStoreById[featureId]?.let(::artifactGeoPoints).orEmpty()
                    if (points.isNotEmpty() && mapView.width > 0 && mapView.height > 0) {
                        if (points.size == 1) {
                            mapView.controller.animateTo(points.first(), MAP_DISPLAY_MAX_ZOOM, 500L)
                        } else {
                            mapView.zoomToBoundingBox(boundingBoxFromPoints(points), true, 96)
                        }
                        if (!isInsetMode) persistFullMapViewport(mapView)
                        CTInfo(MAP_PANE_TAG, "Zoomed to assignment featureId=$featureId points=${points.size}")
                        pendingArtifactZoomFeatureId = null
                        initialViewportApplied = true
                    }
                }

                val now = System.currentTimeMillis()
                if (!cacheStatsQueryInFlight && now >= nextCacheStatsLogAtMs) {
                    cacheStatsQueryInFlight = true
                    nextCacheStatsLogAtMs = now + 15_000L
                    uiScope.launch(Dispatchers.IO) {
                        val iconStats = iconCacheService.statsSnapshot()
                        val tileStats = tileCacheWriter.statsSnapshot()
                        val demStats = demElevationService.statsSnapshot()
                        val statsLine =
                            "CacheStats icon(hit=${iconStats.hits} miss=${iconStats.misses} stale=${iconStats.staleServed} " +
                                "evict=${iconStats.evictions} bytes=${iconStats.bytesUsed}) " +
                                "tile(hit=${tileStats.hits} miss=${tileStats.misses} stale=${tileStats.staleServed} " +
                                "evict=${tileStats.evictions} bytes=${tileStats.bytesUsed}) " +
                                "dem(hit=${demStats.hits} miss=${demStats.misses} stale=${demStats.staleServed} " +
                                "evict=${demStats.evictions} bytes=${demStats.bytesUsed})"
                        withContext(Dispatchers.Main.immediate) {
                            if (statsLine != lastCacheStats) {
                                lastCacheStats = statsLine
                                CTDebug(MAP_PANE_TAG, statsLine)
                            }
                            if (MapCacheDebug.isLudicrousEnabled()) {
                                val iconDeltaHits = (iconStats.hits - prevIconHits).coerceAtLeast(0L)
                                val iconDeltaMisses = (iconStats.misses - prevIconMisses).coerceAtLeast(0L)
                                val tileDeltaHits = (tileStats.hits - prevTileHits).coerceAtLeast(0L)
                                val tileDeltaMisses = (tileStats.misses - prevTileMisses).coerceAtLeast(0L)
                                val demDeltaHits = (demStats.hits - prevDemHits).coerceAtLeast(0L)
                                val demDeltaMisses = (demStats.misses - prevDemMisses).coerceAtLeast(0L)

                                fun formatHitRate(hits: Long, misses: Long): String {
                                    val total = hits + misses
                                    if (total <= 0L) return "--"
                                    val pct = (hits.toDouble() * 100.0) / total.toDouble()
                                    return "%.1f%%".format(Locale.US, pct)
                                }

                                MapCacheDebug.log(
                                    "summary/15s tile(hit=$tileDeltaHits miss=$tileDeltaMisses rate=${formatHitRate(tileDeltaHits, tileDeltaMisses)}) " +
                                        "icon(hit=$iconDeltaHits miss=$iconDeltaMisses rate=${formatHitRate(iconDeltaHits, iconDeltaMisses)}) " +
                                        "dem(hit=$demDeltaHits miss=$demDeltaMisses rate=${formatHitRate(demDeltaHits, demDeltaMisses)})"
                                )
                            }
                            prevIconHits = iconStats.hits
                            prevIconMisses = iconStats.misses
                            prevTileHits = tileStats.hits
                            prevTileMisses = tileStats.misses
                            prevDemHits = demStats.hits
                            prevDemMisses = demStats.misses
                            cacheStatsQueryInFlight = false
                        }
                    }
                }
                val focusDesignator = focusedPath
                val followFocusPoint = focusDesignator?.let { focus ->
                    dronePoints.firstOrNull { it.designator == focus }
                }
                val shouldFollowFocusedDrone = shouldFollowFocusedDrone(
                    presentationMode = presentationMode,
                    followFocusedDroneEnabled = followFocusedDroneEnabled,
                    hasFocusedDroneTelemetry = followFocusPoint != null,
                    operatorAdjustedViewport = operatorAdjustedViewport
                )
                if (!shouldFollowFocusedDrone) {
                    lastInsetFollowDesignator = null
                    lastInsetFollowPoint = null
                }
                if (shouldFollowFocusedDrone && followFocusPoint != null) {
                    val nowMs = System.currentTimeMillis()
                    val focusChanged = lastInsetFollowDesignator != focusDesignator
                    if (focusChanged || nowMs - lastInsetFollowAtMs >= INSET_FOLLOW_INTERVAL_MS) {
                        val target = GeoPoint(followFocusPoint.lat, followFocusPoint.lng)
                        val previous = lastInsetFollowPoint.takeUnless { focusChanged }
                        val movedEnough = previous == null ||
                            previous.distanceToAsDouble(target) >= INSET_FOLLOW_MIN_MOVE_METERS
                        if (movedEnough) {
                            mapView.controller.setCenter(target)
                            if (restoredViewport == null && mapView.zoomLevelDouble < STARTUP_MY_LOCATION_MIN_ZOOM) {
                                mapView.controller.setZoom(STARTUP_MY_LOCATION_MIN_ZOOM)
                            }
                            if (!isInsetMode) {
                                persistFullMapViewport(mapView)
                            }
                            lastInsetFollowDesignator = focusDesignator
                            lastInsetFollowPoint = target
                        }
                        lastInsetFollowAtMs = nowMs
                    }
                }
                mapView.invalidate()
            }
        )

        mapBackgroundWorkStatus?.let { status ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .fillMaxWidth(0.58f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val countText = if (status.total > 0) {
                    " ${status.completed}/${status.total}"
                } else {
                    ""
                }
                Text(
                    text = status.label + countText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val progress = status.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (!isInsetMode) {
            MapPaneSettingsMenus(
                context = context,
                settingsMenuExpanded = settingsMenuExpanded,
                onSettingsMenuExpandedChange = { settingsMenuExpanded = it },
                mapManagementMenuExpanded = mapManagementMenuExpanded,
                onMapManagementMenuExpandedChange = { mapManagementMenuExpanded = it },
                baseLayerMenuExpanded = baseLayerMenuExpanded,
                onBaseLayerMenuExpandedChange = { baseLayerMenuExpanded = it },
                badTilesMenuExpanded = badTilesMenuExpanded,
                onBadTilesMenuExpandedChange = { badTilesMenuExpanded = it },
                baseLayer = baseLayer,
                predictiveHeadEnabled = predictiveHeadEnabled,
                followFocusedDroneEnabled = followFocusedDroneEnabled,
                mapReloadInFlight = mapReloadInFlight,
                mapCacheAvailableBytes = offlinePrepAvailableBytes,
                downloadMapStatus = offlinePrepMenuStatus(offlinePrepInFlight, offlinePrepProgress),
                mapName = mapName,
                autoRemoveBadTiles = autoRemoveBadTiles,
                contourOverlayEnabled = contourOverlayEnabled,
                hasMapFolders = buildMapFolderUiStates(artifactStoreById).isNotEmpty(),
                onTogglePredictiveHead = {
                    predictiveHeadEnabled = !predictiveHeadEnabled
                    CaltopoClient.SetPredictiveHeadEnabled(predictiveHeadEnabled)
                    settingsMenuExpanded = false
                },
                onDownloadMap = {
                    offlinePrepIncludeContours = contourOverlayEnabled
                    offlinePrepCacheLimitInput = String.format(
                        Locale.US,
                        "%.1f",
                        MapCachePolicy.tileCacheMaxBytes(context) / 1_000_000_000.0
                    )
                    settingsMenuExpanded = false
                    showOfflinePrepDialog = true
                },
                onOpenMapFolders = {
                    CTInfo(
                        MAP_PANE_TAG,
                        "Map Folders opened: " + mapFolderUiDebugSummary(
                            folders = buildMapFolderUiStates(artifactStoreById),
                            hiddenFolderIds = hiddenFolderIds,
                            hiddenItemIds = hiddenItemIds
                        ) + " | " + assignmentDiagnosticSummary(
                            features = artifactStoreById,
                            hiddenFolderIds = hiddenFolderIds,
                            hiddenItemIds = hiddenItemIds,
                            viewport = currentMapView?.boundingBox,
                        )
                    )
                    settingsMenuExpanded = false
                    showMapFoldersDialog = true
                },
                onToggleFollowFocusedDrone = {
                    val enabled = !followFocusedDroneEnabled
                    if (enabled) {
                        operatorAdjustedViewport = false
                    }
                    viewModel.setFollowFocusedDroneEnabled(enabled)
                },
                onReloadMap = {
                    mapManagementMenuExpanded = false
                    if (!mapReloadInFlight) {
                        mapReloadInFlight = true
                        CaltopoClient.ShowToast("Reloading map artifacts...")
                        CaltopoMap.ReloadMapArtifactsNow(
                            Runnable {
                                uiScope.launch(Dispatchers.Main.immediate) {
                                    startArtifactHydration("manual-reload") {
                                        mapReloadInFlight = false
                                        CaltopoClient.ShowToast("Map reloaded.")
                                    }
                                }
                            }
                        )
                        uiScope.launch {
                            delay(15_000L)
                            if (mapReloadInFlight) {
                                mapReloadInFlight = false
                            }
                        }
                    }
                },
                onOpenBadTiles = {
                    mapManagementMenuExpanded = false
                    badTilesMenuExpanded = true
                },
                onOpenBadTilesHowTo = {
                    badTilesMenuExpanded = false
                    showBadTilesHowToDialog = true
                },
                onOpenCacheSize = {
                    mapManagementMenuExpanded = false
                    mapCacheSizeInput = String.format(
                        Locale.US,
                        "%.1f",
                        MapCacheSettings.maxCacheBytes(context).toDouble() / 1_000_000_000.0
                    )
                    showMapCacheSizeDialog = true
                },
                onOpenTileAge = {
                    mapManagementMenuExpanded = false
                    mapTileAgeDaysInput = MapCacheSettings.maxTileAgeDays(context).toString()
                    showMapTileAgeDialog = true
                },
                onOpenMutualAidPackage = {
                    mapManagementMenuExpanded = false
                    if (!CaltopoClient.HasMutualAidTemplate()) {
                        CaltopoClient.ShowToast("Configure the Mutual Aid account in Settings before exporting an MA package.")
                    } else if (CaltopoMap.GetMapId().isBlank()) {
                        CaltopoClient.ShowToast("Connect to a CalTopo map before exporting an MA package.")
                    } else {
                        openMutualAidPackageDialog()
                    }
                },
                onToggleAutoRemoveBadTiles = {
                    autoRemoveBadTiles = !autoRemoveBadTiles
                    badTilesMenuExpanded = false
                },
                onClearBadTileFlags = {
                    BadTilePolicy.clearBlockedHashes(context)
                    CaltopoClient.ShowToast("Bad tile flags cleared.")
                    badTilesMenuExpanded = false
                },
                onExportBadTileHashes = {
                    val exportedTo = exportBadTileHashes(context)
                    if (exportedTo != null) {
                        CaltopoClient.ShowToast("Exported bad tile hashes to $exportedTo")
                    } else {
                        CaltopoClient.ShowToast("Bad tile hash export failed.")
                    }
                    badTilesMenuExpanded = false
                },
                onBaseLayerSelected = { option ->
                    viewModel.setBaseLayer(option)
                    baseLayerMenuExpanded = false
                },
                onToggleContours = {
                    contourOverlayEnabled = !contourOverlayEnabled
                    MapCacheSettings.setContourOverlayEnabled(context, contourOverlayEnabled)
                    currentMapView?.postInvalidate()
                    baseLayerMenuExpanded = false
                }
            )
        }

        MapPaneMutualAidDialogs(
            showPackageDialog = showMutualAidPackageDialog,
            onShowPackageDialogChange = { showMutualAidPackageDialog = it },
            sourceLabel = CaltopoClient.GetMutualAidSourceLabel(),
            displayName = maPackageDisplayName,
            onDisplayNameChange = { maPackageDisplayName = it },
            incident = maPackageIncident,
            onIncidentChange = { maPackageIncident = it },
            opPeriod = maPackageOpPeriod,
            onOpPeriodChange = { maPackageOpPeriod = it },
            mapId = maPackageMapId,
            onMapIdChange = { maPackageMapId = it },
            mapTitle = maPackageMapTitle,
            onMapTitleChange = { maPackageMapTitle = it },
            expiryDateText = maPackageExpiryDateText,
            onExpiryDateTextChange = { maPackageExpiryDateText = it },
            expiryTimeText = maPackageExpiryTimeText,
            onExpiryTimeTextChange = { maPackageExpiryTimeText = it },
            useMapPaneExtents = maPackageUseMapPaneExtents,
            onUseMapPaneExtentsChange = { maPackageUseMapPaneExtents = it },
            parsedExpiryEpochMs = parseMutualAidPackageExpiry(),
            preparingShare = preparingMutualAidShare,
            onStartShare = { startMutualAidShare() },
            activeShareSession = activeShareSession,
            onShareDone = { MutualAidPackageTransferManager.stopShareSession() }
        )

        if (showOfflinePrepDialog && !isInsetMode) {
            val selectedBoundary =
                if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                    offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
                } else {
                    null
                }
            val effectiveBoundary = selectedBoundary
            val bounds = effectiveBoundary?.bounds ?: mapBounds
            AlertDialog(
                onDismissRequest = {
                    if (!offlinePrepInFlight) showOfflinePrepDialog = false
                },
                title = { Text("Download Map") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (offlinePrepInFlight || offlinePrepProgress.phase != "Idle") {
                            val pct = if (offlinePrepProgress.totalBytes > 0L) {
                                offlinePrepProgress.fraction * 100.0
                            } else if (offlinePrepProgress.phase == "Complete" || offlinePrepProgress.phase == "Complete with failures") {
                                100.0
                            } else {
                                0.0
                            }
                            val progressFraction = (pct / 100.0).toFloat()
                            val etaText = offlinePrepProgress.etaSeconds?.let { formatDurationShort(it) } ?: "--:--"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "${String.format(Locale.US, "%.0f", pct)}% complete",
                                    fontSize = 18.sp
                                )
                                if (offlinePrepInFlight && offlinePrepProgress.totalBytes <= 0L) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Text(
                                    "Progress: ${offlinePrepProgress.phase} ${offlinePrepProgress.completed}/${offlinePrepProgress.total} " +
                                        "(${String.format(Locale.US, "%.2f", pct)}%) " +
                                        "rate=${formatStorageBytes(offlinePrepProgress.bytesPerSec.toLong())}/s " +
                                        "ETA=$etaText",
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Tiles: ${offlinePrepProgress.tileCompleted}/${offlinePrepProgress.tileTotal} " +
                                        "(hit=${offlinePrepProgress.hits} fetched=${offlinePrepProgress.fetched} failed=${offlinePrepProgress.failed})",
                                    fontSize = 12.sp
                                )
                                if (offlinePrepProgress.demTotal > 0) {
                                    Text(
                                        "DEM: ${offlinePrepProgress.demCompleted}/${offlinePrepProgress.demTotal} " +
                                            "(hit=${offlinePrepProgress.demHits} fetched=${offlinePrepProgress.demFetched} failed=${offlinePrepProgress.demFailed})",
                                        fontSize = 12.sp
                                    )
                                }
                                if (offlinePrepProgress.totalFailed > 0) {
                                    Text(
                                        "Total failures: ${offlinePrepProgress.totalFailed}",
                                        fontSize = 12.sp
                                    )
                                }
                                if (!offlinePrepInFlight && offlinePrepProgress.phase == "Complete") {
                                    Text(
                                        "Closing automatically...",
                                        fontSize = 11.sp
                                    )
                                }
                                if (!offlinePrepInFlight && offlinePrepProgress.phase == "Complete with failures") {
                                    Text(
                                        "Download finished with failures. Review the counts above before retrying.",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        val offlinePrepOptionsScrollState = rememberScrollState()
                        var offlinePrepOptionsViewportHeightPx by remember { mutableIntStateOf(0) }
                        Box(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .fillMaxWidth()
                                .clipToBounds()
                                .onSizeChanged { offlinePrepOptionsViewportHeightPx = it.height }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(offlinePrepOptionsScrollState)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                        Text("Area")
                        OfflinePrepAreaMode.entries.forEach { area ->
                            val enabled = area != OfflinePrepAreaMode.MapBoundary || offlineBoundaryOptions.isNotEmpty()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled && !offlinePrepInFlight) {
                                        offlinePrepAreaMode = area
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = offlinePrepAreaMode == area,
                                    onCheckedChange = {
                                        if (enabled && !offlinePrepInFlight && it == true) {
                                            offlinePrepAreaMode = area
                                        }
                                    },
                                    enabled = enabled && !offlinePrepInFlight
                                )
                                val label = if (area == OfflinePrepAreaMode.MapBoundary && offlineBoundaryOptions.isEmpty()) {
                                    "${area.label} (no polygons/lines in map)"
                                } else {
                                    area.label
                                }
                                Text(label)
                            }
                        }
                        if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary && offlineBoundaryOptions.isNotEmpty()) {
                            Text("Boundary shape", fontSize = 12.sp)
                            offlineBoundaryOptions.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !offlinePrepInFlight) {
                                            offlinePrepBoundaryId = option.id
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = offlinePrepBoundaryId == option.id,
                                        onCheckedChange = {
                                            if (!offlinePrepInFlight && it == true) {
                                                offlinePrepBoundaryId = option.id
                                            }
                                        },
                                        enabled = !offlinePrepInFlight
                                    )
                                    Text(option.label, fontSize = 12.sp)
                                }
                            }
                        }
                        OFFLINE_PREP_PRESETS.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !offlinePrepInFlight) { offlinePrepPreset = preset },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = offlinePrepPreset == preset,
                                    onCheckedChange = {
                                        if (!offlinePrepInFlight && it == true) offlinePrepPreset = preset
                                    },
                                    enabled = !offlinePrepInFlight
                                )
                                Column {
                                    Text(preset.label)
                                    Text(
                                        demSamplingSummary(preset.demStepMeters),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        Text(
                            "DEM detail is planned separately from map zoom. The default remains 30 m.",
                            fontSize = 11.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = offlinePrepIncludeDem,
                                onCheckedChange = { if (!offlinePrepInFlight) offlinePrepIncludeDem = it },
                                enabled = !offlinePrepInFlight
                            )
                            Text("Include DEM tiles")
                        }
                        if (offlinePrepIncludeDem) {
                            DemResolutionOption.values().forEach { resolution ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !offlinePrepInFlight) {
                                            offlinePrepDemResolution = resolution
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = offlinePrepDemResolution == resolution,
                                        onCheckedChange = {
                                            if (!offlinePrepInFlight && it == true) offlinePrepDemResolution = resolution
                                        },
                                        enabled = !offlinePrepInFlight
                                    )
                                    Column {
                                        Text(resolution.label, fontSize = 12.sp)
                                        Text(resolution.explanation, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = offlinePrepIncludeContours,
                                onCheckedChange = { if (!offlinePrepInFlight) offlinePrepIncludeContours = it },
                                enabled = !offlinePrepInFlight
                            )
                            Text("Include contour tiles")
                        }
                        if (maximizeThroughputBlockedForOsm) {
                            Text(
                                "OpenStreetMap offline prep uses a conservative single-request mode with the app's OSM user agent.",
                                fontSize = 11.sp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = offlinePrepMaxThroughput,
                                    onCheckedChange = { if (!offlinePrepInFlight) offlinePrepMaxThroughput = it },
                                    enabled = !offlinePrepInFlight
                                )
                                Text("Maximize throughput")
                            }
                        }
                        Text(
                            if (offlinePrepEstimateRunning || !offlinePrepEstimate.ready) {
                                "Estimate: calculating..."
                            } else {
                                "Estimate: " +
                                    "tiles=${offlinePrepEstimate.tileEstimate}" +
                                    (if (offlinePrepIncludeContours) " incl. contours" else "") +
                                    " (~${"%.1f".format(Locale.US, offlinePrepEstimate.estimatedTileCacheMb)} MB), " +
                                    "dem=${offlinePrepEstimate.demEstimate} tile(s) (~${"%.0f".format(Locale.US, offlinePrepEstimate.estimatedDemCacheMb)} MB)"
                            },
                            fontSize = 12.sp
                        )
                        Text(
                            "Note: GeoTIFF tiles provide instant local DEM lookups with no network queries for covered areas.",
                            fontSize = 11.sp
                        )
                        if (offlinePrepEstimate.ready) {
                            val estimatedTileBytes = (offlinePrepEstimate.estimatedTileCacheMb * 1024.0 * 1024.0).toLong()
                            val estimatedDemBytes = (offlinePrepEstimate.estimatedDemCacheMb * 1024.0 * 1024.0).toLong()
                            val capacity = OfflinePrepCapacity(
                                currentTileCacheBytes = offlinePrepCurrentTileCacheBytes,
                                estimatedTileBytes = estimatedTileBytes,
                                estimatedDemBytes = estimatedDemBytes,
                                maximumTileCacheBytes = offlinePrepTileCacheCapBytes,
                                availableVolumeBytes = offlinePrepAvailableBytes
                            )
                            Text(
                                "Tile cache: ${formatStorageBytes(capacity.currentTileCacheBytes)} currently; " +
                                    "up to ${formatStorageBytes(capacity.projectedTileCacheBytes)} after this download",
                                fontSize = 11.sp
                            )
                            Text(
                                "Configured limit: ${formatStorageBytes(capacity.maximumTileCacheBytes)}",
                                fontSize = 11.sp
                            )
                            if (capacity.exceedsCacheLimit) {
                                Text(
                                    "This download is expected to exceed the tile-cache limit. Increase the limit or reduce the selection before starting.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                val recommendedGb = capacity.recommendedMaximumBytes / 1_000_000_000.0
                                TextButton(
                                    onClick = {
                                        MapCacheSettings.setMaxCacheBytes(context, capacity.recommendedMaximumBytes)
                                        offlinePrepTileCacheCapBytes = MapCachePolicy.tileCacheMaxBytes(context)
                                        offlinePrepCacheLimitInput = String.format(Locale.US, "%.1f", recommendedGb)
                                    },
                                    enabled = !offlinePrepInFlight
                                ) {
                                    Text("Use recommended ${String.format(Locale.US, "%.1f", recommendedGb)} GB limit")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = offlinePrepCacheLimitInput,
                                    onValueChange = { offlinePrepCacheLimitInput = it },
                                    label = { Text("Max cache (GB)") },
                                    singleLine = true,
                                    enabled = !offlinePrepInFlight,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        val requestedGb = offlinePrepCacheLimitInput.toDoubleOrNull()
                                        if (requestedGb == null || requestedGb <= 0.0) {
                                            CaltopoClient.ShowToast("Enter a positive cache size in GB.")
                                        } else {
                                            val requestedBytes = (requestedGb * 1_000_000_000.0).toLong()
                                            val reasonableMaximum = reasonableCacheMaximumBytes(
                                                offlinePrepCurrentTileCacheBytes,
                                                offlinePrepAvailableBytes
                                            )
                                            if (reasonableMaximum != null && requestedBytes > reasonableMaximum) {
                                                CaltopoClient.ShowToast(
                                                    "Requested cache size exceeds the space reasonably available on this volume " +
                                                        "(${MapCacheSettings.formatDecimalGb(reasonableMaximum)} maximum recommended)."
                                                )
                                            } else {
                                                MapCacheSettings.setMaxCacheBytes(context, requestedBytes)
                                                offlinePrepTileCacheCapBytes = MapCachePolicy.tileCacheMaxBytes(context)
                                                offlinePrepCacheLimitInput = String.format(
                                                    Locale.US,
                                                    "%.1f",
                                                    offlinePrepTileCacheCapBytes / 1_000_000_000.0
                                                )
                                            }
                                        }
                                    },
                                    enabled = !offlinePrepInFlight
                                ) { Text("Apply") }
                            }
                            if (capacity.availableVolumeBytes != null) {
                                Text(
                                    "Available on cache volume: ${formatStorageBytes(capacity.availableVolumeBytes)}",
                                    fontSize = 11.sp
                                )
                                if (capacity.exceedsAvailableVolume) {
                                    Text(
                                        "Warning: estimated download may exceed available storage.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Text(
                            when {
                                !offlinePrepCacheStatus.checked -> "Offline package readiness: checking cached tiles..."
                                offlinePrepCacheStatus.readyForPackage -> "Offline package readiness: selected map data is fully cached."
                                offlinePrepReadyByCompletion -> buildString {
                                    append("Offline package readiness: enabled from latest Start run")
                                    if (offlinePrepCacheStatus.tileMissing > 0 || offlinePrepCacheStatus.demMissing > 0) {
                                        append(" (still missing ")
                                        if (offlinePrepCacheStatus.tileMissing > 0) {
                                            append("${offlinePrepCacheStatus.tileMissing} tile")
                                            if (offlinePrepCacheStatus.tileMissing != 1) append("s")
                                        }
                                        if (offlinePrepCacheStatus.tileMissing > 0 && offlinePrepCacheStatus.demMissing > 0) {
                                            append(", ")
                                        }
                                        if (offlinePrepCacheStatus.demMissing > 0) {
                                            append("${offlinePrepCacheStatus.demMissing} DEM")
                                            if (offlinePrepCacheStatus.demMissing != 1) append("s")
                                        }
                                        append(")")
                                    }
                                }
                                else -> buildString {
                                    append("Offline package readiness: run Start first")
                                    if (offlinePrepCacheStatus.tileMissing > 0 || offlinePrepCacheStatus.demMissing > 0) {
                                        append(" (missing ")
                                        if (offlinePrepCacheStatus.tileMissing > 0) {
                                            append("${offlinePrepCacheStatus.tileMissing} tile")
                                            if (offlinePrepCacheStatus.tileMissing != 1) append("s")
                                        }
                                        if (offlinePrepCacheStatus.tileMissing > 0 && offlinePrepCacheStatus.demMissing > 0) {
                                            append(", ")
                                        }
                                        if (offlinePrepCacheStatus.demMissing > 0) {
                                            append("${offlinePrepCacheStatus.demMissing} DEM")
                                            if (offlinePrepCacheStatus.demMissing != 1) append("s")
                                        }
                                        append(")")
                                    }
                                }
                            },
                            fontSize = 11.sp,
                            color = if (offlinePrepCacheStatus.checked && !offlinePackageReady) {
                                MaterialTheme.colorScheme.error
                            } else if (offlinePrepReadyByCompletion && !offlinePrepCacheStatus.readyForPackage) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                            }
                            val maxScroll = offlinePrepOptionsScrollState.maxValue
                            if (maxScroll > 0 && offlinePrepOptionsViewportHeightPx > 0) {
                                val density = LocalDensity.current
                                val viewportDp = with(density) { offlinePrepOptionsViewportHeightPx.toDp() }
                                val surfaceColor = MaterialTheme.colorScheme.surface
                                if (offlinePrepOptionsScrollState.value > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .fillMaxWidth()
                                            .height(18.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        surfaceColor,
                                                        Color.Transparent
                                                    )
                                                )
                                            )
                                    )
                                }
                                if (offlinePrepOptionsScrollState.value < maxScroll) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .height(22.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        surfaceColor
                                                    )
                                                )
                                            )
                                    )
                                }
                                val estimatedContentHeightPx = offlinePrepOptionsViewportHeightPx + maxScroll
                                val thumbHeightPx = (
                                    offlinePrepOptionsViewportHeightPx.toFloat() *
                                        offlinePrepOptionsViewportHeightPx.toFloat() /
                                        estimatedContentHeightPx.toFloat()
                                    ).toInt().coerceAtLeast(with(density) { 36.dp.roundToPx() })
                                    .coerceAtMost(offlinePrepOptionsViewportHeightPx)
                                val thumbOffsetPx = if (maxScroll <= 0) {
                                    0
                                } else {
                                    ((offlinePrepOptionsViewportHeightPx - thumbHeightPx).toFloat() *
                                        offlinePrepOptionsScrollState.value.toFloat() /
                                        maxScroll.toFloat()).toInt()
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(6.dp)
                                        .height(viewportDp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                            CircleShape
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(y = with(density) { thumbOffsetPx.toDp() })
                                        .width(6.dp)
                                        .height(with(density) { thumbHeightPx.toDp() })
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                                            CircleShape
                                        )
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val currentBounds = mapBounds
                                val boundary =
                                    if (offlinePrepAreaMode == OfflinePrepAreaMode.MapBoundary) {
                                        offlineBoundaryOptions.firstOrNull { it.id == offlinePrepBoundaryId }?.boundary
                                    } else {
                                        null
                                    }
                                val prepBounds = boundary?.bounds ?: currentBounds
                                if (prepBounds == null) {
                                    CaltopoClient.ShowToast("Offline prep needs visible map bounds.")
                                    return@TextButton
                                }
                                if (offlinePrepEstimate.ready) {
                                    val capacity = OfflinePrepCapacity(
                                        currentTileCacheBytes = offlinePrepCurrentTileCacheBytes,
                                        estimatedTileBytes = (offlinePrepEstimate.estimatedTileCacheMb * 1024.0 * 1024.0).toLong(),
                                        estimatedDemBytes = (offlinePrepEstimate.estimatedDemCacheMb * 1024.0 * 1024.0).toLong(),
                                        maximumTileCacheBytes = offlinePrepTileCacheCapBytes,
                                        availableVolumeBytes = offlinePrepAvailableBytes
                                    )
                                    if (capacity.exceedsCacheLimit) {
                                        CaltopoClient.ShowToast("Increase the tile-cache limit or choose a smaller download.")
                                        return@TextButton
                                    }
                                    if (capacity.exceedsAvailableVolume) {
                                        CaltopoClient.ShowToast("Estimated download exceeds available storage. Pick a smaller area/zoom.")
                                        return@TextButton
                                    }
                                }
                                startOfflinePrep(prepBounds, boundary)
                            },
                            enabled = !offlinePrepInFlight && offlinePrepEstimate.ready &&
                                (mapBounds != null || selectedBoundary != null)
                        ) { Text("Start") }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (offlinePrepInFlight) {
                                if (offlinePrepCancelRequested) return@TextButton
                                offlinePrepCoordinator.requestCancel()
                            } else {
                                offlinePrepAutoCloseJob?.cancel()
                                offlinePrepCancelRequested = false
                                showOfflinePrepDialog = false
                            }
                        },
                        enabled = !offlinePrepCancelRequested || !offlinePrepInFlight
                    ) {
                        Text(
                            when {
                                offlinePrepInFlight && offlinePrepCancelRequested -> "Cancelling..."
                                offlinePrepInFlight -> "Cancel"
                                else -> "Close"
                            }
                        )
                    }
                }
            )
        }

        if (isInsetMode) {
            Text(
                text = if (contourOverlayEnabled) "© OSM · USGS" else "© OSM",
                fontSize = 6.sp,
                lineHeight = 6.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 3.dp, bottom = 3.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.56f))
                    .padding(horizontal = 2.dp, vertical = 1.dp)
                    .clickable {
                        uriHandler.openUri("https://www.openstreetmap.org/copyright")
                    }
            )
        } else {
            Text(
                text = mapPaneAttributionText(contourOverlayEnabled),
                fontSize = 8.sp,
                lineHeight = 10.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .clickable {
                        uriHandler.openUri("https://www.openstreetmap.org/copyright")
                    }
            )
        }
    }
}

private fun BoundingBox.containsLocation(location: Location): Boolean {
    val minLat = minOf(latNorth, latSouth)
    val maxLat = maxOf(latNorth, latSouth)
    val minLon = minOf(lonWest, lonEast)
    val maxLon = maxOf(lonWest, lonEast)
    return location.latitude in minLat..maxLat && location.longitude in minLon..maxLon
}

private fun locationAgeMs(location: Location, nowMs: Long): Long {
    val locationTimeMs = location.time
    return if (locationTimeMs > 0L) {
        (nowMs - locationTimeMs).coerceAtLeast(0L)
    } else {
        Long.MAX_VALUE
    }
}

private fun nearestDistanceMeters(dronePoint: DroneMapPoint, artifactPoints: List<GeoPoint>): Double? {
    if (artifactPoints.isEmpty()) return null
    var best = Double.POSITIVE_INFINITY
    val result = FloatArray(1)
    for (artifactPoint in artifactPoints) {
        Location.distanceBetween(
            dronePoint.lat,
            dronePoint.lng,
            artifactPoint.latitude,
            artifactPoint.longitude,
            result
        )
        if (result[0].isFinite()) {
            best = minOf(best, result[0].toDouble())
        }
    }
    return if (best.isFinite()) best else null
}

private fun nearestLocalTrackTailDistanceMeters(
    dronePoint: DroneMapPoint,
    tracksByMappedId: Map<String, List<LocalTrackPoint>>
): Double? {
    var best: Double? = null
    tracksByMappedId.values.forEach { points ->
        val tail = points.lastOrNull() ?: return@forEach
        val result = FloatArray(1)
        Location.distanceBetween(
            dronePoint.lat,
            dronePoint.lng,
            tail.lat,
            tail.lng,
            result
        )
        if (result[0].isFinite()) {
            val value = result[0].toDouble()
            best = if (best == null) value else minOf(best!!, value)
        }
    }
    return best
}


private fun predictedHeadPoint(
    designator: String,
    nowWallMsec: Long,
    dronePointTimestampMsec: Long,
    tracksByMappedId: Map<String, List<LocalTrackPoint>>
): PredictedHead? {
    val points = tracksByMappedId[designator] ?: return null
    val p2 = points.lastOrNull() ?: return null
    val p1 = points.asReversed().drop(1).firstOrNull() ?: return null

    val deltaByDroneTsMsec = p2.timestampMsec - p1.timestampMsec
    val deltaByReceiveMsec = p2.receivedAtMsec - p1.receivedAtMsec
    val deltaMsec = when {
        deltaByDroneTsMsec > 0L -> deltaByDroneTsMsec
        deltaByReceiveMsec > 0L -> deltaByReceiveMsec
        else -> return null
    }

    val distanceAndBearing = FloatArray(2)
    Location.distanceBetween(
        p1.lat, p1.lng,
        p2.lat, p2.lng,
        distanceAndBearing
    )
    val segmentDistanceM = distanceAndBearing[0].toDouble()
    if (!segmentDistanceM.isFinite() || segmentDistanceM <= 0.0) return null

    val speedMps = (segmentDistanceM / deltaMsec.toDouble() * 1000.0)
        .coerceAtMost(PREDICTIVE_HEAD_MAX_SPEED_MPS)
    if (speedMps <= 0.0) return null

    val ageMsec = nowWallMsec - dronePointTimestampMsec
    if (ageMsec < PREDICTIVE_HEAD_MIN_AGE_MS || ageMsec > PREDICTIVE_HEAD_MAX_AGE_MS) return null

    val projectionMsec = ageMsec.coerceAtMost(PREDICTIVE_HEAD_MAX_LOOKAHEAD_MS)
    val projectionDistanceM = (speedMps * projectionMsec.toDouble() / 1000.0)
        .coerceAtMost(PREDICTIVE_HEAD_MAX_DISTANCE_M)
    if (projectionDistanceM <= 0.0) return null

    val predictedGeoPoint = destinationPoint(
        startLat = p2.lat,
        startLng = p2.lng,
        bearingDeg = distanceAndBearing[1].toDouble(),
        distanceM = projectionDistanceM
    )
    return PredictedHead(
        lat = predictedGeoPoint.latitude,
        lng = predictedGeoPoint.longitude
    )
}
