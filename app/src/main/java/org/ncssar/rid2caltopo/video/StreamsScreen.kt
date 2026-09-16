package org.ncssar.rid2caltopo.video

import OverLimitDroneUiState
import StreamsLayoutMode
import StreamsViewModel
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.MediaMTXStatus
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamPanel
import org.ncssar.rid2caltopo.notam.NotamStatusChip
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionPanel
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionStatusChip
import org.ncssar.rid2caltopo.ui.CaltopoActionInterface
import org.ncssar.rid2caltopo.ui.CaltopoConnectionState
import org.ncssar.rid2caltopo.ui.ClueSubmissionSheet
import org.ncssar.rid2caltopo.ui.DroneSignalLossAlertCenter
import org.ncssar.rid2caltopo.ui.ResumeProximityAlertButton
import org.ncssar.rid2caltopo.ui.SignalStrengthBars
import org.ncssar.rid2caltopo.ui.SignalLossAlertButton
import org.ncssar.rid2caltopo.ui.SignalLossAlertDialog
import org.opendroneid.android.bluetooth.DroneScoutBridgeMonitor

private const val EMPTY_STREAMS_SETTINGS_DESIGNATOR = "__empty_streams_defaults__"
private const val STREAM_PIP_FRAME_PADDING_DP = 24f

internal data class StreamPipInsetSize(
    val width: Float,
    val height: Float
)

internal fun streamPipInsetSize(
    maxWidth: Float,
    maxHeight: Float,
    insetFraction: Float,
    aspectRatio: Float = streamPipFullFrameAspectRatio(maxWidth, maxHeight),
    padding: Float = STREAM_PIP_FRAME_PADDING_DP
): StreamPipInsetSize {
    val safeAspectRatio = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio else 1f
    val maxInsetWidth = (maxWidth - padding).coerceAtLeast(1f)
    val maxInsetHeight = (maxHeight - padding).coerceAtLeast(1f)
    val desiredWidth = maxInsetWidth * insetFraction
    val insetWidth = minOf(desiredWidth, maxInsetHeight * safeAspectRatio)
    return StreamPipInsetSize(width = insetWidth, height = insetWidth / safeAspectRatio)
}

internal fun streamPipFullFrameAspectRatio(width: Float, height: Float): Float =
    if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
        width / height
    } else {
        1f
    }

internal fun streamPipHasStreamContent(visibleStreamCount: Int, focusedPath: String?): Boolean =
    visibleStreamCount > 0 || focusedPath != null

internal fun streamPipInsetTapLayoutMode(currentLayoutMode: StreamsLayoutMode): StreamsLayoutMode =
    when (currentLayoutMode) {
        StreamsLayoutMode.Streams -> StreamsLayoutMode.Map
        StreamsLayoutMode.Map -> StreamsLayoutMode.Streams
        StreamsLayoutMode.Both -> StreamsLayoutMode.Both
    }

internal fun shouldShowMapPipInset(
    pipEnabled: Boolean,
    layoutMode: StreamsLayoutMode
): Boolean = pipEnabled && layoutMode == StreamsLayoutMode.Streams

internal fun shouldShowStreamsPipInset(
    pipEnabled: Boolean,
    layoutMode: StreamsLayoutMode
): Boolean = pipEnabled && layoutMode == StreamsLayoutMode.Map

internal data class StreamsFullScreenChrome(
    val showTopBar: Boolean,
    val showExitChip: Boolean
)

internal fun streamsFullScreenChrome(
    fullScreen: Boolean,
    externalContentActive: Boolean
): StreamsFullScreenChrome {
    val active = fullScreen && !externalContentActive
    return StreamsFullScreenChrome(
        showTopBar = !active,
        showExitChip = active
    )
}

internal fun shouldShowEnterFullScreenChip(
    fullScreen: Boolean,
    externalContentActive: Boolean
): Boolean = !fullScreen && !externalContentActive

internal data class FullScreenExitChipLayout(
    val minWidthDp: Float,
    val minHeightDp: Float,
    val endPaddingDp: Float
)

internal fun fullScreenExitChipLayout(): FullScreenExitChipLayout =
    FullScreenExitChipLayout(
        minWidthDp = 96f,
        minHeightDp = 48f,
        endPaddingDp = 84f
    )

internal data class StreamTileFocusPresentation(
    val effectiveFocused: Boolean,
    val showFocusBorder: Boolean
)

internal data class StreamTileChromePresentation(
    val fillContainer: Boolean,
    val showStandaloneTelemetryOverlay: Boolean
)

internal fun streamTileChromePresentation(
    fullScreenContent: Boolean,
    focused: Boolean
): StreamTileChromePresentation =
    StreamTileChromePresentation(
        fillContainer = fullScreenContent && focused,
        showStandaloneTelemetryOverlay = !focused
    )

internal fun streamTileFocusPresentation(
    displayedTileCount: Int,
    explicitlyFocused: Boolean
): StreamTileFocusPresentation {
    val singleDisplayedTile = displayedTileCount == 1
    return StreamTileFocusPresentation(
        effectiveFocused = singleDisplayedTile || explicitlyFocused,
        showFocusBorder = !singleDisplayedTile && explicitlyFocused
    )
}

private fun restartMediaMtxServer(context: android.content.Context) {
    val appContext = context.applicationContext
    MediaMTXService.requestRestart(appContext)
    CaltopoClient.ShowToast("Streams server restarted. Connected publishers will reconnect if supported.")
    CTDebug("StreamsPane", "User requested MediaMTXService restart from streams settings.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(
    viewModel: StreamsViewModel = viewModel(),
    onBack: () -> Unit,
    onMapStatusTap: () -> Unit = {},
    onPlayCapturedVideo: (() -> Unit)? = null,
    showNavigation: Boolean = true,
    externalContentMode: ExternalDisplayContentMode? = null,
    allowModalDialogs: Boolean = true,
    remoteVideoStatus: String? = null,
    remoteVideoActive: Boolean = false,
    remoteVideoDesignator: String? = null,
    remoteRequesterEmail: String? = null,
    remoteVideoMicrophoneEnabled: Boolean = false,
    remoteVideoMicrophoneError: String? = null,
    onToggleRemoteVideoMicrophone: () -> Unit = {},
    onTerminateRemoteVideo: () -> Unit = {},
) {
    val currentOnBack = rememberUpdatedState(onBack)
    val releaseStreamsUiConsumer = remember(viewModel) {
        val removeConsumer = viewModel.addStreamsUiConsumer()
        var removed = false
        {
            if (!removed) {
                removed = true
                removeConsumer()
            }
        }
    }
    val handleBack = remember(releaseStreamsUiConsumer) {
        {
            releaseStreamsUiConsumer()
            currentOnBack.value()
        }
    }

    DisposableEffect(releaseStreamsUiConsumer) {
        onDispose {
            releaseStreamsUiConsumer()
        }
    }

    val isServerRunning = MediaMTXStatus.isServerRunning
    val serverExitReason = MediaMTXStatus.serverExitReason
    val controllerEndpoints = rememberControllerEndpoints()
    val serverStatus = when {
        isServerRunning -> controllerEndpointInstructions(controllerEndpoints)
        serverExitReason.isNotEmpty() -> "\uD83D\uDD34 Server exited: $serverExitReason"
        else             -> "\uD83D\uDFE1 Starting"
    }
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val mapName = viewModel.mapName
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
    val airspaceUiState by AirspaceCenter.uiState.collectAsStateWithLifecycle()
    val landRestrictionUiState by LandRestrictionCenter.uiState.collectAsStateWithLifecycle()
    val overLimitDrones by viewModel.overLimitDrones.collectAsStateWithLifecycle()
    val signalLossFlights by DroneSignalLossAlertCenter.flights.collectAsStateWithLifecycle()
    val bridgeSignal by DroneScoutBridgeMonitor.signal.collectAsStateWithLifecycle()
    var bridgeSignalClockMs by remember {
        mutableLongStateOf(System.nanoTime() / 1_000_000L)
    }
    LaunchedEffect(Unit) {
        while (true) {
            bridgeSignalClockMs = System.nanoTime() / 1_000_000L
            delay(1_000L)
        }
    }
    val bridgeRssi = DroneScoutBridgeMonitor.currentRssi(
        signal = bridgeSignal,
        nowMonotonicMs = bridgeSignalClockMs,
    )
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var confirmRemoteVideoTermination by remember { mutableStateOf(false) }
    LaunchedEffect(remoteVideoActive) {
        if (!remoteVideoActive) confirmRemoteVideoTermination = false
    }
    val persistedLayoutMode by viewModel.layoutMode.collectAsStateWithLifecycle()
    val streamPipUiState = viewModel.streamPipUiState
    val layoutMode = when (externalContentMode) {
        ExternalDisplayContentMode.StreamsGrid,
        ExternalDisplayContentMode.ObserverMode -> StreamsLayoutMode.Streams
        ExternalDisplayContentMode.MapOnly -> StreamsLayoutMode.Map
        ExternalDisplayContentMode.Split -> StreamsLayoutMode.Both
        null -> persistedLayoutMode
    }
    var showNotamPanel by remember { mutableStateOf(false) }
    var showLandRestrictionPanel by remember { mutableStateOf(false) }
    var showPerformancePanel by remember { mutableStateOf(false) }
    var showCompliancePanel by remember { mutableStateOf(false) }
    var showSignalLossPanel by remember { mutableStateOf(false) }
    var streamsFullScreen by remember { mutableStateOf(false) }
    BackHandler(enabled = showNavigation) {
        if (streamsFullScreen) {
            streamsFullScreen = false
        } else {
            handleBack()
        }
    }
    val fullScreenChrome = streamsFullScreenChrome(
        fullScreen = streamsFullScreen,
        externalContentActive = externalContentMode != null
    )
    ApplyStreamsFullScreenSystemBars(fullScreenChrome.showExitChip)
    val allOverLimitMuted = overLimitDrones.isNotEmpty() && overLimitDrones.all { it.muted }
    val allSignalLossMuted = signalLossFlights.isNotEmpty() && signalLossFlights.all { it.muted }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            if (fullScreenChrome.showTopBar) {
                TopAppBar(
                    modifier = Modifier.pointerInput(handleBack) {
                        detectTapGestures(
                            onDoubleTap = { handleBack() }
                        )
                    },
                    title = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NotamStatusChip(
                                state = notamUiState,
                                airspaceState = airspaceUiState,
                                onClick = { showNotamPanel = true },
                                outerPadding = PaddingValues(0.dp)
                            )
                            LandRestrictionStatusChip(
                                state = landRestrictionUiState,
                                onClick = { showLandRestrictionPanel = true },
                                outerPadding = PaddingValues(start = 8.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = serverStatus,
                                modifier = Modifier
                                    .clickable { showPerformancePanel = true }
                                    .padding(end = 8.dp),
                                fontSize = 14.sp,
                            )
                            StreamsMapStatusButton(
                                mapName = mapName,
                                onClick = onMapStatusTap,
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .height(36.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    },
                    navigationIcon = if (showNavigation) {
                        {
                            IconButton(onClick = handleBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    } else {
                        {}
                    },
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (
                                shouldShowEnterFullScreenChip(
                                    fullScreen = streamsFullScreen,
                                    externalContentActive = externalContentMode != null
                                )
                            ) {
                                LayoutToggleChip(
                                    label = "Enter FS",
                                    selected = false,
                                    onClick = { streamsFullScreen = true }
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            ComplianceAlertBell(
                                overLimitDrones = overLimitDrones,
                                allOverLimitMuted = allOverLimitMuted,
                                onClick = { showCompliancePanel = true }
                            )
                            SignalLossAlertButton(
                                flights = signalLossFlights,
                                allMuted = allSignalLossMuted,
                                onClick = { showSignalLossPanel = true }
                            )
                            ResumeProximityAlertButton()
                            if (externalContentMode == null) {
                                LayoutToggleChip(
                                    label = if (streamPipUiState.enabled) "PiP:On" else "PiP:Off",
                                    selected = streamPipUiState.enabled,
                                    onClick = { viewModel.setStreamPipEnabled(!streamPipUiState.enabled) }
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            BridgeSignalIndicator(rssi = bridgeRssi)
                        }
                    }
                )
            }
            if (!remoteVideoStatus.isNullOrBlank()) {
                Surface(
                    color = if (remoteVideoActive) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    contentColor = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = remoteVideoMicrophoneError?.takeIf { it.isNotBlank() }
                                ?: remoteVideoStatus,
                            modifier = Modifier.weight(1f),
                            color = if (!remoteVideoMicrophoneError.isNullOrBlank()) {
                                Color(0xFFB3261E)
                            } else {
                                Color(0xFF1F2937)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (remoteVideoActive) {
                            IconButton(onClick = onToggleRemoteVideoMicrophone) {
                                Box(
                                    modifier = Modifier.width(42.dp).height(42.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = if (remoteVideoMicrophoneEnabled) {
                                            "Turn microphone off"
                                        } else {
                                            "Turn microphone on"
                                        },
                                    )
                                    if (remoteVideoMicrophoneEnabled) {
                                        Icon(
                                            imageVector = Icons.Default.Campaign,
                                            contentDescription = null,
                                            tint = Color(0xFF1B8F3A),
                                            modifier = Modifier.width(18.dp).height(18.dp)
                                                .offset(x = 14.dp, y = (-12).dp),
                                        )
                                    } else {
                                        Canvas(modifier = Modifier.width(32.dp).height(32.dp)) {
                                            drawLine(
                                                color = Color.Red,
                                                start = Offset(3f, size.height - 3f),
                                                end = Offset(size.width - 3f, 3f),
                                                strokeWidth = 4f,
                                            )
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { confirmRemoteVideoTermination = true }) {
                                Text("Terminate")
                            }
                        }
                    }
                }
            }

            if (confirmRemoteVideoTermination && remoteVideoActive) {
                AlertDialog(
                    onDismissRequest = { confirmRemoteVideoTermination = false },
                    title = { Text("Terminate remote video?") },
                    text = {
                        Text("The current viewer will immediately lose video and audio.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmRemoteVideoTermination = false
                                onTerminateRemoteVideo()
                            },
                        ) {
                            Text("Terminate")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRemoteVideoTermination = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            Box(Modifier.fillMaxSize()) {
                if (externalContentMode == null) {
                    val effectiveSplitFraction = when (layoutMode) {
                        StreamsLayoutMode.Streams -> MAX_SPLIT_FRACTION
                        StreamsLayoutMode.Map -> MIN_SPLIT_FRACTION
                        StreamsLayoutMode.Both -> splitFraction
                    }
                    SplitStreamsAndMap(
                        viewModel = viewModel,
                        onPlayCapturedVideo = onPlayCapturedVideo.takeIf { allowModalDialogs },
                        splitFraction = effectiveSplitFraction,
                        onSplitFractionChange = { next ->
                            splitFraction = next
                            viewModel.setLayoutMode(layoutModeForSplitFraction(next))
                        },
                        onMapStatusTap = onMapStatusTap,
                        onStreamsPaneTap = {
                            splitFraction = MAX_SPLIT_FRACTION
                            viewModel.setLayoutMode(StreamsLayoutMode.Streams)
                        },
                        onMapPaneTap = {
                            splitFraction = MIN_SPLIT_FRACTION
                            viewModel.setLayoutMode(StreamsLayoutMode.Map)
                        },
                        remoteVideoDesignator = remoteVideoDesignator,
                        remoteRequesterEmail = remoteRequesterEmail,
                    )
                } else {
                    when (layoutMode) {
                        StreamsLayoutMode.Both -> {
                            SplitStreamsAndMap(
                                viewModel = viewModel,
                                onPlayCapturedVideo = onPlayCapturedVideo.takeIf { allowModalDialogs },
                                splitFraction = splitFraction,
                                onSplitFractionChange = { splitFraction = it },
                                onMapStatusTap = onMapStatusTap,
                                onStreamsPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Streams) },
                                onMapPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Map) },
                                remoteVideoDesignator = remoteVideoDesignator,
                                remoteRequesterEmail = remoteRequesterEmail,
                            )
                        }

                        StreamsLayoutMode.Streams -> {
                            StreamsGrid(
                                viewModel = viewModel,
                                onPlayCapturedVideo = onPlayCapturedVideo.takeIf { allowModalDialogs },
                                onMapStatusTap = onMapStatusTap,
                                fullScreenContent = fullScreenChrome.showExitChip,
                                remoteVideoDesignator = remoteVideoDesignator,
                                remoteRequesterEmail = remoteRequesterEmail,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        StreamsLayoutMode.Map -> {
                        SplitMapPane(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        }
                    }
                }

                val pipEnabled = streamPipUiState.enabled && externalContentMode == null
                if (shouldShowMapPipInset(pipEnabled = pipEnabled, layoutMode = layoutMode)) {
                    val swapToMap = {
                        val nextLayout = streamPipInsetTapLayoutMode(layoutMode)
                        viewModel.setLayoutMode(nextLayout)
                    }
                    StreamPipInsetFrame(
                        editorMode = streamPipUiState.editorMode,
                        insetFraction = streamPipUiState.insetFraction,
                        onTap = swapToMap,
                        onLongPress = { viewModel.toggleStreamPipEditorModeFromLongPress() },
                        onResizeFractionChange = { next -> viewModel.setStreamPipInsetFraction(next) }
                    ) {
                        SplitMapPane(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onSingleTapFocus = swapToMap,
                            presentationMode = MapPanePresentationMode.Inset
                        )
                    }
                }

                if (
                    shouldShowStreamsPipInset(
                        pipEnabled = pipEnabled,
                        layoutMode = layoutMode
                    )
                ) {
                    val swapToStreams = {
                        val nextLayout = streamPipInsetTapLayoutMode(layoutMode)
                        viewModel.setLayoutMode(nextLayout)
                    }
                    StreamPipInsetFrame(
                        editorMode = streamPipUiState.editorMode,
                        insetFraction = streamPipUiState.insetFraction,
                        onTap = swapToStreams,
                        onLongPress = { viewModel.toggleStreamPipEditorModeFromLongPress() },
                        onResizeFractionChange = { next -> viewModel.setStreamPipInsetFraction(next) }
                    ) {
                        StreamsGrid(
                            viewModel = viewModel,
                            onPlayCapturedVideo = null,
                            onMapStatusTap = onMapStatusTap,
                            showTileControls = false,
                            remoteVideoDesignator = remoteVideoDesignator,
                            remoteRequesterEmail = remoteRequesterEmail,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (fullScreenChrome.showExitChip) {
                    val exitLayout = fullScreenExitChipLayout()
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = exitLayout.endPaddingDp.dp)
                            .zIndex(10f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FullScreenExitChip(onClick = { streamsFullScreen = false })
                        FullScreenPipChip(
                            enabled = streamPipUiState.enabled,
                            onClick = {
                                viewModel.setStreamPipEnabled(!streamPipUiState.enabled)
                            },
                        )
                        BridgeSignalIndicator(rssi = bridgeRssi, overlay = true)
                    }
                }

                if (allowModalDialogs) viewModel.pendingClue?.let {
                    ClueSubmissionSheet(
                        pendingClue = it,
                        coordinateDisplayFormat = viewModel.coordinateDisplayFormat,
                        onTitleChanged = viewModel::updateClueTitle,
                        onDescriptionChanged = viewModel::updateClueDescription,
                        onCameraHeadingChanged = viewModel::updateClueCameraHeading,
                        onGimbalAngleChanged = viewModel::updateClueGimbalAngle,
                        onSubmit = viewModel::submitClue,
                        onSubmitLocalMarkerOnly = viewModel::submitLocalMarkerOnly,
                        onCancel = viewModel::clearPendingClue,
                    )
                }
            }
        }

        ComplianceAlertDialog(
            visible = showCompliancePanel,
            overLimitDrones = overLimitDrones,
            onDismiss = { showCompliancePanel = false },
            onToggleMuted = { mappedId, muted ->
                viewModel.setComplianceAlertMuted(mappedId, muted)
            }
        )
        SignalLossAlertDialog(
            visible = showSignalLossPanel,
            flights = signalLossFlights,
            onDismiss = { showSignalLossPanel = false },
            onToggleMuted = { flightKey, muted ->
                DroneSignalLossAlertCenter.setMuted(flightKey, muted)
            }
        )
    }

    if (showNotamPanel) {
        NotamPanel(
            state = notamUiState,
            airspaceState = airspaceUiState,
            onDismiss = { showNotamPanel = false }
        )
    }
    if (showLandRestrictionPanel) {
        LandRestrictionPanel(
            state = landRestrictionUiState,
            onRefresh = LandRestrictionCenter::requestImmediateRefresh,
            onDismiss = { showLandRestrictionPanel = false }
        )
    }
    if (showPerformancePanel) {
        AlertDialog(
            onDismissRequest = { showPerformancePanel = false },
            title = { Text("Performance") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = viewModel.performancePanelText(focusedPath),
                        fontSize = 14.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPerformancePanel = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ApplyStreamsFullScreenSystemBars(active: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(active, view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (active && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun BridgeSignalIndicator(
    rssi: Int?,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
) {
    Surface(
        modifier = modifier.heightIn(min = 32.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (overlay) Color.Black.copy(alpha = 0.68f)
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (overlay) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "Bridge ${rssi ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            SignalStrengthBars(
                rssi = rssi ?: 0,
                modifier = Modifier.width(26.dp).height(22.dp),
                colorByStrength = true,
            )
        }
    }
}

@Composable
private fun FullScreenExitChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layout = fullScreenExitChipLayout()
    Box(
        modifier = modifier
            .widthIn(min = layout.minWidthDp.dp)
            .heightIn(min = layout.minHeightDp.dp)
            .border(1.dp, Color.White, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        OutlinedOverlayText(
            text = "Exit FS",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun FullScreenPipChip(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(min = 76.dp)
            .heightIn(min = 48.dp)
            .border(
                width = 1.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else Color.White,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedOverlayText(
            text = if (enabled) "PiP:On" else "PiP:Off",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun OutlinedOverlayText(
    text: String,
    style: TextStyle,
) {
    val outlinedStyle = style.copy(fontWeight = FontWeight.Black)
    Box {
        Text(
            text = text,
            color = Color.Black,
            style = outlinedStyle.copy(drawStyle = Stroke(width = 4f, miter = 2f)),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = text,
            color = Color.White,
            style = outlinedStyle,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

@Composable
fun ComplianceAlertBell(
    overLimitDrones: List<OverLimitDroneUiState>,
    allOverLimitMuted: Boolean,
    onClick: () -> Unit
) {
    if (overLimitDrones.isEmpty()) return
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (allOverLimitMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
            contentDescription = "Altitude alerts",
            tint = if (allOverLimitMuted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}

@Composable
fun ComplianceAlertDialog(
    visible: Boolean,
    overLimitDrones: List<OverLimitDroneUiState>,
    onDismiss: () -> Unit,
    onToggleMuted: (mappedId: String, muted: Boolean) -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Altitude Alerts") },
        text = {
            if (overLimitDrones.isEmpty()) {
                Text("No drones are currently above 200 ft AGL.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    overLimitDrones.forEach { drone ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onToggleMuted(drone.mappedId, !drone.muted)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(drone.mappedId)
                                Text(
                                    text = complianceAlertSummary(drone),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    onToggleMuted(drone.mappedId, !drone.muted)
                                }
                            ) {
                                Icon(
                                    imageVector = if (drone.muted) {
                                        Icons.Default.NotificationsOff
                                    } else {
                                        Icons.Default.Notifications
                                    },
                                    contentDescription = if (drone.muted) {
                                        "Enable alerts for ${drone.mappedId}"
                                    } else {
                                        "Mute alerts for ${drone.mappedId}"
                                    },
                                    tint = if (drone.muted) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun complianceAlertSummary(drone: OverLimitDroneUiState): String {
    return if (drone.usingDemAgl) {
        String.format(
            java.util.Locale.US,
            "%.0f' AGL%s",
            drone.aglFt,
            if (drone.staleDem) " (DEM stale)" else ""
        )
    } else {
        val atoText = drone.atoFt?.let {
            String.format(java.util.Locale.US, "%.0f' ATO", it)
        } ?: String.format(java.util.Locale.US, "%.0f' ATO", drone.aglFt)
        "$atoText (AGL not available)"
    }
}

internal const val MIN_SPLIT_FRACTION = 0f
internal const val MAX_SPLIT_FRACTION = 1f
internal const val SPLIT_EDGE_SNAP_HANDLE_WIDTHS = 2f
internal const val SPLIT_DIVIDER_TOUCH_SIZE_DP = 96
internal const val SPLIT_DIVIDER_SNAP_WIDTH_DP = 48
internal const val SPLIT_EDGE_RESTORE_TOUCH_HEIGHT_DP = 192
internal const val SPLIT_BOTTOM_GESTURE_CLEARANCE_DP = 12

internal fun splitDividerTouchHeightDp(fraction: Float): Int =
    if (fraction <= MIN_SPLIT_FRACTION || fraction >= MAX_SPLIT_FRACTION) {
        SPLIT_EDGE_RESTORE_TOUCH_HEIGHT_DP
    } else {
        SPLIT_DIVIDER_TOUCH_SIZE_DP
    }

internal fun portraitSplitDividerTouchOffsetPx(
    fraction: Float,
    availablePx: Float,
    dividerTouchSizePx: Float,
    mandatoryBottomGestureInsetPx: Float,
    bottomClearancePx: Float,
): Float {
    val normalOffset = splitDividerTouchOffsetPx(
        fraction = fraction,
        availablePx = availablePx,
        dividerThicknessPx = dividerTouchSizePx,
    )
    if (fraction < MAX_SPLIT_FRACTION) return normalOffset
    return (normalOffset - mandatoryBottomGestureInsetPx.coerceAtLeast(0f) -
        bottomClearancePx.coerceAtLeast(0f)).coerceAtLeast(0f)
}

internal fun layoutModeForSplitFraction(fraction: Float): StreamsLayoutMode =
    when {
        fraction <= MIN_SPLIT_FRACTION -> StreamsLayoutMode.Map
        fraction >= MAX_SPLIT_FRACTION -> StreamsLayoutMode.Streams
        else -> StreamsLayoutMode.Both
    }

internal fun splitDividerOffsetPx(
    fraction: Float,
    availablePx: Float,
    dividerThicknessPx: Float,
): Float = fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION) * availablePx -
    dividerThicknessPx / 2f

internal fun splitDividerTouchOffsetPx(
    fraction: Float,
    availablePx: Float,
    dividerThicknessPx: Float,
): Float = splitDividerOffsetPx(fraction, availablePx, dividerThicknessPx)
    .coerceIn(0f, (availablePx - dividerThicknessPx).coerceAtLeast(0f))

internal fun adjustedSplitFraction(
    currentFraction: Float,
    dragDeltaPx: Float,
    availablePx: Float,
): Float {
    val clampedCurrent = currentFraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
    if (availablePx <= 0f) return clampedCurrent
    return (clampedCurrent + dragDeltaPx / availablePx)
        .coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
}

internal fun snappedSplitFraction(
    fraction: Float,
    availablePx: Float,
    dividerThicknessPx: Float,
): Float {
    val clamped = fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
    if (availablePx <= 0f || dividerThicknessPx <= 0f) return clamped
    val positionPx = clamped * availablePx
    val snapDistancePx = SPLIT_EDGE_SNAP_HANDLE_WIDTHS * dividerThicknessPx
    return when {
        positionPx <= snapDistancePx && positionPx <= availablePx / 2f -> MIN_SPLIT_FRACTION
        availablePx - positionPx <= snapDistancePx && positionPx >= availablePx / 2f ->
            MAX_SPLIT_FRACTION
        else -> clamped
    }
}

@Composable
private fun SplitStreamsAndMap(
    viewModel: StreamsViewModel,
    onPlayCapturedVideo: (() -> Unit)?,
    splitFraction: Float,
    onSplitFractionChange: (Float) -> Unit,
    onMapStatusTap: () -> Unit,
    onStreamsPaneTap: () -> Unit,
    onMapPaneTap: () -> Unit,
    remoteVideoDesignator: String?,
    remoteRequesterEmail: String?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isPortrait = maxHeight > maxWidth
        val dividerTouchSize = SPLIT_DIVIDER_TOUCH_SIZE_DP.dp
        val dividerSnapWidth = SPLIT_DIVIDER_SNAP_WIDTH_DP.dp
        val dividerLineThickness = 4.dp
        val clamped = splitFraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
        val density = LocalDensity.current
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val dividerTouchSizePx = with(density) { dividerTouchSize.toPx() }
        val dividerSnapWidthPx = with(density) { dividerSnapWidth.toPx() }
        val dividerLinePx = with(density) { dividerLineThickness.toPx() }
        val currentSplitFraction by rememberUpdatedState(clamped)
        val currentOnSplitFractionChange by rememberUpdatedState(onSplitFractionChange)

        if (isPortrait) {
            val mandatoryBottomGestureInsetPx =
                WindowInsets.mandatorySystemGestures.getBottom(density).toFloat()
            val bottomGestureClearancePx = with(density) {
                SPLIT_BOTTOM_GESTURE_CLEARANCE_DP.dp.toPx()
            }
            val dividerOffsetDp = with(density) {
                portraitSplitDividerTouchOffsetPx(
                    fraction = clamped,
                    availablePx = maxHeightPx,
                    dividerTouchSizePx = dividerTouchSizePx,
                    mandatoryBottomGestureInsetPx = mandatoryBottomGestureInsetPx,
                    bottomClearancePx = bottomGestureClearancePx,
                ).toDp()
            }
            val dividerLineOffsetDp = with(density) {
                (clamped * maxHeightPx - dividerLinePx / 2f).toDp()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (clamped > MIN_SPLIT_FRACTION) Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(clamped.coerceIn(0f, 1f))
                        .align(Alignment.TopStart)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onStreamsPaneTap() })
                        }
                ) {
                    StreamsGrid(
                        viewModel = viewModel,
                        onPlayCapturedVideo = onPlayCapturedVideo,
                        onMapStatusTap = onMapStatusTap,
                        onStreamTileSingleTap = onStreamsPaneTap,
                        remoteVideoDesignator = remoteVideoDesignator,
                        remoteRequesterEmail = remoteRequesterEmail,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (clamped < MAX_SPLIT_FRACTION) Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((1f - clamped).coerceIn(0f, 1f))
                        .align(Alignment.BottomStart)
                ) {
                    SplitMapPane(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onSingleTapFocus = onMapPaneTap
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(y = dividerLineOffsetDp)
                        .fillMaxWidth()
                        .height(dividerLineThickness)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = dividerOffsetDp)
                        .size(dividerTouchSize)
                        .background(Color.Transparent)
                        .systemGestureExclusion()
                        .semantics {
                            contentDescription = "Resize video and map panes"
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = clamped,
                                range = MIN_SPLIT_FRACTION..MAX_SPLIT_FRACTION,
                            )
                        }
                        .pointerInput(maxHeightPx) {
                            var rawDragFraction = currentSplitFraction
                            detectDragGestures(
                                onDragStart = { rawDragFraction = currentSplitFraction },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    rawDragFraction = adjustedSplitFraction(
                                        currentFraction = rawDragFraction,
                                        dragDeltaPx = dragAmount.y,
                                        availablePx = maxHeightPx,
                                    )
                                    currentOnSplitFractionChange(
                                        snappedSplitFraction(
                                            fraction = rawDragFraction,
                                            availablePx = maxHeightPx,
                                            dividerThicknessPx = dividerSnapWidthPx,
                                        )
                                    )
                                },
                            )
                        }
                ) {
                    SplitDragHandle(
                        horizontalDivider = true,
                        modifier = when {
                            clamped <= MIN_SPLIT_FRACTION -> Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-14).dp)
                            clamped >= MAX_SPLIT_FRACTION -> Modifier
                                .align(Alignment.BottomCenter)
                                // Android's bottom Home gesture cannot be excluded. Keep the
                                // visible handle fully inside the inset-adjusted touch target.
                            else -> Modifier.align(Alignment.Center)
                        },
                    )
                }
            }
        } else {
            val dividerTouchHeight = splitDividerTouchHeightDp(clamped).dp
            val dividerOffsetDp = with(density) {
                splitDividerTouchOffsetPx(clamped, maxWidthPx, dividerTouchSizePx).toDp()
            }
            val dividerLineOffsetDp = with(density) {
                (clamped * maxWidthPx - dividerLinePx / 2f).toDp()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (clamped > MIN_SPLIT_FRACTION) Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(clamped.coerceIn(0f, 1f))
                        .align(Alignment.CenterStart)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onStreamsPaneTap() })
                        }
                ) {
                    StreamsGrid(
                        viewModel = viewModel,
                        onPlayCapturedVideo = onPlayCapturedVideo,
                        onMapStatusTap = onMapStatusTap,
                        onStreamTileSingleTap = onStreamsPaneTap,
                        remoteVideoDesignator = remoteVideoDesignator,
                        remoteRequesterEmail = remoteRequesterEmail,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (clamped < MAX_SPLIT_FRACTION) Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((1f - clamped).coerceIn(0f, 1f))
                        .align(Alignment.CenterEnd)
                ) {
                    SplitMapPane(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onSingleTapFocus = onMapPaneTap
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(x = dividerLineOffsetDp)
                        .fillMaxHeight()
                        .width(dividerLineThickness)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = dividerOffsetDp)
                        .width(dividerTouchSize)
                        .height(dividerTouchHeight)
                        .background(Color.Transparent)
                        .systemGestureExclusion()
                        .semantics {
                            contentDescription = "Resize video and map panes"
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = clamped,
                                range = MIN_SPLIT_FRACTION..MAX_SPLIT_FRACTION,
                            )
                        }
                        .pointerInput(maxWidthPx) {
                            var rawDragFraction = currentSplitFraction
                            detectDragGestures(
                                onDragStart = { rawDragFraction = currentSplitFraction },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    rawDragFraction = adjustedSplitFraction(
                                        currentFraction = rawDragFraction,
                                        dragDeltaPx = dragAmount.x,
                                        availablePx = maxWidthPx,
                                    )
                                    currentOnSplitFractionChange(
                                        snappedSplitFraction(
                                            fraction = rawDragFraction,
                                            availablePx = maxWidthPx,
                                            dividerThicknessPx = dividerSnapWidthPx,
                                        )
                                    )
                                },
                            )
                        }
                ) {
                    SplitDragHandle(
                        horizontalDivider = false,
                        modifier = when {
                            clamped <= MIN_SPLIT_FRACTION -> Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = (-14).dp)
                            clamped >= MAX_SPLIT_FRACTION -> Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 14.dp)
                            else -> Modifier.align(Alignment.Center)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitDragHandle(
    horizontalDivider: Boolean,
    modifier: Modifier = Modifier,
) {
    val handleColor = MaterialTheme.colorScheme.primary
    val gripColor = MaterialTheme.colorScheme.onPrimary
    Canvas(
        modifier = modifier
            .width(if (horizontalDivider) 48.dp else 28.dp)
            .height(if (horizontalDivider) 28.dp else 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(handleColor)
    ) {
        val spacing = 7.dp.toPx()
        val radius = 2.dp.toPx()
        for (index in -1..1) {
            drawCircle(
                color = gripColor,
                radius = radius,
                center = if (horizontalDivider) {
                    Offset(center.x + index * spacing, center.y)
                } else {
                    Offset(center.x, center.y + index * spacing)
                },
            )
        }
    }
}

@Composable
private fun StreamPipInsetFrame(
    editorMode: Boolean,
    insetFraction: Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onResizeFractionChange: (Float) -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val insetSize = streamPipInsetSize(
            maxWidth = maxWidth.value,
            maxHeight = maxHeight.value,
            insetFraction = insetFraction
        )
        val maxInsetWidth = (maxWidth - STREAM_PIP_FRAME_PADDING_DP.dp).coerceAtLeast(1.dp)
        val density = LocalDensity.current
        val latestInsetFraction by rememberUpdatedState(insetFraction)
        val latestOnResizeFractionChange by rememberUpdatedState(onResizeFractionChange)

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .width(insetSize.width.dp)
                .height(insetSize.height.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            content()
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(editorMode) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() }
                        )
                    }
            )
            if (editorMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(44.dp)
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .pointerInput(maxInsetWidth, density) {
                            var dragStartFraction = 0f
                            var accumulatedDragFraction = 0f
                            detectDragGestures(
                                onDragStart = {
                                    dragStartFraction = latestInsetFraction
                                    accumulatedDragFraction = 0f
                                },
                                onDrag = { _, dragAmount ->
                                    val deltaPx = -dragAmount.x - dragAmount.y
                                    val denominatorPx = with(density) { maxInsetWidth.toPx() }
                                    if (denominatorPx > 0f) {
                                        accumulatedDragFraction += deltaPx / denominatorPx
                                        latestOnResizeFractionChange(dragStartFraction + accumulatedDragFraction)
                                    }
                                }
                            )
                        }
                )
            }
        }
    }
}

@Composable
private fun LayoutToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StreamsMapStatusButton(
    mapName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        CaltopoActionInterface(
            state = streamsMapConnectionState(mapName),
            onActionClicked = onClick,
            modifier = Modifier.height(36.dp)
        )
    }
}

private fun streamsMapConnectionState(mapName: String?): CaltopoConnectionState {
    val connectedMapName = mapName?.takeIf { it.isNotBlank() }
    return if (connectedMapName == null) {
        CaltopoConnectionState.StandAlone
    } else {
        CaltopoConnectionState.MapSelected(
            CaltopoNode.MapNode(
                id = "",
                title = connectedMapName,
                updated = 0L
            )
        )
    }
}

fun <T> List<T>.padTo(size: Int): List<T?> =
    this + List(size - this.size) { null }

@Composable
private fun StreamsGrid(
    viewModel: StreamsViewModel,
    onPlayCapturedVideo: (() -> Unit)?,
    onMapStatusTap: () -> Unit,
    onStreamTileSingleTap: (() -> Unit)? = null,
    showTileControls: Boolean = true,
    fullScreenContent: Boolean = false,
    remoteVideoDesignator: String? = null,
    remoteRequesterEmail: String? = null,
    modifier: Modifier = Modifier
) {
    val tag = "StreamsGrid"
    val context = LocalContext.current
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val streamEntries = streams.entries
        .filter { (_, info) -> viewModel.isStreamVisible(info) }
        .toList()
    val mapName = viewModel.mapName
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val visibleEntries =
        if (focusedPath != null) {
            streamEntries.filter { it.key == focusedPath }
        } else {
            streamEntries
        }

    val singleVisibleDesignator = remember(visibleEntries, focusedPath) {
        if (focusedPath == null && visibleEntries.size == 1) visibleEntries[0].key else null
    }
    val pendingReviewExport = viewModel.pendingLocalPlaybackReviewExport()
    val reviewExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            viewModel.completeLocalPlaybackReviewExport(uri)
        }
    )

    LaunchedEffect(singleVisibleDesignator) {
        singleVisibleDesignator?.let { designator ->
            viewModel.ensureFocus(designator)
        }
    }
    Box(modifier = modifier) {
        if (visibleEntries.isEmpty()) {
            CTDebug(tag, "No streams to show.")
            EmptyStreamsView(
                viewModel = viewModel,
                mapName = mapName,
                onMapStatusTap = onMapStatusTap,
                onPlayCapturedVideo = onPlayCapturedVideo,
                onRestartServer = {
                    restartMediaMtxServer(context)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val columns = if (visibleEntries.size <= 2) 1 else 2
            val rows = when (visibleEntries.size) {
                0, 1 -> 1
                2 -> 2
                else -> 2
            }

            if (fullScreenContent && visibleEntries.size == 1) {
                val (path, info) = visibleEntries.first()
                val focusPresentation = streamTileFocusPresentation(
                    displayedTileCount = visibleEntries.size,
                    explicitlyFocused = focusedPath == path
                )
                val chromePresentation = streamTileChromePresentation(
                    fullScreenContent = true,
                    focused = focusPresentation.effectiveFocused
                )
                StreamTile(
                    viewModel = viewModel,
                    streamDesignator = path,
                    streamRevision = info.revision,
                    streamState = info.state,
                    streamErrorDetail = info.errorDetail,
                    onCloseStream = {
                        viewModel.closeStream(path)
                    },
                    onRestartServer = {
                        restartMediaMtxServer(context)
                    },
                    onToggleFocus = {
                        handleStreamTileSingleTap(
                            onStreamsPaneTap = onStreamTileSingleTap,
                            onToggleFocus = { viewModel.toggleFocus(path) },
                        )
                    },
                    showTileControls = showTileControls,
                    effectiveFocused = focusPresentation.effectiveFocused,
                    showFocusBorder = focusPresentation.showFocusBorder,
                    fillContainer = chromePresentation.fillContainer,
                    showStandaloneTelemetryOverlay = chromePresentation.showStandaloneTelemetryOverlay,
                    remoteRequesterEmail = remoteRequesterEmail.takeIf {
                        path.equals(remoteVideoDesignator, ignoreCase = true)
                    },
                )
                return@Box
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val cellHeight = maxHeight / rows

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false,
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(
                        items = visibleEntries,
                        key = { it.key }
                    ) { (path, info) ->
                        val focusPresentation = streamTileFocusPresentation(
                            displayedTileCount = visibleEntries.size,
                            explicitlyFocused = focusedPath == path
                        )
                        val chromePresentation = streamTileChromePresentation(
                            fullScreenContent = false,
                            focused = focusPresentation.effectiveFocused
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(cellHeight)
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            StreamTile(
                                viewModel = viewModel,
                                streamDesignator = path,
                                streamRevision = info.revision,
                                streamState = info.state,
                                streamErrorDetail = info.errorDetail,
                                onCloseStream = {
                                    viewModel.closeStream(path)
                                },
                                onRestartServer = {
                                    restartMediaMtxServer(context)
                                },
                                onToggleFocus = {
                                    handleStreamTileSingleTap(
                                        onStreamsPaneTap = onStreamTileSingleTap,
                                        onToggleFocus = { viewModel.toggleFocus(path) },
                                    )
                                },
                                showTileControls = showTileControls,
                                effectiveFocused = focusPresentation.effectiveFocused,
                                showFocusBorder = focusPresentation.showFocusBorder,
                                fillContainer = chromePresentation.fillContainer,
                                showStandaloneTelemetryOverlay = chromePresentation.showStandaloneTelemetryOverlay,
                                remoteRequesterEmail = remoteRequesterEmail.takeIf {
                                    path.equals(remoteVideoDesignator, ignoreCase = true)
                                },
                            )
                        }
                    }
                }
            }
        }

    }

    if (pendingReviewExport != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearPendingLocalPlaybackReviewExport() },
            title = { Text("Save Review Sidecar?") },
            text = {
                Text("Save the updated review annotations for ${pendingReviewExport.designator} as ${pendingReviewExport.suggestedFileName}?") 
            },
            confirmButton = {
                TextButton(
                    onClick = { reviewExportLauncher.launch(pendingReviewExport.suggestedFileName) }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearPendingLocalPlaybackReviewExport() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

internal fun handleStreamTileSingleTap(
    onStreamsPaneTap: (() -> Unit)?,
    onToggleFocus: () -> Unit,
) {
    if (onStreamsPaneTap != null) {
        onStreamsPaneTap()
    } else {
        onToggleFocus()
    }
}

@Composable
private fun EmptyStreamsView(
    viewModel: StreamsViewModel,
    mapName: String?,
    onMapStatusTap: () -> Unit,
    onPlayCapturedVideo: (() -> Unit)?,
    onRestartServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var anomalyMenuExpanded by remember { mutableStateOf(false) }
    var showAnomalySettingsDialog by remember { mutableStateOf(false) }
    var showAdHelpDialog by remember { mutableStateOf(false) }
    val settingsDesignator = EMPTY_STREAMS_SETTINGS_DESIGNATOR
    val anomalyConfig = viewModel.anomalyConfigFor(settingsDesignator)
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            IconButton(
                onClick = { anomalyMenuExpanded = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Streams settings"
                )
            }
            DropdownMenu(
                expanded = anomalyMenuExpanded,
                onDismissRequest = { anomalyMenuExpanded = false }
            ) {
                onPlayCapturedVideo?.let { playCapturedVideo ->
                    DropdownMenuItem(
                        text = { Text("Play Captured Video") },
                        onClick = {
                            anomalyMenuExpanded = false
                            playCapturedVideo()
                        }
                    )
                }
                AnomalySettingsMenuContent(
                    viewModel = viewModel,
                    streamDesignator = settingsDesignator,
                    anomalyMode = anomalyConfig.detectorMode(),
                    isLocalPlayback = false,
                    pauseLocalPlaybackOnOpen = false,
                    onShowSettings = { showAnomalySettingsDialog = true },
                    onShowHelp = { showAdHelpDialog = true },
                    onCloseStream = null,
                    onRestartServer = onRestartServer,
                    onDismissMenu = { anomalyMenuExpanded = false },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val endpoints = rememberControllerEndpoints()
            Text(controllerEndpointInstructions(endpoints))
            Spacer(modifier = Modifier.height(12.dp))
            StreamsMapStatusButton(
                mapName = mapName,
                onClick = onMapStatusTap,
                modifier = Modifier
                    .width(220.dp)
                    .height(48.dp)
            )
        }

        AnomalySettingsDialogs(
            viewModel = viewModel,
            streamDesignator = settingsDesignator,
            anomalyConfig = anomalyConfig,
            isLocalPlayback = false,
            showAnomalySettingsDialog = showAnomalySettingsDialog,
            onDismissAnomalySettingsDialog = { showAnomalySettingsDialog = false },
            showAdHelpDialog = showAdHelpDialog,
            onDismissAdHelpDialog = { showAdHelpDialog = false },
        )
    }
}
