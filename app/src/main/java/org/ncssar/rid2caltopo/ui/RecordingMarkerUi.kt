package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.ncssar.rid2caltopo.app.ArchiveWritability
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.NotCapturingStreams
import org.ncssar.rid2caltopo.video.RecordingConnectWarning
import org.ncssar.rid2caltopo.video.RecordingMarker
import org.ncssar.rid2caltopo.video.RecordingMarkerPolicy
import org.ncssar.rid2caltopo.video.RecordingMarkerState
import org.ncssar.rid2caltopo.video.RecordingStatus
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.ncssar.rid2caltopo.video.StreamState

internal val RecordingRed = Color(0xFFD32F2F)
internal val RecordingAmber = Color(0xFFFFA000)

/** Ticks once a second while a marker is still waiting for its recorder. */
@Composable
private fun rememberRecordingClock(waiting: Boolean): Long {
    var now by remember { mutableLongStateOf(RecordingStatus.clock()) }
    LaunchedEffect(waiting) {
        now = RecordingStatus.clock()
        while (waiting) {
            delay(1_000L)
            now = RecordingStatus.clock()
        }
    }
    return now
}

@Composable
private fun recordingMarkerFor(info: StreamInfo?): RecordingMarker {
    val status by RecordingStatus.state.collectAsStateWithLifecycle()
    val archive by ArchiveWritability.state.collectAsStateWithLifecycle()
    val live = info != null && info.state == StreamState.LIVE && !info.isLocalPlayback
    val waiting = live && info!!.sourcePath !in status.recordingPaths
    val now = rememberRecordingClock(waiting)
    if (info == null) return RecordingMarker(RecordingMarkerState.HIDDEN)
    return status.marker(info.sourcePath, info.state == StreamState.LIVE, info.isLocalPlayback, now, archive.writable)
}

private fun onMarkerTap(info: StreamInfo, marker: RecordingMarker) {
    if (RecordingMarkerPolicy.tapTurnsOnCapture(marker)) {
        CaltopoClient.CTInfo("RecordingMarker", "NOT RECORDING tapped for ${info.designator}: turning on Capture Streams")
        NotCapturingStreams.turnOn()
    } else if (marker.state == RecordingMarkerState.NOT_RECORDING) {
        marker.reason?.let { RecordingConnectWarning.showReason(RecordingConnectWarning.streamKey(info), info.designator, it) }
    }
}

/** The pill itself; also used by previews and screenshot tests. */
@Composable
fun RecordingMarkerPill(marker: RecordingMarker, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val label = marker.label ?: return
    val (background, foreground) = when (marker.state) {
        RecordingMarkerState.RECORDING -> Color.Black.copy(alpha = 0.72f) to RecordingRed
        RecordingMarkerState.STARTING -> Color.Black.copy(alpha = 0.6f) to Color.White.copy(alpha = 0.7f)
        else -> RecordingAmber to Color.Black
    }
    val description = when (marker.state) {
        RecordingMarkerState.RECORDING -> "Recording"
        RecordingMarkerState.STARTING -> "Recording is starting"
        else -> "Not recording. " + (marker.reason?.let(RecordingMarkerPolicy::reasonText) ?: "")
    }
    Text(
        text = label,
        modifier = modifier
            .background(background, RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = description },
        color = foreground,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** On every stream tile (full, split, grid, external display, compact PiP). */
@Composable
fun StreamRecordingMarker(streamDesignator: String, modifier: Modifier = Modifier) {
    val streams by StreamRegistry.streams.collectAsStateWithLifecycle()
    val info = streams[streamDesignator]
    val marker = recordingMarkerFor(info)
    if (info == null || marker.state == RecordingMarkerState.HIDDEN) return
    RecordingMarkerPill(marker, modifier, onClick = { onMarkerTap(info, marker) })
}

/** Map status row: one chip for all live streams (amber if any is not recorded). */
@Composable
fun RecordingStatusChip(modifier: Modifier = Modifier) {
    val streams by StreamRegistry.streams.collectAsStateWithLifecycle()
    val status by RecordingStatus.state.collectAsStateWithLifecycle()
    val archive by ArchiveWritability.state.collectAsStateWithLifecycle()
    val live = streams.values.filter { it.state == StreamState.LIVE && !it.isLocalPlayback }.sortedBy { it.designator.lowercase() }
    val now = rememberRecordingClock(live.any { it.sourcePath !in status.recordingPaths })
    if (live.isEmpty()) return
    val markers = live.map { it to status.marker(it.sourcePath, true, false, now, archive.writable) }
    val worst = markers.firstOrNull { it.second.state == RecordingMarkerState.NOT_RECORDING }
        ?: markers.firstOrNull { it.second.state == RecordingMarkerState.STARTING }
        ?: markers.first()
    val marker = worst.second
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RecordingMarkerPill(marker, onClick = { onMarkerTap(worst.first, marker) })
        // Name the stream when more than one is live and one of them is not recorded.
        if (marker.state == RecordingMarkerState.NOT_RECORDING && live.size > 1) {
            Text(" ${worst.first.designator}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** "Video is not being recorded": Turn on (default) / Not now, or the reason and OK. */
@Composable
fun RecordingPromptHost() {
    val prompts by RecordingConnectWarning.prompts.collectAsStateWithLifecycle()
    val prompt = prompts.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { RecordingConnectWarning.notNow(prompt) },
        title = { Text(prompt.title) },
        text = { Text(prompt.text) },
        confirmButton = {
            if (prompt.offersTurnOn) {
                TextButton(onClick = { RecordingConnectWarning.turnOn(prompt) }) { Text(RecordingMarkerPolicy.TURN_ON, fontWeight = FontWeight.Bold) }
            } else {
                TextButton(onClick = { RecordingConnectWarning.dismiss(prompt) }) { Text(RecordingMarkerPolicy.OK) }
            }
        },
        dismissButton = if (prompt.offersTurnOn) {
            { TextButton(onClick = { RecordingConnectWarning.notNow(prompt) }) { Text(RecordingMarkerPolicy.NOT_NOW) } }
        } else null,
    )
}
