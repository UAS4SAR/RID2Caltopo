package org.ncssar.rid2caltopo.video

import DroneSpecState
import DroneDisplayState
import StreamsViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal data class IndicatorPalette(
    val fillColor: Color,
    val outlineColor: Color
)

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignatorIndicator(
    streamDesignator: String,
    viewModel: StreamsViewModel,
    streamState: StreamState,
    streamErrorDetail: String?,
    onLongPress: () -> Unit,
    onTelemetryChipClick: () -> Unit = onLongPress,
    interactionEnabled: Boolean = true
) {
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val errorSummary = formatStreamErrorDetail(streamErrorDetail)
    val renderDelayMs = viewModel.renderDelayMsFor(streamDesignator)
    val playbackIndicatorState = viewModel.playbackIndicatorStateFor(streamDesignator)
    val droneDisplayState = viewModel.droneDisplayStateForStream(streamDesignator)
    val cameraAzimuthDeg = viewModel.cameraAzimuthForStream(streamDesignator)
    val isLocalPlayback = viewModel.isLocalPlayback(streamDesignator)
    val coordinateDisplayFormat = viewModel.coordinateDisplayFormat
    var coordinateMenuExpanded by remember(streamDesignator) { mutableStateOf(false) }
    val streamStateText = when (streamState) {
        StreamState.CONNECTING -> "Connecting..."
        StreamState.LIVE -> formatLiveState(renderDelayMs, playbackIndicatorState)
        StreamState.STOPPED -> "Stopped"
        StreamState.ERROR -> errorSummary?.let { "Error: $it" } ?: "Error"
    }
    val mapName = viewModel.mapName
    var mapStatus = "Standalone"
    if (null != mapName) {
        mapStatus = "Connected to $mapName"
    }
    if (isLocalPlayback) {
        val palette = IndicatorPalette(
            fillColor = Color.White,
            outlineColor = Color.Black
        )
        val helperText = if (!interactionEnabled) {
            "Captured video playback."
        } else if (focusedPath == streamDesignator) {
            "Captured video playback. Use the tile controls to review playback settings."
        } else {
            "Tap to focus. Use the tile controls to review playback settings."
        }
        Column {
            OutlinedIndicatorText(
                text = "$streamDesignator - Captured Video",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                palette = palette,
                modifier = Modifier
                    .padding(10.dp)
                    .background(Color.Transparent)
            )
            OutlinedIndicatorText(
                text = helperText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                palette = palette,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .background(Color.Transparent)
            )
        }
        return
    }
    val designatorState = viewModel.designatorStateFor(streamDesignator)
    val showTelemetryChip = interactionEnabled &&
        focusedPath == streamDesignator &&
        (designatorState is DesignatorState.Yellow || designatorState is DesignatorState.Green)
    val showCompactTopTelemetry = designatorState is DesignatorState.Green && streamState == StreamState.LIVE

    val (palette, locationText, detailText) = when (designatorState) {
        is DesignatorState.Green -> {
            val ds = designatorState.droneSpecState
            val location: String
            location = CoordinateFormatter.format(ds.lastLat, ds.lastLng, coordinateDisplayFormat)
            Triple(
                indicatorPaletteFor(designatorState),
                "$location (${coordinateDisplayFormat.label})",
                if (showCompactTopTelemetry) "" else formatCompactTelemetry(droneDisplayState, cameraAzimuthDeg)
            )
        }
        else -> Triple(
            indicatorPaletteFor(designatorState),
            null,
            if (showTelemetryChip) "" else designatorDetailText(designatorState, mapStatus, interactionEnabled)
        )
    }
    Column {
        if (showCompactTopTelemetry || showTelemetryChip) {
            Row(
                modifier = Modifier
                    .padding(10.dp)
                    .background(Color.Transparent),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedIndicatorText(
                    text = "${viewModel.streamTilePrimaryLabel(streamDesignator)} -",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    palette = palette
                )
                OutlinedIndicatorText(
                    text = streamStateText,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    palette = palette,
                    modifier = Modifier.requiredWidth(84.dp)
                )
                if (showTelemetryChip) {
                    TelemetryIndicatorChip(
                        text = telemetryChipTextFor(designatorState, droneDisplayState, cameraAzimuthDeg),
                        palette = palette,
                        onClick = onTelemetryChipClick
                    )
                } else {
                    OutlinedIndicatorText(
                        text = formatCompactTelemetry(droneDisplayState, cameraAzimuthDeg),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        palette = palette
                    )
                }
            }
        } else {
            OutlinedIndicatorText(
                text = "${viewModel.streamTilePrimaryLabel(streamDesignator)} - $streamStateText",
                style = MaterialTheme.typography.titleLarge,
                maxLines = if (streamState == StreamState.ERROR) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                palette = palette,
                modifier = Modifier
                    .padding(10.dp)
                    .background(Color.Transparent)
            )
        }
        if (locationText != null) {
            OutlinedIndicatorText(
                text = locationText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                palette = palette,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .background(Color.Transparent)
                    .then(
                        if (interactionEnabled) {
                            Modifier.clickable { coordinateMenuExpanded = true }
                        } else {
                            Modifier
                        }
                    )
            )
            if (interactionEnabled) {
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
            }
        }
        if (detailText.isNotBlank()) {
            OutlinedIndicatorText(
                text = detailText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (streamState == StreamState.ERROR) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                palette = palette,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .background(Color.Transparent)
            )
        }
        if (streamState == StreamState.ERROR && streamErrorDetail != null) {
            OutlinedIndicatorText(
                text = streamErrorDetail,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                palette = palette,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .background(Color.Transparent)
            )
        }
    }
}

@Composable
private fun TelemetryIndicatorChip(
    text: String,
    palette: IndicatorPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(1.dp, palette.fillColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .background(Color.Transparent)
    ) {
        OutlinedIndicatorText(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            palette = palette
        )
    }
}

@Composable
private fun OutlinedIndicatorText(
    text: String,
    palette: IndicatorPalette,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val outlinedStyle = style.copy(fontWeight = FontWeight.Black)
    Box(modifier = modifier) {
        Text(
            text = text,
            color = palette.outlineColor,
            style = outlinedStyle.copy(drawStyle = Stroke(width = 4f, miter = 2f)),
            maxLines = maxLines,
            overflow = overflow,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = text,
            color = palette.fillColor,
            style = outlinedStyle,
            maxLines = maxLines,
            overflow = overflow,
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

internal fun indicatorPaletteFor(designatorState: DesignatorState): IndicatorPalette = when (designatorState) {
    is DesignatorState.Green -> IndicatorPalette(
        fillColor = Color(0xFF00FF00),
        outlineColor = Color(0xFFFF4FD8)
    )
    is DesignatorState.Yellow -> IndicatorPalette(
        fillColor = Color(0xFFFFFF00),
        outlineColor = Color(0xFF1F4BFF)
    )
    DesignatorState.Red -> IndicatorPalette(
        fillColor = Color(0xFFFF0000),
        outlineColor = Color(0xFF00D4FF)
    )
}

internal fun designatorDetailText(
    designatorState: DesignatorState,
    mapStatus: String,
    interactionEnabled: Boolean
): String = when (designatorState) {
    is DesignatorState.Yellow -> "Telemetry not attached (mapStatus:${mapStatus})"
    DesignatorState.Red -> "No telemetry available (mapStatus:${mapStatus})"
    is DesignatorState.Green -> ""
}

internal fun telemetryChipTextFor(
    designatorState: DesignatorState,
    display: DroneDisplayState?,
    cameraAzimuthDeg: Double? = null,
): String = when (designatorState) {
    is DesignatorState.Green -> formatCompactTelemetry(display, cameraAzimuthDeg)
    is DesignatorState.Yellow -> "Pair Telemetry"
    DesignatorState.Red -> "No Telemetry"
}

internal fun formatLiveState(
    renderDelayMs: Long?,
    playbackIndicatorState: PlaybackIndicatorState? = null,
): String {
    if (playbackIndicatorState == PlaybackIndicatorState.BUFFERING) return "Buffering"
    if (playbackIndicatorState == PlaybackIndicatorState.LIVE_UNMEASURED) return "Live"
    val delayMs = renderDelayMs ?: return "Starting"
    if (delayMs >= 5_000L) return "Stalled"
    if (delayMs < 1_000L) return "lag:${delayMs}ms"
    return String.format(Locale.US, "lag:%.1fs", delayMs / 1000.0)
}

internal fun formatCompactTelemetry(
    display: DroneDisplayState?,
    cameraAzimuthDeg: Double? = null,
): String {
    // This is the header rendered over focused and split-screen live video. Reuse the map
    // formatter so every operator view has the same entries, order, units, and missing tokens.
    return streamTelemetryHeaderText(display, cameraAzimuthDeg)
}

private fun formatStreamErrorDetail(streamErrorDetail: String?): String? {
    if (streamErrorDetail.isNullOrBlank()) return null
    return when {
        streamErrorDetail.contains("extended chunk stream IDs", ignoreCase = true) ->
            "RTMP publisher uses unsupported extended chunk IDs"
        streamErrorDetail.contains("connection reset by peer", ignoreCase = true) ->
            "RTMP publisher reset connection"
        streamErrorDetail.contains("i/o timeout", ignoreCase = true) ->
            "RTMP publisher timed out"
        streamErrorDetail.contains("unexpected EOF", ignoreCase = true) ->
            "RTMP publisher disconnected"
        streamErrorDetail.contains("received type 1 chunk without previous chunk", ignoreCase = true) ->
            "RTMP chunk stream lost sync"
        else -> streamErrorDetail
    }
}

@Composable
fun DroneSpecPickerDialog(
    droneSpecStates: Map<String, DroneSpecState>,
    closestMatchRemoteId: String?,
    onSelect: (Map.Entry<String, DroneSpecState>) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Drone Telemetry") },
        text = {
            Column {
                val orderedStates = droneSpecStates.entries.sortedWith(
                    compareByDescending<Map.Entry<String, DroneSpecState>> {
                        it.value.remoteId == closestMatchRemoteId
                    }.thenBy {
                        it.value.mappedId.ifBlank { it.key }.lowercase(Locale.US)
                    }
                )
                orderedStates.forEach { droneSpecState ->
                    val isClosestMatch = droneSpecState.value.remoteId == closestMatchRemoteId
                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(droneSpecState) }
                        .then(
                            if (isClosestMatch) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(8.dp)
                    Row(
                        modifier = rowModifier
                    ) {
                        Column {
                            val (mappedId, droneSpecState) = droneSpecState
                            val displayMappedId = droneSpecState.mappedId.ifBlank { mappedId }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Mapped ID: $displayMappedId")
                                if (isClosestMatch) {
                                    Text(
                                        text = "Closest match",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text("Remote ID: ${droneSpecState.remoteId}")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
