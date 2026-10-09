package org.ncssar.rid2caltopo.video

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

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
import org.ncssar.rid2caltopo.ui.AlertDialog
import org.ncssar.rid2caltopo.ui.DropdownMenu
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
    interactionEnabled: Boolean = true,
    onCalibrationRequested: () -> Unit = onTelemetryChipClick,
    zoomText: String? = null,
) {
    val state = viewModel.designatorStateFor(streamDesignator)
    val displayState = viewModel.droneDisplayStateForStream(streamDesignator)
    val cameraAzimuth = viewModel.cameraAzimuthForStream(streamDesignator)
    val format = viewModel.coordinateDisplayFormat
    var coordinateMenuExpanded by remember(streamDesignator) { mutableStateOf(false) }
    val localPlayback = viewModel.isLocalPlayback(streamDesignator)
    val status = when (streamState) {
        StreamState.CONNECTING -> "Connecting..."
        StreamState.LIVE -> formatLiveState(viewModel.renderDelayMsFor(streamDesignator), viewModel.playbackIndicatorStateFor(streamDesignator))
        StreamState.STOPPED -> "Stopped"
        StreamState.ERROR -> formatStreamErrorDetail(streamErrorDetail)?.let { "Error: $it" } ?: "Error"
    }
    val foreground = Color.White
    val background = Color.Black
    Row(
        modifier = Modifier.fillMaxWidth().background(background)
            .horizontalScroll(rememberScrollState())
            .pointerInput(streamDesignator, interactionEnabled) {
                detectTapGestures(onLongPress = { if (interactionEnabled) onLongPress() })
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("${viewModel.streamTilePrimaryLabel(streamDesignator)} — $status", color = indicatorPaletteFor(state).fillColor, style = style, maxLines = 1)
        if (localPlayback) {
            Text("Captured Video", color = foreground, style = style, maxLines = 1)
        } else {
            val telemetry = stableVideoTelemetryText(telemetryChipTextFor(state, displayState, cameraAzimuth))
            Text(telemetry, color = foreground, style = style, maxLines = 1,
                modifier = Modifier.clickable(enabled = interactionEnabled) {
                    if (telemetry.contains("CAL")) onCalibrationRequested() else onTelemetryChipClick()
                })
            zoomText?.let { Text(it, color = foreground, style = style, maxLines = 1) }
            if (state is DesignatorState.Green) {
                val drone = state.droneSpecState
                Box {
                    Text("${CoordinateFormatter.format(drone.lastLat, drone.lastLng, format)} (${format.label})",
                        color = foreground, style = style, maxLines = 1,
                        modifier = Modifier.clickable(enabled = interactionEnabled) { coordinateMenuExpanded = true })
                    DropdownMenu(expanded = coordinateMenuExpanded, onDismissRequest = { coordinateMenuExpanded = false }) {
                        CoordinateDisplayFormat.values().forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                coordinateMenuExpanded = false
                                viewModel.setCoordinateDisplayFormat(option)
                            })
                        }
                    }
                }
            }
        }
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
    is DesignatorState.Yellow -> if (designatorState.embeddedTelemetry) "Embedded telemetry available; aircraft not paired" else "Telemetry not attached (mapStatus:${mapStatus})"
    DesignatorState.Red -> "No telemetry available (mapStatus:${mapStatus})"
    is DesignatorState.Green -> ""
}

internal fun telemetryChipTextFor(
    designatorState: DesignatorState,
    display: DroneDisplayState?,
    cameraAzimuthDeg: Double? = null,
): String = when (designatorState) {
    is DesignatorState.Green -> formatCompactTelemetry(display, cameraAzimuthDeg)
    is DesignatorState.Yellow -> if (designatorState.embeddedTelemetry) "Embedded Telemetry" else "Pair Telemetry"
    DesignatorState.Red -> "No Telemetry"
}

internal fun formatLiveState(
    renderDelayMs: Long?,
    playbackIndicatorState: PlaybackIndicatorState? = null,
): String {
    if (playbackIndicatorState == PlaybackIndicatorState.BUFFERING) return "Buffering"
    if (playbackIndicatorState == PlaybackIndicatorState.LIVE_UNMEASURED) return "Streaming"
    val delayMs = renderDelayMs ?: return "Starting"
    if (delayMs >= 5_000L) return "Stalled"
    return "Streaming"
}

internal fun formatCompactTelemetry(
    display: DroneDisplayState?,
    cameraAzimuthDeg: Double? = null,
): String {
    // This is the header rendered above focused and split-screen live video. Reuse the map
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
    onDismiss: () -> Unit,
    configuration: @Composable () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Drone Telemetry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Select the drone supplying this stream’s telemetry.")
                if (droneSpecStates.isEmpty()) Text("Waiting for drone telemetry from the bridge.")
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
                configuration()
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

/** Fixed monospaced value slots, including a dedicated uncertainty-marker column. */
internal fun stableVideoTelemetryText(text: String): String = text.split(" ").sortedBy { if (it.startsWith("RNG:")) 1 else 0 }.joinToString(" ") { token ->
    val separator = token.indexOf(':')
    if (separator < 0) token else {
        val value = token.substring(separator + 1)
        val uncertain = value.endsWith("?")
        val width = if (token.startsWith("CAM:") || token.startsWith("TRK:") || token.startsWith("HDG:")) 4 else 5
        token.substring(0, separator + 1) + value.removeSuffix("?").padStart(width) +
            if (uncertain) "?" else " "
    }
}
