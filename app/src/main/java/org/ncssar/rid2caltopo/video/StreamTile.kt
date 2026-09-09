package org.ncssar.rid2caltopo.video

import DroneDisplayState
import StreamsViewModel
import CenterpointElevationSample
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.anomaly.AnomalyAlgorithm
import org.ncssar.rid2caltopo.video.anomaly.AnomalyConfig
import org.ncssar.rid2caltopo.video.anomaly.AnomalyDetectorMode
import org.ncssar.rid2caltopo.video.anomaly.AnomalyStrideMode
import org.ncssar.rid2caltopo.video.anomaly.MovementEstimatorMode
import org.ncssar.rid2caltopo.video.anomaly.PersonRelevanceMode
import org.ncssar.rid2caltopo.video.anomaly.PERSON_RELEVANCE_SUPPORTING_TEXT
import org.ncssar.rid2caltopo.video.anomaly.TargetColorFamily
import org.ncssar.rid2caltopo.video.anomaly.targetColorFamilySummary
import org.ncssar.rid2caltopo.ui.StreamPlayerView
import kotlin.math.roundToInt

internal fun shouldShowStreamClueCaptureButton(
    showTileControls: Boolean,
    isLocalPlayback: Boolean,
    streamState: StreamState,
): Boolean = showTileControls && !isLocalPlayback && streamState == StreamState.LIVE

internal fun streamTelemetryHeaderText(
    displayState: DroneDisplayState?,
    cameraAzimuthDeg: Double? = null,
): String =
    droneStatusLabelText(
        atoFeet = displayState?.atoFt,
        aglFeet = displayState?.aglFt,
        aglStale = displayState?.aglStale == true,
        rangeFeet = displayState?.rangeFt,
        headingDeg = cameraAzimuthDeg ?: displayState?.headingDeg,
        headingLabel = if (cameraAzimuthDeg != null) "CAM" else "TRK",
    )

internal fun isNearStreamCenter(
    tap: Offset,
    widthPx: Float,
    heightPx: Float,
    radiusPx: Float,
): Boolean {
    if (widthPx <= 0f || heightPx <= 0f || radiusPx <= 0f) return false
    val dx = tap.x - widthPx / 2f
    val dy = tap.y - heightPx / 2f
    return dx * dx + dy * dy <= radiusPx * radiusPx
}

internal fun shouldToggleCenterpointElevation(
    explicitlyFocused: Boolean,
    tapNearCenter: Boolean,
): Boolean = explicitlyFocused && tapNearCenter

internal fun shouldSetCenterpointElevationReference(
    explicitlyFocused: Boolean,
    elevationEnabled: Boolean,
    pressNearCenter: Boolean,
): Boolean = explicitlyFocused && elevationEnabled && pressNearCenter

internal enum class CenterpointElevationDisplayMode {
    MSL,
    REFERENCE,
}

private val CenterpointTurquoise = Color(0xFF40E0D0)

internal fun centerpointElevationLabel(
    sample: CenterpointElevationSample?,
    referenceElevationFeet: Int? = null,
    displayMode: CenterpointElevationDisplayMode = CenterpointElevationDisplayMode.MSL,
): String {
    val suffix = sample?.demResolutionMeters?.let { "${it}m DEM" }
        ?: "USGS DEM"
    if (sample == null) return "--' MSL"
    if (displayMode == CenterpointElevationDisplayMode.REFERENCE && referenceElevationFeet != null) {
        val delta = sample.elevationFeet - referenceElevationFeet
        val signedDelta = if (delta >= 0) "+$delta" else delta.toString()
        return "$signedDelta' REF · $suffix"
    }
    return "${sample.elevationFeet}' MSL · $suffix"
}

@Composable
private fun CenterpointElevationOverlay(
    sample: CenterpointElevationSample?,
    referenceElevationFeet: Int?,
    displayMode: CenterpointElevationDisplayMode,
    onToggleDisplayMode: () -> Unit,
    onSetReference: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = sample?.let {
                    val resolution = it.demResolutionMeters?.let { meters -> ", $meters meter DEM" }
                        ?: ", USGS DEM"
                    "Centerpoint elevation ${it.elevationFeet} feet$resolution"
                }
                    ?: "Centerpoint elevation unavailable"
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val arm = 18.dp.toPx()
            val gap = 4.dp.toPx()
            val segments = listOf(
                center.copy(x = center.x - arm) to center.copy(x = center.x - gap),
                center.copy(x = center.x + gap) to center.copy(x = center.x + arm),
                center.copy(y = center.y - arm) to center.copy(y = center.y - gap),
                center.copy(y = center.y + gap) to center.copy(y = center.y + arm),
            )
            segments.forEach { (start, end) ->
                drawLine(Color.Black, start, end, strokeWidth = 4.dp.toPx())
                drawLine(CenterpointTurquoise, start, end, strokeWidth = 1.5.dp.toPx())
            }
            drawCircle(Color.Black, radius = 3.5.dp.toPx(), center = center, style = Stroke(2.5.dp.toPx()))
            drawCircle(CenterpointTurquoise, radius = 3.5.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
        }
        val label = centerpointElevationLabel(sample, referenceElevationFeet, displayMode)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 42.dp, y = 28.dp)
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(referenceElevationFeet, displayMode) {
                    detectTapGestures(
                        onTap = {
                            if (referenceElevationFeet != null) onToggleDisplayMode()
                        },
                        onLongPress = { onSetReference() },
                    )
                }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = label,
                color = Color.Black,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(drawStyle = Stroke(width = 5f)),
            )
            Text(
                text = label,
                color = CenterpointTurquoise,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}


@Composable
fun StreamTile(
    streamDesignator: String,
    streamRevision: Long,
    viewModel: StreamsViewModel,
    streamState: StreamState,
    streamErrorDetail: String?,
    onCloseStream: () -> Unit,
    onRestartServer: () -> Unit,
    onToggleFocus: () -> Unit,
    showTileControls: Boolean = true,
    effectiveFocused: Boolean? = null,
    showFocusBorder: Boolean? = null,
    fillContainer: Boolean = false,
    showStandaloneTelemetryOverlay: Boolean = true,
    remoteRequesterEmail: String? = null,
) {
    val tag="StreamTile"
    val clueCaptureSlowMs = 250L
    val clueCaptureStateKey = streamClueCaptureStateKey(streamDesignator, streamRevision)
    val clueCaptureTargetRef = remember(clueCaptureStateKey) {
        mutableStateOf<StreamClueCaptureTarget?>(null)
    }
    var renderedFrameCount by remember(clueCaptureStateKey) { mutableIntStateOf(0) }
    var pendingClueCaptureRequestId by remember(clueCaptureStateKey) { mutableLongStateOf(0L) }
    var handledClueCaptureRequestId by remember(clueCaptureStateKey) { mutableLongStateOf(0L) }
    var showPicker by remember { mutableStateOf(false) }
    var showUnmatchDialog by remember { mutableStateOf(false) }
    var pendingPairingWarning by remember { mutableStateOf<StreamTelemetryPairingWarning?>(null) }
    var anomalyMenuExpanded by remember { mutableStateOf(false) }
    var showAnomalySettingsDialog by remember { mutableStateOf(false) }
    var showAdHelpDialog by remember { mutableStateOf(false) }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()

    // Keep the altitude coordinator active while this tile is on screen.
    DisposableEffect(viewModel) {
        val removeConsumer = viewModel.addAltitudeConsumer()
        onDispose { removeConsumer() }
    }
    val explicitlyFocused = focusedPath == streamDesignator
    val isFocused = effectiveFocused ?: explicitlyFocused
    val drawFocusBorder = showFocusBorder ?: explicitlyFocused
    val isLocalPlayback = viewModel.isLocalPlayback(streamDesignator)
    val isLocalPlaybackPaused = if (isLocalPlayback) viewModel.isLocalPlaybackPaused(streamDesignator) else false
    val tileInteractionsEnabled = showTileControls
    val pauseLocalPlaybackOnOpen = if (isLocalPlayback) viewModel.pauseLocalPlaybackOnOpenEnabled() else false
    val designatorState = viewModel.designatorStateFor(streamDesignator)
    val automaticPairingRequest = viewModel.automaticStreamPairingRequest
    val anomalyConfig = viewModel.anomalyConfigFor(streamDesignator)
    val anomalyMode = anomalyConfig.detectorMode()
    val anomalyPauseReason = viewModel.anomalyPauseReasonFor(streamDesignator)
    val currentIsFocused by rememberUpdatedState(isFocused)
    val currentDesignatorState by rememberUpdatedState(designatorState)
    var pendingAnnotationPoint by remember(streamDesignator) { mutableStateOf<Offset?>(null) }
    var centerpointElevationEnabled by remember(streamDesignator) { mutableStateOf(false) }
    var centerpointElevation by remember(streamDesignator) { mutableStateOf<CenterpointElevationSample?>(null) }
    var centerpointReferenceElevationFeet by remember(streamDesignator) { mutableStateOf<Int?>(null) }
    var centerpointDisplayMode by remember(streamDesignator) {
        mutableStateOf(CenterpointElevationDisplayMode.MSL)
    }
    val density = LocalDensity.current
    val localRuntimeSnapshot by produceState<org.ncssar.rid2caltopo.video.ffmpeg.StreamRuntimeSnapshot?>(
        initialValue = null,
        streamDesignator,
        isLocalPlayback,
        isLocalPlaybackPaused,
        streamRevision,
    ) {
        if (!isLocalPlayback) {
            value = null
            return@produceState
        }
        while (true) {
            value = withContext(Dispatchers.Default) {
                viewModel.runtimeSnapshotFor(streamDesignator)
            }
            delay(if (isLocalPlaybackPaused) 120L else 400L)
        }
    }
    val currentFrameTimestampUs = localRuntimeSnapshot?.currentSourceTimestampUs
    val currentFrameAnnotations = viewModel.localPlaybackFrameAnnotations(streamDesignator, currentFrameTimestampUs)
    val currentFrameAnnotationSummary = viewModel.localPlaybackFrameAnnotationSummary(streamDesignator, currentFrameTimestampUs)
    val currentFrameCounterText = if (isLocalPlayback) {
        currentFrameTimestampUs?.let { "T ${formatPlaybackTimestampUs(it)}" } ?: "T --:--.--"
    } else {
        null
    }
    val showLocalPlaybackLegendControls = isLocalPlayback
    val showAnomalyReviewLegendControls = isLocalPlayback && anomalyConfig.enabled
    val showAnomalyLegendControls = true
    val showLegendControls = showLocalPlaybackLegendControls || showAnomalyLegendControls
    val showLocalPlaybackShell = isLocalPlayback && streamState != StreamState.ERROR
    val showOverlayControls = showTileControls &&
        (isFocused || isLocalPlayback) &&
        (streamState == StreamState.LIVE || showLocalPlaybackShell)
    var streamTileSize by remember(streamDesignator) { mutableStateOf(IntSize.Zero) }
    var zoomScale by remember(streamDesignator, streamRevision) { mutableStateOf(1f) }
    var zoomOffset by remember(streamDesignator, streamRevision) { mutableStateOf(Offset.Zero) }
    val maxZoomScale = 4f
    fun clampZoomOffset(offset: Offset, scale: Float): Offset {
        if (scale <= 1.001f || streamTileSize.width <= 0 || streamTileSize.height <= 0) {
            return Offset.Zero
        }
        val maxX = streamTileSize.width * (scale - 1f) * 0.5f
        val maxY = streamTileSize.height * (scale - 1f) * 0.5f
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }
    val zoomTransformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (zoomScale * zoomChange).coerceIn(1f, maxZoomScale)
        zoomScale = nextScale
        zoomOffset = clampZoomOffset(
            offset = if (nextScale <= 1.001f) Offset.Zero else zoomOffset + panChange,
            scale = nextScale,
        )
    }
    LaunchedEffect(anomalyConfig.enabled) {
        if (!anomalyConfig.enabled) {
            pendingAnnotationPoint = null
        }
    }
    val streamFrameModifier = if (fillContainer) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    }

    LaunchedEffect(streamTileSize, zoomScale) {
        zoomOffset = clampZoomOffset(zoomOffset, zoomScale)
    }

    LaunchedEffect(isFocused) {
        if (!isFocused) {
            centerpointElevationEnabled = false
            centerpointElevation = null
        }
    }

    LaunchedEffect(centerpointElevationEnabled, isFocused, streamState) {
        if (!centerpointElevationEnabled || !isFocused || streamState != StreamState.LIVE) return@LaunchedEffect
        while (true) {
            viewModel.centerpointElevationForStream(streamDesignator)?.let { updated ->
                if (centerpointElevation != updated) centerpointElevation = updated
            }
            delay(500L)
        }
    }

    fun openTelemetryPairingControl() {
        if (!tileInteractionsEnabled || isLocalPlayback) return
        when (designatorState) {
            is DesignatorState.Yellow -> {
                val decision = viewModel.streamPairingControlDecision(streamDesignator)
                if (decision.kind == StreamTelemetryPairingControlAction.ShowWarning && decision.warning != null) {
                    pendingPairingWarning = decision.warning
                } else {
                    showPicker = true
                }
            }
            is DesignatorState.Green -> showUnmatchDialog = true
            else -> {}
        }
    }

    LaunchedEffect(
        automaticPairingRequest?.generation,
        designatorState,
        streamState,
        tileInteractionsEnabled,
    ) {
        val request = automaticPairingRequest ?: return@LaunchedEffect
        if (
            tileInteractionsEnabled &&
            streamState == StreamState.LIVE &&
            designatorState is DesignatorState.Yellow &&
            request.streamDesignator == streamDesignator
        ) {
            CTDebug(
                tag,
                "Presenting automatic telemetry pairing prompt stream=$streamDesignator " +
                    "remoteId=${request.remoteId}",
            )
            openTelemetryPairingControl()
            viewModel.consumeAutomaticStreamPairingRequest(request)
        }
    }

    fun setCenterpointElevationReference() {
        val sample = centerpointElevation
        if (sample == null) {
            CaltopoClient.ShowToast("Reference unavailable: waiting for centerpoint elevation.")
        } else {
            centerpointReferenceElevationFeet = sample.elevationFeet
            centerpointDisplayMode = CenterpointElevationDisplayMode.REFERENCE
            CaltopoClient.ShowToast("Elevation reference set at ${sample.elevationFeet} ft MSL.")
        }
    }

    fun registerClueCaptureTarget(target: StreamClueCaptureTarget) {
        val previous = clueCaptureTargetRef.value
        if (previous?.textureView !== target.textureView ||
            previous.requiresRenderedFrame != target.requiresRenderedFrame
        ) {
            renderedFrameCount = 0
        }
        clueCaptureTargetRef.value = target
    }

    fun captureClueSnapshot(reason: String): Boolean {
        val clueTapStartedAtMs = System.currentTimeMillis()
        fun logClueTapIfSlow(step: String, elapsedMs: Long, bitmap: Bitmap? = null) {
            if (elapsedMs < clueCaptureSlowMs) return
            val bitmapSummary = bitmap?.let { " bitmap=${it.width}x${it.height}" } ?: ""
            CaltopoClient.CTWarn(
                tag,
                "clue double-tap slow step=$step elapsedMs=$elapsedMs " +
                    "designator=$streamDesignator reason=$reason zoom=$zoomScale offset=${zoomOffset.x},${zoomOffset.y}$bitmapSummary"
            )
        }

        val captureTarget = clueCaptureTargetRef.value
        if (!streamClueCaptureReady(
                hasCaptureTarget = captureTarget != null,
                renderedFrameCount = renderedFrameCount,
                requiresRenderedFrame = captureTarget?.requiresRenderedFrame ?: true
            ) || captureTarget == null
        ) {
            CTDebug(
                tag,
                "Capture target not ready yet for clue capture reason=$reason " +
                    "target=${captureTarget?.label ?: "none"} " +
                    "requiresRenderedFrame=${captureTarget?.requiresRenderedFrame ?: true} " +
                    "renderedFrames=$renderedFrameCount"
            )
            return false
        }

        val captureStartedAtMs = System.currentTimeMillis()
        val bitmap = captureTarget.textureView.bitmap
        logClueTapIfSlow(
            "${captureTarget.label}.bitmap",
            System.currentTimeMillis() - captureStartedAtMs,
            bitmap
        )
        if (bitmap == null) {
            CTDebug(
                tag,
                "Failed to capture bitmap from ${captureTarget.label} " +
                    "reason=$reason renderedFrames=$renderedFrameCount"
            )
            return false
        }

        val zoomStartedAtMs = System.currentTimeMillis()
        val clueBitmap = zoomedSnapshotBitmap(
            source = bitmap,
            scale = zoomScale,
            offset = zoomOffset,
        )
        logClueTapIfSlow(
            "zoomedSnapshotBitmap",
            System.currentTimeMillis() - zoomStartedAtMs,
            clueBitmap
        )
        val viewModelStartedAtMs = System.currentTimeMillis()
        viewModel.onSnapshotCaptured(streamDesignator, clueBitmap)
        logClueTapIfSlow(
            "StreamsViewModel.onSnapshotCaptured",
            System.currentTimeMillis() - viewModelStartedAtMs,
            clueBitmap
        )
        logClueTapIfSlow(
            "total",
            System.currentTimeMillis() - clueTapStartedAtMs,
            clueBitmap
        )
        return true
    }

    fun requestClueCapture(reason: String) {
        if (!tileInteractionsEnabled || isLocalPlayback) return
        CTDebug(
            tag,
            "Clue capture requested designator=$streamDesignator reason=$reason " +
                "focused=$currentIsFocused state=$streamState renderedFrames=$renderedFrameCount"
        )
        if (!currentIsFocused) {
            viewModel.ensureFocus(streamDesignator)
        }
        if (!viewModel.hasPairedTelemetry(streamDesignator)) {
            CTDebug(tag, "Clue capture unavailable for $streamDesignator: no active paired telemetry.")
            CaltopoClient.ShowToast("Clue unavailable: no current paired drone location.")
            return
        }
        if (captureClueSnapshot(reason)) {
            pendingClueCaptureRequestId = 0L
            handledClueCaptureRequestId = 0L
        } else {
            pendingClueCaptureRequestId += 1L
            CaltopoClient.ShowToast("Preparing video frame for clue...")
        }
    }

    LaunchedEffect(pendingClueCaptureRequestId, renderedFrameCount, clueCaptureTargetRef.value) {
        val requestId = pendingClueCaptureRequestId
        if (requestId == 0L || requestId == handledClueCaptureRequestId) return@LaunchedEffect
        if (captureClueSnapshot("deferred")) {
            handledClueCaptureRequestId = requestId
        }
    }

    Box(
        modifier = Modifier
            .border(
                width = if (drawFocusBorder) 3.dp else 0.dp,
                color = if (drawFocusBorder) Color.Yellow else Color.Transparent
            )
            .then(if (fillContainer) Modifier.fillMaxSize() else Modifier.aspectRatio(16f / 9f))
            .onSizeChanged { streamTileSize = it }
            .clipToBounds()
            .transformable(zoomTransformState)
        ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = zoomOffset.x
                    translationY = zoomOffset.y
                }
        ) {
            StreamPlayer(
                state = streamState,
                designator = streamDesignator,
                streamRevision = streamRevision,
                modifier = streamFrameModifier,
                fillFrame = fillContainer,
                viewModel = viewModel,
                onTextureViewReady = { tv ->
                    registerClueCaptureTarget(
                        StreamClueCaptureTarget(
                            textureView = tv,
                            requiresRenderedFrame = true,
                            label = "ffmpeg-texture"
                        )
                    )
                },
                onPlayerTextureViewReady = { tv ->
                    registerClueCaptureTarget(
                        StreamClueCaptureTarget(
                            textureView = tv,
                            requiresRenderedFrame = false,
                            label = "player-texture"
                        )
                    )
                },
                onTextureFrameUpdated = { renderedFrameCount = it }
            )
            if (isLocalPlayback) {
                if (currentFrameAnnotations.isNotEmpty()) {
                    Canvas(
                        modifier = streamFrameModifier
                    ) {
                        currentFrameAnnotations.forEach { annotation ->
                            val markerColor = when (annotation.verdict) {
                                LocalPlaybackAnnotationVerdict.Good -> Color.Green
                                LocalPlaybackAnnotationVerdict.Bad -> Color.Red
                                LocalPlaybackAnnotationVerdict.Unsure -> Color.Yellow
                            }
                            val center = Offset(
                                x = annotation.xNorm.coerceIn(0f, 1f) * size.width,
                                y = annotation.yNorm.coerceIn(0f, 1f) * size.height,
                            )
                            val radius = 10.dp.toPx()
                            val strokeWidth = 2.dp.toPx()
                            drawCircle(
                                color = markerColor,
                                radius = radius,
                                center = center,
                                style = Stroke(width = strokeWidth)
                            )
                            drawLine(
                                color = markerColor,
                                start = Offset(center.x - radius * 0.75f, center.y),
                                end = Offset(center.x + radius * 0.75f, center.y),
                                strokeWidth = strokeWidth
                            )
                            drawLine(
                                color = markerColor,
                                start = Offset(center.x, center.y - radius * 0.75f),
                                end = Offset(center.x, center.y + radius * 0.75f),
                                strokeWidth = strokeWidth
                            )
                        }
                    }
                }
            }
        }
        if (isLocalPlayback && anomalyConfig.enabled && isLocalPlaybackPaused && currentFrameTimestampUs != null) {
            Box(
                modifier = streamFrameModifier
                    .pointerInput(
                        streamDesignator,
                        currentFrameTimestampUs,
                        isLocalPlaybackPaused,
                        zoomScale,
                        zoomOffset,
                    ) {
                        detectTapGestures(
                            onTap = { tapOffset ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val height = size.height.toFloat().coerceAtLeast(1f)
                                val visualPoint = unzoomedStreamPoint(
                                    tapOffset = tapOffset,
                                    containerWidth = width,
                                    containerHeight = height,
                                    scale = zoomScale,
                                    offset = zoomOffset,
                                )
                                pendingAnnotationPoint = Offset(
                                    x = (visualPoint.x / width).coerceIn(0f, 1f),
                                    y = (visualPoint.y / height).coerceIn(0f, 1f),
                                )
                            },
                            onLongPress = {
                                showAnomalySettingsDialog = true
                            },
                            onDoubleTap = {
                                zoomScale = 1f
                                zoomOffset = Offset.Zero
                            }
                        )
                    }
            )
        }
        // Keep aircraft heading distinct from the camera bearing supplied by DJI SEI.
        if (streamState == StreamState.LIVE && !isLocalPlayback && showStandaloneTelemetryOverlay) {
            val displayState = viewModel.droneDisplayStateForStream(streamDesignator)
            val cameraAzimuthDeg = viewModel.cameraAzimuthForStream(streamDesignator)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = 6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = streamTelemetryHeaderText(displayState, cameraAzimuthDeg),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (zoomScale > 1.01f) {
                    Text(
                        text = "${zoomScale.formatZoom()}x",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (
            anomalyConfig.enabled &&
            anomalyConfig.showGuideBoxes &&
            streamTileSize.width > 0 &&
            streamTileSize.height > 0
        ) {
            Canvas(
                modifier = Modifier
                    .then(streamFrameModifier)
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = zoomOffset.x
                        translationY = zoomOffset.y
                    }
            ) {
                val guideColor = Color(0xFF80CBC4).copy(alpha = 0.70f)
                val (scanZoneWidth, scanZoneHeight) = anomalyConfig.scanZoneSize(
                    frameWidth = size.width,
                    frameHeight = size.height,
                )
                val scanZoneTopLeft = Offset(
                    x = (size.width - scanZoneWidth) * 0.5f,
                    y = (size.height - scanZoneHeight) * 0.5f,
                )
                drawRect(
                    color = guideColor,
                    topLeft = scanZoneTopLeft,
                    size = Size(scanZoneWidth, scanZoneHeight),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                val targetSpanPx = anomalyConfig.effectiveSmallTargetSpanPx(
                    frameWidth = streamTileSize.width,
                    frameHeight = streamTileSize.height,
                ).coerceAtMost(size.minDimension * 0.35f)
                val topLeft = Offset(
                    x = (size.width - targetSpanPx) * 0.5f,
                    y = (size.height - targetSpanPx) * 0.5f,
                )
                drawRect(
                    color = guideColor,
                    topLeft = topLeft,
                    size = Size(targetSpanPx, targetSpanPx),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (!tileInteractionsEnabled || (isLocalPlayback && isLocalPlaybackPaused)) {
                        Modifier
                    } else {
                        Modifier.pointerInput(streamDesignator, designatorState, currentIsFocused, currentDesignatorState) {
                            detectTapGestures(
                                onTap = { tapOffset ->
                                    CTDebug(tag, "StreamTile{${streamDesignator}) onTap")
                                    if (isLocalPlayback) {
                                        viewModel.ensureFocus(streamDesignator)
                                    } else if (currentIsFocused) {
                                        val minimumDimension = minOf(size.width, size.height).toFloat()
                                        val radiusPx = with(density) {
                                            (minimumDimension * 0.20f)
                                                .coerceIn(48.dp.toPx(), 96.dp.toPx())
                                        }
                                        if (shouldToggleCenterpointElevation(
                                                explicitlyFocused = explicitlyFocused,
                                                tapNearCenter = isNearStreamCenter(
                                                    tap = tapOffset,
                                                    widthPx = size.width.toFloat(),
                                                    heightPx = size.height.toFloat(),
                                                    radiusPx = radiusPx,
                                                ),
                                            )
                                        ) {
                                            centerpointElevationEnabled = !centerpointElevationEnabled
                                            if (centerpointElevationEnabled) {
                                                centerpointElevation = null
                                                zoomScale = 1f
                                                zoomOffset = Offset.Zero
                                            }
                                            return@detectTapGestures
                                        }
                                        onToggleFocus()
                                    } else {
                                        onToggleFocus()
                                    }
                                },
                                onLongPress = { pressOffset ->
                                    if (isLocalPlayback) {
                                        showAnomalySettingsDialog = true
                                        return@detectTapGestures
                                    }
                                    val minimumDimension = minOf(size.width, size.height).toFloat()
                                    val radiusPx = with(density) {
                                        (minimumDimension * 0.20f)
                                            .coerceIn(48.dp.toPx(), 96.dp.toPx())
                                    }
                                    if (shouldSetCenterpointElevationReference(
                                            explicitlyFocused = explicitlyFocused,
                                            elevationEnabled = centerpointElevationEnabled,
                                            pressNearCenter = isNearStreamCenter(
                                                tap = pressOffset,
                                                widthPx = size.width.toFloat(),
                                                heightPx = size.height.toFloat(),
                                                radiusPx = radiusPx,
                                            ),
                                        )
                                    ) {
                                        setCenterpointElevationReference()
                                        return@detectTapGestures
                                    }
                                    CTDebug(tag, "StreamTile(${streamDesignator}) onLongPress designatorState=${designatorState::class.simpleName}")
                                    openTelemetryPairingControl()
                                },
                                onDoubleTap = {
                                    requestClueCapture("double-tap")
                                }
                            )
                        }
                    }
                )
        )
        if (centerpointElevationEnabled && isFocused && streamState == StreamState.LIVE) {
            CenterpointElevationOverlay(
                sample = centerpointElevation,
                referenceElevationFeet = centerpointReferenceElevationFeet,
                displayMode = centerpointDisplayMode,
                onToggleDisplayMode = {
                    if (centerpointReferenceElevationFeet != null) {
                        centerpointDisplayMode = when (centerpointDisplayMode) {
                            CenterpointElevationDisplayMode.MSL -> CenterpointElevationDisplayMode.REFERENCE
                            CenterpointElevationDisplayMode.REFERENCE -> CenterpointElevationDisplayMode.MSL
                        }
                    }
                },
                onSetReference = ::setCenterpointElevationReference,
            )
        }
        if (shouldShowStreamClueCaptureButton(showTileControls, isLocalPlayback, streamState)) {
            IconButton(
                onClick = { requestClueCapture("camera-button") },
                modifier = Modifier
                    .zIndex(2f)
                    .align(Alignment.CenterEnd)
                    .offset(x = (-62).dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = "Capture clue snapshot",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (showOverlayControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                IconButton(
                    onClick = { anomalyMenuExpanded = true },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Anomaly detection settings"
                    )
                }
                DropdownMenu(
                    expanded = anomalyMenuExpanded,
                    onDismissRequest = { anomalyMenuExpanded = false }
                ) {
                    AnomalySettingsMenuContent(
                        viewModel = viewModel,
                        streamDesignator = streamDesignator,
                        anomalyMode = anomalyMode,
                        isLocalPlayback = isLocalPlayback,
                        pauseLocalPlaybackOnOpen = pauseLocalPlaybackOnOpen,
                        onShowSettings = { showAnomalySettingsDialog = true },
                        onShowHelp = { showAdHelpDialog = true },
                        onCloseStream = onCloseStream,
                        onRestartServer = if (!isLocalPlayback) onRestartServer else null,
                        onDismissMenu = { anomalyMenuExpanded = false },
                    )
                }
            }
            if (showLegendControls) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showLocalPlaybackLegendControls) {
                        currentFrameCounterText?.let { frameText ->
                            Text(
                                text = frameText,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(76.dp),
                            )
                        }
                        Text(
                            text = "Back",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .width(34.dp)
                                .pointerInput(streamDesignator) {
                                    detectTapGestures(onTap = { viewModel.stepLocalPlaybackBack(streamDesignator) })
                                }
                        )
                        Text(
                            text = if (isLocalPlaybackPaused) "Run" else "Pause",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .width(42.dp)
                                .pointerInput(streamDesignator, isLocalPlaybackPaused) {
                                    detectTapGestures(onTap = { viewModel.toggleLocalPlaybackPaused(streamDesignator) })
                                }
                        )
                        Text(
                            text = "Step",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .width(32.dp)
                                .pointerInput(streamDesignator) {
                                    detectTapGestures(onTap = { viewModel.stepLocalPlaybackFrame(streamDesignator) })
                                }
                        )
                        if (showAnomalyReviewLegendControls) {
                            Text(
                                text = currentFrameAnnotationSummary ?: "0 notes",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.width(52.dp),
                            )
                        }
                    }
                    if (showAnomalyLegendControls) {
                        OutlinedLegendText(
                            text = "AD: ${anomalyMode.label}",
                            fillColor = when (anomalyMode) {
                                AnomalyDetectorMode.Off -> Color.DarkGray
                                AnomalyDetectorMode.ColorUniqueness -> Color(0xFF1565C0)
                                AnomalyDetectorMode.TargetColors -> Color(0xFF2E7D32)
                                AnomalyDetectorMode.Infrared -> Color(0xFFC62828)
                            },
                            fontSize = 11.sp,
                            modifier = Modifier.pointerInput(streamDesignator, anomalyMode) {
                                detectTapGestures(onTap = { showAnomalySettingsDialog = true })
                            },
                        )
                        if (anomalyConfig.enabled && anomalyPauseReason != null) {
                            Text(
                                text = "AD paused: $anomalyPauseReason",
                                color = Color(0xFFFFD54F),
                                fontSize = 11.sp,
                            )
                        }
                    }
                    remoteRequesterEmail?.takeIf { it.isNotBlank() }?.let { requesterEmail ->
                        Text(
                            text = "Requested by $requesterEmail",
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            AnomalySettingsDialogs(
                viewModel = viewModel,
                streamDesignator = streamDesignator,
                anomalyConfig = anomalyConfig,
                isLocalPlayback = isLocalPlayback,
                showAnomalySettingsDialog = showAnomalySettingsDialog,
                onDismissAnomalySettingsDialog = { showAnomalySettingsDialog = false },
                showAdHelpDialog = showAdHelpDialog,
                onDismissAdHelpDialog = { showAdHelpDialog = false },
            )
        }
        DesignatorIndicator(
            streamDesignator = streamDesignator,
            streamState = streamState,
            streamErrorDetail = streamErrorDetail,
            viewModel = viewModel,
            onLongPress = {
                openTelemetryPairingControl()
            },
            onTelemetryChipClick = {
                CTDebug(tag, "StreamTile(${streamDesignator}) telemetryChipClick designatorState=${designatorState::class.simpleName}")
                openTelemetryPairingControl()
            },
            interactionEnabled = tileInteractionsEnabled
        )
        if (showUnmatchDialog && designatorState is DesignatorState.Green) {
            val greenState = designatorState as DesignatorState.Green
            AlertDialog(
                onDismissRequest = { showUnmatchDialog = false },
                title = { Text("Change Telemetry Pairing") },
                text = {
                    Text(
                        "Stream \"$streamDesignator\" is paired with Remote ID:\n" +
                            "${greenState.droneSpecState.remoteId}\n\n" +
                            "Unmatch clears any current-run pairing. Remap lets you choose a different drone for this run."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        CTDebug(tag, "StreamTile($streamDesignator) Unmatch confirmed")
                        viewModel.clearStreamTelemetry(streamDesignator)
                        showUnmatchDialog = false
                    }) { Text("Unmatch") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showUnmatchDialog = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            CTDebug(tag, "StreamTile($streamDesignator) Remap: clearing pairing and opening picker")
                            viewModel.clearStreamTelemetry(streamDesignator)
                            showUnmatchDialog = false
                            showPicker = true
                        }) { Text("Remap") }
                    }
                }
            )
        }
        if (showPicker) {
            DroneSpecPickerDialog(
                droneSpecStates = viewModel.droneStates,
                closestMatchRemoteId = viewModel.closestTelemetryRemoteId(streamDesignator),
                onSelect = { (selectedMappedId, droneSpecState) ->
                    CTDebug(tag, "DroneSpecPickerDialog() User paired stream '${streamDesignator}' to telemetry ${selectedMappedId}:${droneSpecState.remoteId}")
                    val warning = viewModel.streamPairingWarning(
                        streamDesignator = streamDesignator,
                        remoteId = droneSpecState.remoteId,
                        mappedId = droneSpecState.mappedId
                    )
                    if (warning != null) {
                        pendingPairingWarning = warning
                        showPicker = false
                    } else {
                        viewModel.bindStreamTelemetry(streamDesignator, droneSpecState.remoteId)
                        showPicker = false
                    }
                },
                onDismiss = {showPicker = false}
            )
        }
        pendingPairingWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { pendingPairingWarning = null },
                title = { Text("Controller Designator Mismatch") },
                text = {
                    Text(
                        "The ${warning.droneLabel} drone with Remote ID \"${warning.remoteId}\" " +
                            "is currently configured for controller designator " +
                            "\"${warning.configuredStreamDesignator}\".\n\n" +
                            "It is recommended that you change the RTMP stream designator to " +
                            "\"${warning.configuredStreamDesignator}\" instead of " +
                            "\"${warning.streamDesignator}\" so other tablets can connect to it."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        CTDebug(tag, "StreamTile($streamDesignator) Pair Anyway runtime override remoteId=${warning.remoteId}")
                        viewModel.bindStreamTelemetry(warning.streamDesignator, warning.remoteId)
                        pendingPairingWarning = null
                    }) { Text("Pair Anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPairingWarning = null }) { Text("Cancel") }
                }
            )
        }
        if (anomalyConfig.enabled && isLocalPlayback && isLocalPlaybackPaused && pendingAnnotationPoint != null && currentFrameTimestampUs != null) {
            LocalPlaybackAnnotationDialog(
                initialPoint = pendingAnnotationPoint!!,
                onDismiss = { pendingAnnotationPoint = null },
                onSave = { verdict, reviewKind, objectType, scenario, note ->
                    viewModel.addLocalPlaybackPointAnnotation(
                        designator = streamDesignator,
                        sourceTimestampUs = currentFrameTimestampUs,
                        xNorm = pendingAnnotationPoint!!.x,
                        yNorm = pendingAnnotationPoint!!.y,
                        verdict = verdict,
                        reviewKind = reviewKind,
                        objectType = objectType,
                        scenario = scenario,
                        note = note,
                        anomalyDebugSummary = localRuntimeSnapshot?.anomalyDebugSummary,
                    )
                    pendingAnnotationPoint = null
                }
            )
        }
    }
}

@Composable
internal fun AnomalySettingsMenuContent(
    viewModel: StreamsViewModel,
    streamDesignator: String,
    anomalyMode: AnomalyDetectorMode,
    isLocalPlayback: Boolean,
    pauseLocalPlaybackOnOpen: Boolean,
    onShowSettings: () -> Unit,
    onShowHelp: () -> Unit,
    onCloseStream: (() -> Unit)?,
    onRestartServer: (() -> Unit)?,
    onDismissMenu: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text("AD Mode: ${anomalyMode.label}") },
        onClick = {
            onDismissMenu()
            onShowSettings()
        }
    )
    DropdownMenuItem(
        text = { Text("AD Help") },
        onClick = {
            onDismissMenu()
            onShowHelp()
        }
    )
    if (isLocalPlayback) {
        DropdownMenuItem(
            text = { Text(if (pauseLocalPlaybackOnOpen) "Pause On Open: On" else "Pause On Open: Off") },
            onClick = {
                onDismissMenu()
                viewModel.setPauseLocalPlaybackOnOpen(!pauseLocalPlaybackOnOpen)
            }
        )
    }
    onRestartServer?.let { restartServer ->
        DropdownMenuItem(
            text = { Text("Restart Streams Server") },
            onClick = {
                onDismissMenu()
                restartServer()
            }
        )
    }
    onCloseStream?.let { closeStream ->
        DropdownMenuItem(
            text = { Text("Close Stream") },
            onClick = {
                onDismissMenu()
                closeStream()
            }
        )
    }
}

@Composable
internal fun AnomalySettingsDialogs(
    viewModel: StreamsViewModel,
    streamDesignator: String,
    anomalyConfig: AnomalyConfig,
    isLocalPlayback: Boolean,
    showAnomalySettingsDialog: Boolean,
    onDismissAnomalySettingsDialog: () -> Unit,
    showAdHelpDialog: Boolean,
    onDismissAdHelpDialog: () -> Unit,
) {
    if (showAnomalySettingsDialog) {
        var sensitivityValue by remember(streamDesignator, anomalyConfig.sensitivity) {
            mutableStateOf(anomalyConfig.sensitivity.coerceIn(0f, 1f))
        }
        var scanZoneValue by remember(streamDesignator, anomalyConfig.scanZone) {
            mutableStateOf(anomalyConfig.scanZone.coerceIn(0.5f, 1f))
        }
        var motionEvidenceSensitivityValue by remember(streamDesignator, anomalyConfig.motionEvidenceSensitivity) {
            mutableStateOf(anomalyConfig.motionEvidenceSensitivity.coerceIn(0f, 1f))
        }
        var minHitsValue by remember(streamDesignator, anomalyConfig.minHits) {
            mutableStateOf(anomalyConfig.minHits.coerceIn(1, 5))
        }
        var colorTargetCandidateLimitValue by remember(streamDesignator, anomalyConfig.colorTargetCandidateLimit) {
            mutableStateOf(anomalyConfig.colorTargetCandidateLimit.coerceIn(1, 4))
        }
        var targetColorFamilyMaskValue by remember(streamDesignator, anomalyConfig.targetColorFamilyMask) {
            mutableStateOf(anomalyConfig.targetColorFamilyMask and TargetColorFamily.allowedMask)
        }
        var showTargetColorsDialog by remember(streamDesignator) { mutableStateOf(false) }
        var modeMenuExpanded by remember(streamDesignator) { mutableStateOf(false) }
        var pendingTargetColorFamilyMaskValue by remember(streamDesignator) {
            mutableStateOf(targetColorFamilyMaskValue)
        }
        var frameStrideValue by remember(streamDesignator, anomalyConfig.frameStride) {
            mutableStateOf(anomalyConfig.frameStride.coerceIn(1, 33))
        }
        var adaptiveMinStrideValue by remember(streamDesignator, anomalyConfig.adaptiveMinStrideFrames) {
            mutableStateOf(anomalyConfig.adaptiveMinStrideFrames.coerceAtLeast(2))
        }
        var adaptiveMaxStrideSecondsValue by remember(streamDesignator, anomalyConfig.adaptiveMaxStrideSeconds) {
            mutableStateOf(anomalyConfig.adaptiveMaxStrideSeconds.coerceIn(0.1f, 10.0f))
        }
        var pixelStepValue by remember(streamDesignator, anomalyConfig.pixelStep) {
            mutableStateOf(anomalyConfig.pixelStep.coerceIn(0, 4))
        }
        var thermalMinDeltaValue by remember(streamDesignator, anomalyConfig.thermalMinDelta) {
            mutableStateOf(anomalyConfig.thermalMinDelta.coerceIn(1.0f, 64.0f))
        }
        var smallTargetFractionValue by remember(streamDesignator, anomalyConfig.smallTargetScreenFraction) {
            mutableStateOf(anomalyConfig.smallTargetScreenFraction.coerceIn(0.0015f, 0.03f))
        }
        fun syncDialogValues(config: AnomalyConfig) {
            sensitivityValue = config.sensitivity.coerceIn(0f, 1f)
            scanZoneValue = config.scanZone.coerceIn(0.5f, 1f)
            motionEvidenceSensitivityValue = config.motionEvidenceSensitivity.coerceIn(0f, 1f)
            minHitsValue = config.minHits.coerceIn(1, 5)
            colorTargetCandidateLimitValue = config.colorTargetCandidateLimit.coerceIn(1, 4)
            targetColorFamilyMaskValue = config.targetColorFamilyMask and TargetColorFamily.allowedMask
            frameStrideValue = config.frameStride.coerceIn(1, 33)
            adaptiveMinStrideValue = config.adaptiveMinStrideFrames.coerceAtLeast(2)
            adaptiveMaxStrideSecondsValue = config.adaptiveMaxStrideSeconds.coerceIn(0.1f, 10.0f)
            pixelStepValue = config.pixelStep.coerceIn(0, 4)
            thermalMinDeltaValue = config.thermalMinDelta.coerceIn(1.0f, 64.0f)
            smallTargetFractionValue = config.smallTargetScreenFraction.coerceIn(0.0015f, 0.03f)
        }
        fun openTargetColorsDialog() {
            pendingTargetColorFamilyMaskValue = targetColorFamilyMaskValue and TargetColorFamily.allowedMask
            showTargetColorsDialog = true
        }
        AlertDialog(
            onDismissRequest = onDismissAnomalySettingsDialog,
            title = { Text("Anomaly Detector") },
            text = {
                val settingsScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(settingsScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val selectedMode = anomalyConfig.detectorMode()
                    Text("AD Mode", style = MaterialTheme.typography.titleSmall)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { modeMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedMode.label, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = modeMenuExpanded,
                            onDismissRequest = { modeMenuExpanded = false },
                        ) {
                            AnomalyDetectorMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        modeMenuExpanded = false
                                        if (mode == AnomalyDetectorMode.TargetColors) {
                                            openTargetColorsDialog()
                                        } else {
                                            viewModel.setAnomalyDetectorMode(streamDesignator, mode)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (selectedMode != AnomalyDetectorMode.Off) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Realtime Defaults")
                            TextButton(onClick = {
                                val defaults = anomalyConfig.resetToRealtimeDefaults()
                                syncDialogValues(defaults)
                                viewModel.resetAnomalyRealtimeDefaults(streamDesignator)
                                CaltopoClient.ShowToast("Anomaly detector reset to realtime defaults.")
                            }) {
                                Text("Reset")
                            }
                        }
                    }
                    if (selectedMode == AnomalyDetectorMode.TargetColors) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Target Colors")
                                Text(
                                    targetColorFamilySummary(targetColorFamilyMaskValue),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { openTargetColorsDialog() }) {
                                Text("Change")
                            }
                        }
                    }
                    if (selectedMode != AnomalyDetectorMode.Off) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Motion")
                        TextButton(onClick = {
                            viewModel.toggleAnomalyAlgorithm(streamDesignator, AnomalyAlgorithm.Motion)
                        }) {
                            Text(if (anomalyConfig.motionEnabled) "On" else "Off")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Saliency")
                        TextButton(onClick = { viewModel.toggleSaliencyEnabled(streamDesignator) }) {
                            Text(if (anomalyConfig.saliencyEnabled) "On" else "Off")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Guide Boxes")
                        TextButton(onClick = { viewModel.toggleShowGuideBoxes(streamDesignator) }) {
                            Text(if (anomalyConfig.showGuideBoxes) "On" else "Off")
                        }
                    }
                    if (selectedMode == AnomalyDetectorMode.Infrared) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show Hottest Region")
                            TextButton(onClick = { viewModel.toggleShowHotOverlay(streamDesignator) }) {
                                Text(if (anomalyConfig.showHotOverlay) "On" else "Off")
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Candidate Blobs")
                        TextButton(onClick = { viewModel.toggleShowCandidateBlobs(streamDesignator) }) {
                            Text(if (anomalyConfig.showCandidateBlobs) "On" else "Off")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Troubleshooting Debug")
                        TextButton(onClick = { viewModel.toggleAnomalyTroubleshootingDebug(streamDesignator) }) {
                            Text(if (anomalyConfig.troubleshootingDebug) "On" else "Off")
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Person Relevance")
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            PersonRelevanceMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = anomalyConfig.personRelevanceMode == mode,
                                    onClick = {
                                        viewModel.setPersonRelevanceMode(streamDesignator, mode)
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = PersonRelevanceMode.entries.size,
                                    ),
                                ) {
                                    Text(mode.label)
                                }
                            }
                        }
                        Text(
                            "Assist is experimental.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            PERSON_RELEVANCE_SUPPORTING_TEXT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Registration")
                        TextButton(onClick = { viewModel.cycleAnomalyRegistrationMode(streamDesignator) }) {
                            Text(anomalyConfig.registrationMode.label)
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Movement Estimator")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppearanceSelectionButton(
                                label = "Legacy",
                                selected = anomalyConfig.movementEstimatorMode == MovementEstimatorMode.LegacyAffine,
                                onClick = {
                                    viewModel.setAnomalyMovementEstimatorMode(
                                        streamDesignator,
                                        MovementEstimatorMode.LegacyAffine
                                    )
                                }
                            )
                            AppearanceSelectionButton(
                                label = "Shadow",
                                selected = anomalyConfig.movementEstimatorMode == MovementEstimatorMode.LayeredShadow,
                                onClick = {
                                    viewModel.setAnomalyMovementEstimatorMode(
                                        streamDesignator,
                                        MovementEstimatorMode.LayeredShadow
                                    )
                                }
                            )
                            AppearanceSelectionButton(
                                label = "Active",
                                selected = anomalyConfig.movementEstimatorMode == MovementEstimatorMode.LayeredActive,
                                onClick = {
                                    viewModel.setAnomalyMovementEstimatorMode(
                                        streamDesignator,
                                        MovementEstimatorMode.LayeredActive
                                    )
                                }
                            )
                        }
                    }
                    if (selectedMode == AnomalyDetectorMode.Infrared) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Infrared Palette")
                            TextButton(onClick = { viewModel.cycleAnomalyThermalPolarity(streamDesignator) }) {
                                Text(anomalyConfig.thermalPolarity.label)
                            }
                        }
                    }
                    Text("Sensitivity ${((sensitivityValue * 100f).toInt())}%")
                    Slider(value = sensitivityValue, onValueChange = { sensitivityValue = it }, valueRange = 0f..1f)
                    Text("Motion Evidence ${((motionEvidenceSensitivityValue * 100f).toInt())}%")
                    Slider(value = motionEvidenceSensitivityValue, onValueChange = { motionEvidenceSensitivityValue = it }, valueRange = 0f..1f)
                    Text("Scan Zone ${((scanZoneValue * 100f).toInt())}%")
                    Slider(value = scanZoneValue, onValueChange = { scanZoneValue = it }, valueRange = 0.5f..1f)
                    Text("Min Hits $minHitsValue")
                    Slider(
                        value = minHitsValue.toFloat(),
                        onValueChange = { minHitsValue = it.toInt().coerceIn(1, 5) },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                    if (selectedMode == AnomalyDetectorMode.ColorUniqueness ||
                        selectedMode == AnomalyDetectorMode.TargetColors
                    ) {
                        Text("Color Candidates $colorTargetCandidateLimitValue")
                        Slider(
                            value = colorTargetCandidateLimitValue.toFloat(),
                            onValueChange = { colorTargetCandidateLimitValue = it.toInt().coerceIn(1, 4) },
                            valueRange = 1f..4f,
                            steps = 2
                        )
                    }
                    Text("Frame Stride ${frameStrideValue}x")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppearanceSelectionButton(
                            label = "Fixed",
                            selected = anomalyConfig.strideMode == AnomalyStrideMode.Fixed,
                            onClick = {
                                viewModel.setAnomalyStrideMode(streamDesignator, AnomalyStrideMode.Fixed)
                            }
                        )
                        AppearanceSelectionButton(
                            label = "Adaptive",
                            selected = anomalyConfig.strideMode == AnomalyStrideMode.Adaptive,
                            onClick = {
                                viewModel.setAnomalyStrideMode(streamDesignator, AnomalyStrideMode.Adaptive)
                            }
                        )
                    }
                    Slider(
                        value = frameStrideValue.toFloat(),
                        onValueChange = { frameStrideValue = it.toInt().coerceIn(1, 33) },
                        valueRange = 1f..33f,
                        steps = 31
                    )
                    if (anomalyConfig.strideMode == AnomalyStrideMode.Adaptive) {
                        Text("Adaptive Min ${adaptiveMinStrideValue} frames")
                        Slider(
                            value = adaptiveMinStrideValue.toFloat(),
                            onValueChange = { adaptiveMinStrideValue = it.toInt().coerceIn(2, 33) },
                            valueRange = 2f..33f,
                            steps = 30
                        )
                        Text("Adaptive Max ${"%.1f".format(adaptiveMaxStrideSecondsValue)}s")
                        Slider(
                            value = adaptiveMaxStrideSecondsValue,
                            onValueChange = { adaptiveMaxStrideSecondsValue = it.coerceIn(0.1f, 10.0f) },
                            valueRange = 0.1f..10.0f,
                        )
                    }
                    Text(if (pixelStepValue <= 0) "Detail Auto" else "Detail ${pixelStepValue}px step")
                    Slider(
                        value = pixelStepValue.toFloat(),
                        onValueChange = { pixelStepValue = it.toInt().coerceIn(0, 4) },
                        valueRange = 0f..4f,
                        steps = 3
                    )
                    if (selectedMode == AnomalyDetectorMode.Infrared) {
                        Text("Thermal Min Delta ${"%.1f".format(thermalMinDeltaValue)}")
                        Slider(
                            value = thermalMinDeltaValue,
                            onValueChange = { thermalMinDeltaValue = it },
                            valueRange = 1f..64f,
                        )
                    }
                    val smallTargetDenominator =
                        (1.0f / smallTargetFractionValue.coerceIn(0.0015f, 0.03f)).roundToInt()
                    Text("Small Target Scale 1/$smallTargetDenominator screen diagonal")
                    Slider(value = smallTargetFractionValue, onValueChange = { smallTargetFractionValue = it }, valueRange = 0.0015f..0.03f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val guideColor = Color(0xFF80CBC4).copy(alpha = 0.80f)
                            val previewConfig = anomalyConfig.copy(
                                scanZone = scanZoneValue,
                                smallTargetScreenFraction = smallTargetFractionValue,
                            )
                            val (scanZoneWidth, scanZoneHeight) = previewConfig.scanZoneSize(
                                frameWidth = size.width,
                                frameHeight = size.height,
                            )
                            val scanZoneTopLeft = Offset(
                                x = (size.width - scanZoneWidth) * 0.5f,
                                y = (size.height - scanZoneHeight) * 0.5f,
                            )
                            drawRect(
                                color = guideColor,
                                topLeft = scanZoneTopLeft,
                                size = Size(scanZoneWidth, scanZoneHeight),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            val targetSpanPx = anomalyConfig.copy(
                                smallTargetScreenFraction = smallTargetFractionValue
                            ).effectiveSmallTargetSpanPx(
                                frameWidth = size.width.roundToInt(),
                                frameHeight = size.height.roundToInt(),
                            ).coerceAtMost(size.minDimension * 0.40f)
                            val topLeft = Offset(
                                x = (size.width - targetSpanPx) * 0.5f,
                                y = (size.height - targetSpanPx) * 0.5f,
                            )
                            drawRect(
                                color = guideColor,
                                topLeft = topLeft,
                                size = Size(targetSpanPx, targetSpanPx),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                    if (isLocalPlayback) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Clear Review Annotations")
                            TextButton(onClick = { viewModel.clearLocalPlaybackReviewAnnotations(streamDesignator) }) {
                                Text("Clear")
                            }
                        }
                    }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setAnomalySensitivity(streamDesignator, sensitivityValue)
                        viewModel.setMotionEvidenceSensitivity(streamDesignator, motionEvidenceSensitivityValue)
                        viewModel.setScanZone(streamDesignator, scanZoneValue)
                        viewModel.setMinHits(streamDesignator, minHitsValue)
                        viewModel.setColorTargetCandidateLimit(streamDesignator, colorTargetCandidateLimitValue)
                        viewModel.setTargetColorFamilyMask(streamDesignator, targetColorFamilyMaskValue)
                        viewModel.setAnomalyFrameStride(streamDesignator, frameStrideValue)
                        viewModel.setAnomalyAdaptiveStride(
                            streamDesignator,
                            adaptiveMinStrideValue,
                            adaptiveMaxStrideSecondsValue
                        )
                        viewModel.setAnomalyPixelStep(streamDesignator, pixelStepValue)
                        viewModel.setAnomalyThermalMinDelta(streamDesignator, thermalMinDeltaValue)
                        viewModel.setAnomalySmallTargetScreenFraction(streamDesignator, smallTargetFractionValue)
                        onDismissAnomalySettingsDialog()
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = onDismissAnomalySettingsDialog) { Text("Close") }
            }
        )
        if (showTargetColorsDialog) {
            AlertDialog(
                onDismissRequest = { showTargetColorsDialog = false },
                title = { Text("Target Colors") },
                text = {
                    val targetColorsScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(targetColorsScroll),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TargetColorFamily.entries.chunked(2).forEach { rowFamilies ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                rowFamilies.forEach { family ->
                                    val selected = (pendingTargetColorFamilyMaskValue and family.nativeMask) != 0
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selected,
                                            onCheckedChange = { checked ->
                                                pendingTargetColorFamilyMaskValue = if (checked) {
                                                    pendingTargetColorFamilyMaskValue or family.nativeMask
                                                } else {
                                                    pendingTargetColorFamilyMaskValue and family.nativeMask.inv()
                                                } and TargetColorFamily.allowedMask
                                            }
                                        )
                                        Text(family.label)
                                    }
                                }
                                if (rowFamilies.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = pendingTargetColorFamilyMaskValue != 0,
                        onClick = {
                            val nextMask = pendingTargetColorFamilyMaskValue and TargetColorFamily.allowedMask
                            targetColorFamilyMaskValue = nextMask
                            viewModel.setAnomalyDetectorMode(
                                streamDesignator,
                                if (nextMask == 0) {
                                    AnomalyDetectorMode.ColorUniqueness
                                } else {
                                    AnomalyDetectorMode.TargetColors
                                },
                            )
                            viewModel.setTargetColorFamilyMask(streamDesignator, nextMask)
                            showTargetColorsDialog = false
                        }
                    ) { Text("Apply") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (anomalyConfig.detectorMode() == AnomalyDetectorMode.TargetColors) {
                            TextButton(
                                onClick = {
                                    targetColorFamilyMaskValue = 0
                                    viewModel.setTargetColorFamilyMask(streamDesignator, 0)
                                    viewModel.setAnomalyDetectorMode(
                                        streamDesignator,
                                        AnomalyDetectorMode.ColorUniqueness,
                                    )
                                    showTargetColorsDialog = false
                                }
                            ) { Text("Clear") }
                        }
                        TextButton(onClick = { showTargetColorsDialog = false }) { Text("Cancel") }
                    }
                }
            )
        }
    }

    if (showAdHelpDialog) {
        val helpScroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = onDismissAdHelpDialog,
            title = { Text("Anomaly Detection Help") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(helpScroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("AD mode")
                    Text("The stream legend shows the current mode. Tap it to select Off, Color Uniqueness, Target Colors, or Infrared.")
                    Text("The settings below the mode selector apply only to the selected detector. Settings last for this app session; startup returns AD to Off with realtime defaults.")
                    Text("Sensitivity: Lower values are stricter and require a stronger outlier before drawing a box.")
                    Text("Scan Zone: Centered portion of the frame scanned for anomalies. Lower values ignore more of the outer frame.")
                    Text("Min Hits: Consecutive analyzed-frame hits required in roughly the same motion-stabilized region before a detection is promoted.")
                    Text("Frame Stride: Analyze every Nth frame. Higher stride reduces CPU load but may miss brief motion.")
                    Text("Detail: Pixel sampling step for appearance analysis. Auto chooses a default from frame size; smaller steps inspect more detail at higher cost.")
                    Text("ShowHot: Draws a red ring around the hottest region in the frame as a thermal debug aid.")
                    Text("Guide Boxes: Shows cyan outlines for the centered scan zone and the maximum small-target size.")
                    Text("Saliency: Enables the unified saliency detector. Turn it off to match harness runs that omit the saliency algorithm.")
                    Text("Motion: Motion evidence sensitivity. Higher values strengthen the motion detector and also increase the influence of motion support in combined anomaly scoring.")
                    Text("Registration: Chooses the motion-registration backend used to stabilize detections. Affine usually tracks camera motion more accurately; GMV is simpler and may be cheaper.")
                    Text("Movement Estimator: Legacy keeps current behavior. Shadow computes layered parallax telemetry without changing detections. Active applies layered parallax suppression to motion scoring and is still experimental.")
                    Text("Infrared Palette: White Hot means brighter pixels are hotter; Black Hot means darker pixels are hotter.")
                    Text("Thermal Min Delta: Minimum infrared contrast before thermal/saliency evidence is considered. Raise it to ignore weaker temperature differences.")
                    Text("Small: Maximum on-screen small-target box size. The cyan rectangle shows the largest blob the anomaly detector should treat as a 'small target' for the squinter. As the camera zooms in, targets larger than this are down-ranked and can disappear.")
                    if (isLocalPlayback) {
                        Text("Playback review controls")
                        Text("Back: Step backward through the recent paused-frame history.")
                        Text("Run/Pause: Resume or freeze captured-video playback.")
                        Text("Step: Advance forward one frame while paused.")
                        Text("Pause On Open: Optional local-playback mode that starts newly opened clips paused on their first available frame.")
                        Text("Notes: Number of annotations saved for the held frame.")
                        Text("Tap on a paused frame while AD is on to open an annotation dialog for that frame point.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissAdHelpDialog) { Text("Close") }
            }
        )
    }
}

@Composable
private fun AppearanceSelectionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    ) {
        Text(label)
    }
}

private fun formatPlaybackTimestampUs(timestampUs: Long): String {
    val totalMs = (timestampUs / 1_000L).coerceAtLeast(0L)
    val minutes = totalMs / 60_000L
    val seconds = (totalMs % 60_000L) / 1_000L
    val millis = totalMs % 1_000L
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}

private fun unzoomedStreamPoint(
    tapOffset: Offset,
    containerWidth: Float,
    containerHeight: Float,
    scale: Float,
    offset: Offset,
): Offset {
    if (scale <= 1.001f) return tapOffset
    val center = Offset(containerWidth * 0.5f, containerHeight * 0.5f)
    return Offset(
        x = center.x + ((tapOffset.x - center.x - offset.x) / scale),
        y = center.y + ((tapOffset.y - center.y - offset.y) / scale),
    )
}

internal data class StreamClueCaptureTarget(
    val textureView: TextureView,
    val requiresRenderedFrame: Boolean,
    val label: String
)

internal fun streamClueCaptureStateKey(
    designator: String,
    @Suppress("UNUSED_PARAMETER") streamRevision: Long
): String = designator

internal fun streamClueCaptureReady(
    hasCaptureTarget: Boolean,
    renderedFrameCount: Int,
    requiresRenderedFrame: Boolean
): Boolean =
    hasCaptureTarget && (!requiresRenderedFrame || renderedFrameCount > 0)

internal fun zoomedSnapshotBitmap(
    source: Bitmap,
    scale: Float,
    offset: Offset,
): Bitmap {
    if (scale <= 1.001f && kotlin.math.abs(offset.x) <= 0.5f && kotlin.math.abs(offset.y) <= 0.5f) {
        return source
    }
    val output = Bitmap.createBitmap(
        source.width,
        source.height,
        source.config ?: Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(output)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    canvas.drawColor(AndroidColor.BLACK)
    val centerX = source.width * 0.5f
    val centerY = source.height * 0.5f
    canvas.translate(centerX + offset.x, centerY + offset.y)
    canvas.scale(scale.coerceAtLeast(1f), scale.coerceAtLeast(1f))
    canvas.translate(-centerX, -centerY)
    canvas.drawBitmap(source, 0f, 0f, paint)
    return output
}

private fun Float.formatZoom(): String =
    if (this >= 3.95f) {
        "4"
    } else {
        "%.1f".format(this)
    }

@Composable
private fun OutlinedLegendText(
    text: String,
    fillColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Black,
    )
    Box(modifier = modifier) {
        Text(
            text = text,
            color = Color.White,
            style = style.copy(drawStyle = Stroke(width = 3f, miter = 2f)),
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = text,
            color = fillColor,
            style = style,
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

@Composable
private fun LocalPlaybackAnnotationDialog(
    initialPoint: Offset,
    onDismiss: () -> Unit,
    onSave: (
        LocalPlaybackAnnotationVerdict,
        LocalPlaybackReviewKind,
        LocalPlaybackAnnotationType,
        LocalPlaybackScenario?,
        String,
    ) -> Unit,
) {
    var verdict by remember(initialPoint) { mutableStateOf(LocalPlaybackAnnotationVerdict.Bad) }
    var reviewKind by remember(initialPoint) { mutableStateOf(LocalPlaybackReviewKind.FalsePositive) }
    var objectType by remember(initialPoint) { mutableStateOf(LocalPlaybackAnnotationType.Person) }
    var scenario by remember(initialPoint) { mutableStateOf<LocalPlaybackScenario?>(null) }
    var note by remember(initialPoint) { mutableStateOf("") }
    var scenarioMenuExpanded by remember(initialPoint) { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    fun forceHideKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        forceHideKeyboard()
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { })
                    },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Annotate Frame Point",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                forceHideKeyboard()
                            }
                        ) {
                            Text("Hide")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        TextButton(
                            onClick = { onSave(verdict, reviewKind, objectType, scenario, note) }
                        ) {
                            Text("Save")
                        }
                    }
                }
                Text(
                    text = "Point ${(initialPoint.x * 100f).toInt()}%, ${(initialPoint.y * 100f).toInt()}%"
                )
                    val dialogScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 280.dp, max = 340.dp)
                            .verticalScroll(dialogScroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Review")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                LocalPlaybackReviewKind.MissedTarget,
                                LocalPlaybackReviewKind.FalsePositive,
                                LocalPlaybackReviewKind.CorrectDetection,
                                LocalPlaybackReviewKind.Unsure,
                            ).forEach { candidate ->
                                TextButton(onClick = { reviewKind = candidate }) {
                                    Text(if (reviewKind == candidate) "[${candidate.shortLabel}]" else candidate.shortLabel)
                                }
                            }
                        }
                        Text("Verdict")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LocalPlaybackAnnotationVerdict.entries.forEach { candidate ->
                                TextButton(onClick = { verdict = candidate }) {
                                    Text(if (verdict == candidate) "[${candidate.shortLabel}]" else candidate.shortLabel)
                                }
                            }
                        }
                        Text("Type")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LocalPlaybackAnnotationType.entries.forEach { candidate ->
                                TextButton(onClick = { objectType = candidate }) {
                                    Text(if (objectType == candidate) "[${candidate.shortLabel}]" else candidate.shortLabel)
                                }
                            }
                        }
                        Text("Scenario")
                        Box {
                            TextButton(onClick = { scenarioMenuExpanded = true }) {
                                Text(scenario?.shortLabel ?: "Choose Scenario")
                            }
                            DropdownMenu(
                                expanded = scenarioMenuExpanded,
                                onDismissRequest = { scenarioMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None") },
                                    onClick = {
                                        scenario = null
                                        scenarioMenuExpanded = false
                                    }
                                )
                                LocalPlaybackScenario.entries.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.shortLabel) },
                                        onClick = {
                                            scenario = candidate
                                            scenarioMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp),
                            value = note,
                            onValueChange = { note = it },
                            singleLine = false,
                            minLines = 4,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    forceHideKeyboard()
                                }
                            ),
                            textStyle = TextStyle(color = Color.Black),
                            label = { Text("Note") },
                            placeholder = { Text("What is here or what is happening?") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                focusedBorderColor = Color(0xFF1E88E5),
                                unfocusedBorderColor = Color.DarkGray,
                                focusedLabelColor = Color.Black,
                                unfocusedLabelColor = Color.DarkGray,
                                focusedPlaceholderColor = Color.Gray,
                                unfocusedPlaceholderColor = Color.Gray,
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                cursorColor = Color(0xFF1E88E5),
                            ),
                        )
                        Text(
                            text = "Use Done, Hide Keyboard, or tap outside the field to dismiss the keyboard.",
                            color = Color.DarkGray,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StreamPlayer(
    viewModel: StreamsViewModel,
    state: StreamState,
    designator: String,
    streamRevision: Long,
    modifier: Modifier = Modifier,
    fillFrame: Boolean = false,
    onTextureViewReady: (TextureView) -> Unit,
    onPlayerTextureViewReady: (TextureView) -> Unit = {},
    onTextureFrameUpdated: (Int) -> Unit = {}
) {
    val tag = "StreamPlayer"
    val surfaceTag = "StreamTile"
    if (state != StreamState.LIVE) return

    if (!viewModel.useFfmpegRender(designator)) {
        val player = viewModel.getExoPlayerFor(designator)
        if (player != null) {
            StreamPlayerView(
                player = player,
                modifier = modifier,
                fillFrame = fillFrame,
                onPlayerTextureViewReady = onPlayerTextureViewReady
            )
        } else {
            Box(modifier = modifier.background(Color.Black))
        }
        return
    }

    var attachedTextureView by remember(designator) { mutableStateOf<TextureView?>(null) }
    var attachedSurface by remember(designator) { mutableStateOf<Surface?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).also { textureView ->
                CTDebug(
                    surfaceTag,
                    "FFmpeg TextureView factory for $designator viewId=${System.identityHashCode(textureView)}"
                )
                attachedTextureView = textureView
                onTextureViewReady(textureView)
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var lastUpdatedLogAtMs = 0L
                    private var updateLogCount = 0
                    private var surfaceFrameCount = 0

                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        CTDebug(
                            surfaceTag,
                            "SurfaceTexture available for $designator viewId=${System.identityHashCode(textureView)} textureId=${System.identityHashCode(surfaceTexture)} size=${width}x${height}"
                        )
                        attachedSurface?.release()
                        attachedSurface = Surface(surfaceTexture)
                        val bound = viewModel.bindFfmpegRenderSurface(
                            designator,
                            attachedSurface!!
                        )
                        if (!bound) {
                            CTDebug(tag, "FFmpeg surface bind deferred for $designator")
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        CTDebug(
                            surfaceTag,
                            "SurfaceTexture size changed for $designator textureId=${System.identityHashCode(surfaceTexture)} size=${width}x${height}"
                        )
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        CTDebug(
                            surfaceTag,
                            "SurfaceTexture destroyed for $designator textureId=${System.identityHashCode(surfaceTexture)}"
                        )
                        viewModel.unbindFfmpegRenderSurface(designator, attachedSurface)
                        attachedSurface?.release()
                        attachedSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        surfaceFrameCount += 1
                        onTextureFrameUpdated(surfaceFrameCount)
                        val now = System.currentTimeMillis()
                        if (updateLogCount < 3 || now - lastUpdatedLogAtMs >= 5_000L) {
                            lastUpdatedLogAtMs = now
                            updateLogCount += 1
                            CTDebug(
                                surfaceTag,
                                "SurfaceTexture updated for $designator textureId=${System.identityHashCode(surfaceTexture)} count=$surfaceFrameCount"
                            )
                        }
                    }
                }
            }
        },
        update = { textureView ->
            if (attachedTextureView !== textureView) {
                CTDebug(
                    surfaceTag,
                    "FFmpeg TextureView update rebound for $designator oldViewId=${attachedTextureView?.let { System.identityHashCode(it) }} newViewId=${System.identityHashCode(textureView)}"
                )
                attachedTextureView = textureView
            }
            onTextureViewReady(textureView)
        }
    )

    DisposableEffect(designator) {
        onDispose {
            CTDebug(
                surfaceTag,
                "FFmpeg TextureView dispose for $designator viewId=${attachedTextureView?.let { System.identityHashCode(it) }}"
            )
            attachedSurface?.release()
            attachedSurface = null
            attachedTextureView = null
        }
    }
}
