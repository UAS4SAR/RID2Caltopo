package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.ncssar.rid2caltopo.btrx.BtrxBearingSettings
import org.ncssar.rid2caltopo.btrx.BtrxBearingSource
import org.ncssar.rid2caltopo.btrx.BtrxBearingText
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText
import org.ncssar.rid2caltopo.btrx.BtrxManager

/**
 * Developer Tools > BTRX > "Bearing map layer" (docs/btrx/BEARING-LAYER-spec.md sections
 * 2.4, 3.4, 5.2, 7): the layer switch, fade time and shape sliders with the live
 * "half strength at m:ss" readout, lost-count and lag lines, and every source's row.
 * Same controls, labels and order as the iPad's DeskPodView bearing section.
 */
@Composable
fun BtrxBearingSection() {
    val on by BtrxManager.bearingOn.collectAsState()
    val settings by BtrxManager.bearingSettings.collectAsState()
    val trace by BtrxManager.bearingLagTrace.collectAsState()
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val status by BtrxManager.bearing.status.collectAsState()
    val pod by BtrxManager.snapshot.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(BtrxBearingText.LAYER_SWITCH, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { BtrxManager.setBearingOn(it) })
    }
    Text(BtrxBearingText.LAYER_SWITCH_HINT, style = MaterialTheme.typography.bodySmall)
    BtrxManager.bearing.lastError?.takeIf { !on }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    // Sliders keep a local value while dragging; the layer gets the value on release.
    var minutes by remember(settings.fadeTotalS) { mutableFloatStateOf(settings.fadeMinutes.toFloat()) }
    var shape by remember(settings.fadeShapeP) { mutableFloatStateOf(settings.fadeShapeP) }
    val draft = BtrxBearingSettings(minutes.roundToInt() * 60f, shape)
    Text("${BtrxBearingText.FADE_TIME}: ${BtrxBearingText.minutes(minutes.roundToInt())}")
    Slider(
        value = minutes, onValueChange = { minutes = it }, valueRange = 1f..30f, steps = 28,
        onValueChangeFinished = { BtrxManager.setBearingSettings(draft) },
    )
    Text("${BtrxBearingText.FADE_SHAPE}: ${BtrxBearingSettings.shapeLabel(shape)} (${BtrxBearingText.halfStrength(BtrxManager.bearing.halfStrengthS(draft))})")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Linear", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = shape, onValueChange = { shape = it }, valueRange = 1f..4f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            onValueChangeFinished = { BtrxManager.setBearingSettings(draft) },
        )
        Text("Accelerating", style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(BtrxBearingText.LAG_TRACE, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = trace, onCheckedChange = { BtrxManager.setBearingLagTrace(it) })
    }
    if (on) status?.line?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val s = snap
    // Counters also before the first snapshot (no drone pose yet), from the status copy.
    if (on && s == null) status?.stats?.let { st ->
        Text(BtrxBearingText.countsLine(st), style = MaterialTheme.typography.bodySmall)
        Text(BtrxBearingText.clockLine(st), style = MaterialTheme.typography.bodySmall)
    }
    if (on && s != null) {
        Text(BtrxBearingText.countsLine(s.stats), style = MaterialTheme.typography.bodySmall)
        Text(BtrxBearingText.clockLine(s.stats), style = MaterialTheme.typography.bodySmall)
        Text(BtrxBearingText.SOURCES, fontWeight = FontWeight.SemiBold)
        if (s.sources.isEmpty()) Text(BtrxBearingText.NO_SOURCES, style = MaterialTheme.typography.bodySmall)
        s.sources.forEach { src ->
            BtrxBearingSourceRow(src, s.tUs, onShownChange = { shown -> BtrxManager.bearing.setMuted(listOf(src.key), !shown) })
            if (pod.savedIgnoreSupported && src.srcId != 0) {
                // Drone Pod 0x0106+: saved on the pod by src_id (STEER_MUTE op 3 / 4).
                if (src.srcId in pod.savedIgnores) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${BtrxDeskPodText.OWN_SAVED}: ${BtrxDeskPodText.OWN_SAVED_DONE}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { BtrxManager.unignoreSourceSaved(src.srcId) }, enabled = !pod.filterPendingFor(src.srcId)) {
                        Text(BtrxDeskPodText.OWN_SAVED_REMOVE, style = MaterialTheme.typography.bodySmall)
                    }
                } else TextButton(onClick = { BtrxManager.ignoreSourceSaved(src.srcId) }, enabled = !pod.filterPendingFor(src.srcId)) {
                    Text(BtrxDeskPodText.OWN_SAVED, style = MaterialTheme.typography.bodySmall)
                }
            } else TextButton(onClick = { BtrxManager.bearing.setIgnored(src.key, !src.ignored) }) {
                // Older Drone Pod firmware: this device only.
                Text(if (src.ignored) BtrxBearingText.UNIGNORE else BtrxBearingText.IGNORE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (pod.savedIgnoreSupported || pod.savedIgnores.isNotEmpty()) BtrxSavedOwnAircraft(pod)
}

/** The paired Drone Pod's saved own aircraft (build 0x0106), each with Remove, and the latest result. */
@Composable
private fun BtrxSavedOwnAircraft(pod: org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.Snapshot) {
    Text(BtrxDeskPodText.ownSavedTitle(pod.savedIgnores.size), fontWeight = FontWeight.SemiBold)
    Text(BtrxDeskPodText.OWN_SAVED_HINT, style = MaterialTheme.typography.bodySmall)
    pod.savedIgnores.forEach { id ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(BtrxDeskPodText.srcIdText(id), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { BtrxManager.unignoreSourceSaved(id) }, enabled = !pod.filterPendingFor(id) && pod.savedIgnoreSupported) {
                Text(BtrxDeskPodText.OWN_SAVED_REMOVE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    pod.filterMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Status lines for "Link health" (spec 3.4): reports lost while no app, and the lag stats. */
@Composable
fun BtrxBearingStatusLines() {
    val on by BtrxManager.bearingOn.collectAsState()
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val status by BtrxManager.bearing.status.collectAsState()
    val st = snap?.stats ?: status?.stats
    if (on && st != null) {
        Text(BtrxBearingText.lostLine(st))
        Text(BtrxBearingText.lagLine(st), style = MaterialTheme.typography.bodySmall)
    }
}

/** Map status (field test, Nov 13): why the bearing layer draws nothing. Not tappable. */
@Composable
fun BtrxBearingStatusChip(line: String, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 560.dp), shape = RoundedCornerShape(8.dp), color = Color(0xE6202020), contentColor = Color.White) {
        Text(line, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** One panel row: swatch, checkbox (checked = shown), short ID, class, last heard, reports in the fade window. */
@Composable
fun BtrxBearingSourceRow(src: BtrxBearingSource, nowUs: Long, onShownChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(16.dp).background(Color(0xFF000000.toInt() or src.rgb), RoundedCornerShape(3.dp))
                .border(1.dp, Color.Black.copy(alpha = 0.4f), RoundedCornerShape(3.dp)),
        )
        Checkbox(checked = !src.muted, onCheckedChange = onShownChange)
        Text(src.shortId, fontWeight = FontWeight.SemiBold)
        Text(src.className)
        Text(BtrxBearingText.lastHeard(nowUs, src.lastHeardUs), style = MaterialTheme.typography.bodySmall)
        Text(BtrxBearingText.reports(src.reportsInWindow), style = MaterialTheme.typography.bodySmall)
        if (src.ownSuspect) Text(BtrxBearingText.OWN_SUSPECT_TAG, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The tap panel (spec 5.2): "BTRX sources here", one row per source in sources_at order,
 * "Mute all" / "Unmute all" for the rows listed, "Done". Rows only show or hide shading.
 * [rows] are keys in tap order; the row content follows the live snapshot.
 */
@Composable
fun BtrxBearingPanel(
    rows: List<Long>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (BtrxBearingSource) -> Unit = {},
) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val s = snap ?: return
    val byKey = s.sources.associateBy { it.key }
    val listed = rows.mapNotNull { byKey[it] }
    Surface(modifier.widthIn(max = 420.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(BtrxBearingText.PANEL_TITLE, style = MaterialTheme.typography.titleMedium)
            listed.forEach { src ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        BtrxBearingSourceRow(src, s.tUs, onShownChange = { shown -> BtrxManager.bearing.setMuted(listOf(src.key), !shown) })
                    }
                    TextButton(onClick = { onFocus(src) }) { Text(BtrxBearingText.FOCUS, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { BtrxManager.bearing.setMuted(listed.map { it.key }, true) }) { Text(BtrxBearingText.MUTE_ALL) }
                OutlinedButton(onClick = { BtrxManager.bearing.setMuted(listed.map { it.key }, false) }) { Text(BtrxBearingText.UNMUTE_ALL) }
                TextButton(onClick = onDismiss) { Text(BtrxBearingText.DONE) }
            }
        }
    }
}

/**
 * "Save as own aircraft": on the pod by src_id when the firmware keeps a saved list,
 * otherwise this device only. Shared by the suggestion card and the Developer Tools rows.
 */
fun btrxSaveOwnAircraft(src: BtrxBearingSource) {
    val pod = BtrxManager.snapshot.value
    if (pod.savedIgnoreSupported && src.srcId != 0) BtrxManager.ignoreSourceSaved(src.srcId)
    else BtrxManager.bearing.setIgnored(src.key, true)
}

/**
 * Own-aircraft suggestion (Nov 16 watch test): a source stronger than about -40 dBm for a
 * minute while the drone moved. A suggestion only: nothing is muted until Ken answers, and
 * the layer draws no estimate for it meanwhile. Hidden once saved, ignored or cleared.
 */
@Composable
fun BtrxOwnAircraftSuggestion(modifier: Modifier = Modifier) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val src = snap?.sources?.firstOrNull { it.ownSuspect && !it.ignored } ?: return
    Surface(modifier.widthIn(max = 520.dp), shape = RoundedCornerShape(8.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(BtrxBearingText.ownSuggestion(src), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { btrxSaveOwnAircraft(src) }) { Text(BtrxBearingText.OWN_SUGGEST_SAVE) }
                TextButton(onClick = { BtrxManager.bearing.clearOwnSuspect(src.key) }) { Text(BtrxBearingText.OWN_SUGGEST_KEEP) }
            }
        }
    }
}

/** Inspect card of a pinned (triangulated) source: ID/type, estimate and radius, counts, last heard, peak RSSI. */
@Composable
fun BtrxPinnedInspectCard(
    key: Long,
    onFocus: (BtrxBearingSource) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val s = snap ?: return
    val src = s.sources.firstOrNull { it.key == key } ?: return
    Surface(modifier.widthIn(max = 420.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(16.dp).background(Color(0xFF000000.toInt() or src.rgb), RoundedCornerShape(3.dp)))
                Text(BtrxBearingText.PINNED_TITLE, style = MaterialTheme.typography.titleMedium)
            }
            BtrxBearingText.inspectLines(src, s.tUs).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFocus(src) }) { Text(BtrxBearingText.FOCUS) }
                TextButton(onClick = onDismiss) { Text(BtrxBearingText.CLOSE) }
            }
        }
    }
}

/** Shown while a source is focused: its short ID and "Clear focus". */
@Composable
fun BtrxFocusChip(onClear: () -> Unit, modifier: Modifier = Modifier) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val s = snap ?: return
    if (s.focusKey == 0L) return
    val src = s.sources.firstOrNull { it.key == s.focusKey } ?: return
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color(0xE6202020), contentColor = Color.White) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(Color(0xFF000000.toInt() or src.rgb), RoundedCornerShape(2.dp)))
            Text(" ${src.shortId}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear) { Text(BtrxBearingText.CLEAR_FOCUS, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * The BTRX status chip in the status row at the top of the map (Ken, Nov 16): "BTRX: Active"
 * (green) or "BTRX: Inactive - <reason>" (orange). Hidden until BTRX is set up. Tap: the
 * Desk Pod screen. Same as the iPad.
 */
@Composable
fun BtrxMapStatusChip(modifier: Modifier = Modifier) {
    val status by BtrxManager.mapStatus.collectAsState()
    val s = status ?: return
    androidx.compose.material3.AssistChip(
        onClick = { BtrxManager.openDeskPodScreenFromStatus() },
        label = {
            Text(
                s.text,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
        },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            // Green: link READY and the Drone Pod reporting. Orange: READY but no Drone Pod
            // reports (warning), or Inactive (unchanged).
            containerColor = if (s.active && !s.warning) Color(0xFF2E7D32) else Color(0xFFF57C00),
            labelColor = Color.White,
        ),
        modifier = modifier,
    )
}
