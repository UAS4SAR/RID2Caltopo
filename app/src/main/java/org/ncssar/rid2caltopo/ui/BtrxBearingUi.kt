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
    val s = snap
    if (on && s != null) {
        Text(BtrxBearingText.countsLine(s.stats), style = MaterialTheme.typography.bodySmall)
        Text(BtrxBearingText.clockLine(s.stats), style = MaterialTheme.typography.bodySmall)
        Text(BtrxBearingText.SOURCES, fontWeight = FontWeight.SemiBold)
        if (s.sources.isEmpty()) Text(BtrxBearingText.NO_SOURCES, style = MaterialTheme.typography.bodySmall)
        s.sources.forEach { src ->
            BtrxBearingSourceRow(src, s.tUs, onShownChange = { shown -> BtrxManager.bearing.setMuted(listOf(src.key), !shown) })
            TextButton(onClick = { BtrxManager.bearing.setIgnored(src.key, !src.ignored) }) {
                Text(if (src.ignored) BtrxBearingText.UNIGNORE else BtrxBearingText.IGNORE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Status lines for "Link health" (spec 3.4): reports lost while no app, and the lag stats. */
@Composable
fun BtrxBearingStatusLines() {
    val on by BtrxManager.bearingOn.collectAsState()
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val s = snap
    if (on && s != null) {
        Text(BtrxBearingText.lostLine(s.stats))
        Text(BtrxBearingText.lagLine(s.stats), style = MaterialTheme.typography.bodySmall)
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
    }
}

/**
 * The tap panel (spec 5.2): "BTRX sources here", one row per source in sources_at order,
 * "Mute all" / "Unmute all" for the rows listed, "Done". Rows only show or hide shading.
 * [rows] are keys in tap order; the row content follows the live snapshot.
 */
@Composable
fun BtrxBearingPanel(rows: List<Long>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val s = snap ?: return
    val byKey = s.sources.associateBy { it.key }
    val listed = rows.mapNotNull { byKey[it] }
    Surface(modifier.widthIn(max = 420.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(BtrxBearingText.PANEL_TITLE, style = MaterialTheme.typography.titleMedium)
            listed.forEach { src ->
                BtrxBearingSourceRow(src, s.tUs, onShownChange = { shown -> BtrxManager.bearing.setMuted(listOf(src.key), !shown) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { BtrxManager.bearing.setMuted(listed.map { it.key }, true) }) { Text(BtrxBearingText.MUTE_ALL) }
                OutlinedButton(onClick = { BtrxManager.bearing.setMuted(listed.map { it.key }, false) }) { Text(BtrxBearingText.UNMUTE_ALL) }
                TextButton(onClick = onDismiss) { Text(BtrxBearingText.DONE) }
            }
        }
    }
}
