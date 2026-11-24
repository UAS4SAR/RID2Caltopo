package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.ncssar.rid2caltopo.btrx.BtrxPanelOrder
import org.ncssar.rid2caltopo.btrx.BtrxStaleness
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import org.ncssar.rid2caltopo.btrx.BtrxPopupPlacement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.ncssar.rid2caltopo.btrx.BtrxBearingSettings
import org.ncssar.rid2caltopo.btrx.BtrxBearingSource
import org.ncssar.rid2caltopo.btrx.BtrxBearingText
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText
import org.ncssar.rid2caltopo.btrx.BtrxIgnoreAllExcept
import org.ncssar.rid2caltopo.btrx.BtrxIgnoreAllPlan
import org.ncssar.rid2caltopo.btrx.BtrxOwnSuggestion
import org.ncssar.rid2caltopo.btrx.BtrxManager

/**
 * Developer Tools > BTRX > "Bearing map layer" (docs/btrx/BEARING-LAYER-spec.md sections
 * 2.4, 3.4, 5.2, 7): where telemetry is shown (no switch, Nov 23), fade time and shape sliders with the live
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
    // Ken, Nov 23: no on/off; the layer runs while BTRX is enabled and the map shows it while primary.
    Text(BtrxBearingText.LAYER_TITLE, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Text(BtrxBearingText.LAYER_HINT, style = MaterialTheme.typography.bodySmall)
    BtrxManager.bearing.lastError?.takeIf { !on }?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { BtrxManager.retryBearingLayer() }) { Text(BtrxBearingText.LAYER_RETRY) }
    }

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
    BtrxBearingDisplaySettings()
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

/**
 * Nov 20: hide estimates wider than ±N m (2 sigma; the top of the slider = no limit), hide
 * labels of sources with fewer reports, and mute sources at the takeoff spot. Same as the iPad.
 */
@Composable
private fun BtrxBearingDisplaySettings() {
    val V = org.ncssar.rid2caltopo.btrx.BtrxBearingView
    val D = org.ncssar.rid2caltopo.btrx.BtrxBearingDisplay
    val d by BtrxManager.bearingDisplay.collectAsState()
    val noLimit = V.sliderFromMaxPm(0)
    var pm by remember(d.maxPlusMinusM) { mutableFloatStateOf(V.sliderFromMaxPm(d.maxPlusMinusM)) }
    var minRpt by remember(d.minReports) { mutableFloatStateOf(d.minReports.toFloat()) }
    fun pmValue(v: Float): Int = V.maxPmFromSlider(v)
    Text("${V.SETTING_MAX_PM}: ${V.maxPlusMinusText(pmValue(pm))}")
    Slider(
        value = pm, onValueChange = { pm = it }, valueRange = D.MIN_PLUS_MINUS_M.toFloat()..noLimit, steps = ((noLimit - D.MIN_PLUS_MINUS_M) / 10f).roundToInt() - 1,
        onValueChangeFinished = { BtrxManager.setBearingDisplay(d.copy(maxPlusMinusM = pmValue(pm))) },
    )
    Text("${V.SETTING_MIN_REPORTS}: ${V.minReportsText(minRpt.roundToInt())}")
    Slider(
        value = minRpt, onValueChange = { minRpt = it }, valueRange = 1f..D.MAX_MIN_REPORTS.toFloat(), steps = D.MAX_MIN_REPORTS - 2,
        onValueChangeFinished = { BtrxManager.setBearingDisplay(d.copy(minReports = minRpt.roundToInt())) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(V.SETTING_LAUNCH_MUTE, Modifier.weight(1f))
        Switch(checked = d.launchAutoMute, onCheckedChange = { BtrxManager.setBearingDisplay(d.copy(launchAutoMute = it)) })
    }
    Text(V.SETTING_LAUNCH_MUTE_HINT, style = MaterialTheme.typography.bodySmall)
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

/**
 * Places one map popup (the tap panel at [tapAt], or the inspect card [bottomStart] insets from
 * the bottom-left) with [BtrxPopupPlacement]: inside this host (fill the map), clear of
 * [obstacleInRoot] (the stream PiP, root coordinates), flipped left or up, height capped so the
 * popup scrolls (Ken, Nov 20). The host itself takes no touches.
 */
@Composable
fun BtrxPopupHost(
    obstacleInRoot: androidx.compose.ui.geometry.Rect?,
    modifier: Modifier = Modifier,
    tapAt: androidx.compose.ui.unit.IntOffset? = null,
    bottomStart: Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>? = null,
    /** Top of the map attribution strip in root coordinates: popups stay above it (Ken, Nov 23). */
    keepAboveInRoot: Float? = null,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.layout.Layout(
        content,
        modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() },
    ) { measurables, c ->
        val w = c.maxWidth
        val fullH = c.maxHeight
        val child = measurables.firstOrNull()
        if (child == null || !c.hasBoundedWidth || !c.hasBoundedHeight) {
            val p = child?.measure(c.copy(minWidth = 0, minHeight = 0))
            return@Layout layout(c.minWidth, c.minHeight) { p?.place(tapAt ?: androidx.compose.ui.unit.IntOffset.Zero) }
        }
        val reserved = keepAboveInRoot?.let { origin.y + fullH - it } ?: 0f
        val h = BtrxPopupPlacement.usableHeight(fullH.toFloat(), reserved).roundToInt()
        val m = 8.dp.toPx()
        val obs = obstacleInRoot?.let { BtrxPopupPlacement.Box(it.left - origin.x, it.top - origin.y, it.right - origin.x, it.bottom - origin.y) }
        fun plan(pw: Float, ph: Float): BtrxPopupPlacement.Plan =
            if (bottomStart != null) BtrxPopupPlacement.atBottomStart(w.toFloat(), h.toFloat(), pw, ph, bottomStart.first.toPx(), BtrxPopupPlacement.bottomAboveReserved(bottomStart.second.toPx(), reserved), obs, m)
            else BtrxPopupPlacement.atPoint(w.toFloat(), h.toFloat(), pw, ph, (tapAt?.x ?: 0).toFloat(), (tapAt?.y ?: 0).toFloat(), obs, m)
        val maxW = (w - 2 * m).toInt().coerceAtLeast(1)
        // Natural size first (intrinsics), so a popup squeezed above the PiP is measured at the capped height.
        val natural = runCatching {
            val nw = child.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity).coerceAtMost(maxW)
            nw to child.maxIntrinsicHeight(nw)
        }.getOrNull()
        val cap = natural?.let { plan(it.first.toFloat(), it.second.toFloat()).maxHeight } ?: (h - 2 * m)
        val p = child.measure(androidx.compose.ui.unit.Constraints(maxWidth = maxW, maxHeight = cap.toInt().coerceIn(1, h)))
        val fin = plan(p.width.toFloat(), p.height.toFloat())
        layout(w, fullH) { p.place(fin.x.roundToInt(), fin.y.roundToInt()) }
    }
}

/** Map status (field test, Nov 13): why the bearing layer draws nothing. Not tappable. */
@Composable
fun BtrxBearingStatusChip(line: String, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 560.dp), shape = RoundedCornerShape(8.dp), color = Color(0xE6202020), contentColor = Color.White) {
        Text(line, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** One panel row: swatch, checkbox (checked = shown), short ID, "new", class, last heard, reports in the fade window, RSSI. */
@Composable
fun BtrxBearingSourceRow(src: BtrxBearingSource, nowUs: Long, onShownChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(16.dp).background(Color(0xFF000000.toInt() or src.rgb), RoundedCornerShape(3.dp))
                .border(1.dp, Color.Black.copy(alpha = 0.4f), RoundedCornerShape(3.dp)),
        )
        Checkbox(checked = !src.muted, onCheckedChange = onShownChange)
        Text(src.shortId, fontWeight = FontWeight.SemiBold, maxLines = 1)
        // Nov 23: a source first heard in the last 10 s is tagged "new".
        if (BtrxStaleness.isNew(src, nowUs)) {
            Text(
                BtrxStaleness.NEW_TAG, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        // Two lines (Nov 20 A5 test: in a narrow panel "136 rpt" wrapped one letter per line).
        Column {
            Text(src.className, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                BtrxBearingText.lastHeard(nowUs, src.lastHeardUs) + " · " + BtrxBearingText.reports(src.reportsInWindow) +
                    (src.lastRssiDbm?.let { " · $it dBm" } ?: ""),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (src.ownSuspect) Text(BtrxBearingText.OWN_SUSPECT_TAG, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
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
    val here = rows.mapNotNull { byKey[it] }
    // Nov 23 (Ken): strongest and freshest first, stale ones sink and leave; re-sorted at most
    // every 12 s and never under a finger, so a row does not move as it is tapped.
    val order = remember { BtrxPanelOrder() }
    var holding by remember { mutableStateOf(false) }
    var showOlder by rememberSaveable { mutableStateOf(false) }
    val listed = order.arrange(here, s.tUs, holding, showOlder)
    val older = order.olderCount(here, s.tUs)
    Surface(modifier.widthIn(max = 420.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(12.dp)) {
        // Title and × stay put while the rows scroll (Ken, Nov 23: Done was below the fold).
        BtrxPopupHeader(onDismiss = onDismiss) {
            Text(BtrxBearingText.PANEL_TITLE, style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.weight(1f, fill = false)
                .pointerInput(Unit) {
                    // Watch only (nothing consumed): a finger on the list holds the order.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        holding = true
                        do { val e = awaitPointerEvent(PointerEventPass.Initial) } while (e.changes.any { it.pressed })
                        holding = false
                    }
                }
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (listed.isEmpty()) Text(BtrxBearingText.PANEL_ALL_STALE, style = MaterialTheme.typography.bodySmall)
            listed.forEach { src ->
                val presence = BtrxStaleness.presence(src, s.tUs)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(0.4f + 0.6f * presence)) {
                    Box(Modifier.weight(1f)) {
                        BtrxBearingSourceRow(src, s.tUs, onShownChange = { shown -> BtrxManager.bearing.setMuted(listOf(src.key), !shown) })
                    }
                    TextButton(onClick = { onFocus(src) }) { Text(BtrxBearingText.FOCUS, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (older > 0 || showOlder) {
                TextButton(onClick = { showOlder = !showOlder }) {
                    Text(if (showOlder) BtrxBearingText.HIDE_OLDER else BtrxBearingText.showOlder(older), style = MaterialTheme.typography.bodySmall)
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
}

/**
 * Popup header: the title, then × at the top right (Ken, Nov 23 watch test). × dismisses the
 * popup only: focus, mutes and ignores stay as they are. Same as the iPad.
 */
@Composable
private fun BtrxPopupHeader(onDismiss: () -> Unit, title: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { title() }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.semantics { contentDescription = BtrxBearingText.DISMISS },
        ) { Text("×", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * "Save as own aircraft": ignored on this device at once, and on the pod by src_id too when
 * the firmware keeps a saved list (the pod then stops reporting it). Shared by the suggestion
 * banner and the Developer Tools rows. Nov 17: the pod save alone left the layer's source
 * not ignored, so the suggestion card stayed up.
 */
fun btrxSaveOwnAircraft(src: BtrxBearingSource) {
    BtrxManager.bearing.setIgnored(src.key, true)
    val pod = BtrxManager.snapshot.value
    if (pod.savedIgnoreSupported && src.srcId != 0) BtrxManager.ignoreSourceSaved(src.srcId)
}

/** The own-aircraft suggestion answered: recorded (never shown again for this source), then saved when it is ours. */
fun btrxAnswerOwnAircraft(src: BtrxBearingSource, own: Boolean) {
    BtrxManager.bearing.answerOwnSuggestion(src, own)
    if (own) btrxSaveOwnAircraft(src)
}

/**
 * Own-aircraft suggestion (Nov 16 watch test): a source stronger than about -40 dBm for a
 * minute while the drone moved. A suggestion only: nothing is muted until Ken answers, and
 * the layer draws no estimate for it meanwhile. A one-line banner ("QBXFvg (unknown) on the
 * drone?  Own aircraft  Not mine  ×"); × collapses it to a chip, tap the chip to expand.
 * Closes at once on either answer and never comes back for that source. Same as the iPad.
 */
@Composable
fun BtrxOwnAircraftSuggestion(modifier: Modifier = Modifier) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val answers by BtrxManager.bearing.ownAnswers.collectAsState()
    val src = snap?.sources?.let { BtrxOwnSuggestion.pick(it, answers) } ?: return
    var collapsed by rememberSaveable(src.key) { mutableStateOf(false) }
    val bg = Color(0xE6202020)
    if (collapsed) {
        Surface(modifier.clickable { collapsed = false }, shape = RoundedCornerShape(16.dp), color = bg, contentColor = Color.White) {
            Text(BtrxBearingText.ownSuggestionCollapsed(src), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Surface(modifier.semantics { contentDescription = BtrxBearingText.ownSuggestion(src) + " " + BtrxBearingText.OWN_SUGGEST_WHY },
        shape = RoundedCornerShape(20.dp), color = bg, contentColor = Color.White) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(BtrxBearingText.ownSuggestion(src), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            TextButton(onClick = { btrxAnswerOwnAircraft(src, own = true) }) {
                Text(BtrxBearingText.OWN_SUGGEST_SAVE, color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { btrxAnswerOwnAircraft(src, own = false) }) {
                Text(BtrxBearingText.OWN_SUGGEST_KEEP, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { collapsed = true }, modifier = Modifier.semantics { contentDescription = BtrxBearingText.OWN_SUGGEST_COLLAPSE }) {
                Text("×", color = Color.White)
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
        Column(Modifier.padding(12.dp)) {
        BtrxPopupHeader(onDismiss = onDismiss) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(16.dp).background(Color(0xFF000000.toInt() or src.rgb), RoundedCornerShape(3.dp)))
                Text(BtrxBearingText.PINNED_TITLE, style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BtrxBearingText.inspectLines(src, s.tUs).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            BtrxRestoreIgnoredButton()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFocus(src) }) { Text(BtrxBearingText.FOCUS) }
                BtrxIgnoreAllExceptButton(src)
                TextButton(onClick = onDismiss) { Text(BtrxBearingText.CLOSE) }
            }
        }
        }
    }
}

/**
 * The focus chip at the top of the map. While a source is focused: its short ID, "Ignore all
 * except this" and "Clear focus"; while a class/maker focus is on: what it shows. Always (with
 * sources on the map) "Focus…": the kinds and makers on the map to focus on, and "Hide the
 * others" (Nov 20). Also "n muted at takeoff · Show" for sources the layer muted at the launch
 * point, and "Restore ignored (n)" while "Ignore all except this" has ignores to undo.
 */
@Composable
fun BtrxFocusChip(onClear: () -> Unit, modifier: Modifier = Modifier) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val s = snap ?: return
    val src = if (s.focusKey == 0L) null else s.sources.firstOrNull { it.key == s.focusKey }
    val byAll by BtrxManager.bearing.ignoredByAll.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val filterText = org.ncssar.rid2caltopo.btrx.BtrxBearingView.filterText(s.focusFilter)
    val launch = s.sources.filter { it.launch && !it.ignored }
    val launchMuted = launch.count { it.muted }
    val launchText = org.ncssar.rid2caltopo.btrx.BtrxBearingView.launchChipText(launchMuted, launch.size)
    if (s.sources.isEmpty() && byAll.isEmpty()) return
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color(0xE6202020), contentColor = Color.White) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (src != null) {
                Box(Modifier.size(12.dp).background(Color(0xFF000000.toInt() or src.rgb), RoundedCornerShape(2.dp)))
                Text(" ${src.shortId}", style = MaterialTheme.typography.bodySmall)
                BtrxIgnoreAllExceptButton(src, color = Color.White)
            }
            filterText?.let { Text((if (src != null) " · " else "") + it, style = MaterialTheme.typography.bodySmall) }
            if (s.focusActive) {
                TextButton(onClick = onClear) { Text(BtrxBearingText.CLEAR_FOCUS, color = Color.White, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { menu = true }) {
                Text(org.ncssar.rid2caltopo.btrx.BtrxBearingView.FOCUS_MENU, color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall)
            }
            launchText?.let { t ->
                Text(t, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    if (launchMuted > 0) BtrxManager.bearing.unmuteLaunch()
                    else BtrxManager.bearing.setMuted(launch.map { it.key }, true)
                }) {
                    Text(
                        if (launchMuted > 0) org.ncssar.rid2caltopo.btrx.BtrxBearingView.LAUNCH_SHOW else org.ncssar.rid2caltopo.btrx.BtrxBearingView.LAUNCH_HIDE,
                        color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            BtrxRestoreIgnoredButton(color = Color(0xFF8AB4F8))
        }
    }
    if (menu) BtrxFocusMenu(s, onClear = { menu = false; onClear() }, onDismiss = { menu = false })
}

/** "Focus on": kinds and makers on the map with counts (tap toggles), "Hide the others", Clear, Done. */
@Composable
private fun BtrxFocusMenu(s: org.ncssar.rid2caltopo.btrx.BtrxBearingSnapshot, onClear: () -> Unit, onDismiss: () -> Unit) {
    val V = org.ncssar.rid2caltopo.btrx.BtrxBearingView
    val f = s.focusFilter
    val options = V.focusOptions(s.sources)
    fun apply(n: org.ncssar.rid2caltopo.btrx.BtrxBearingFocusFilter) = BtrxManager.bearing.setFocusFilter(n)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(V.FOCUS_MENU_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text(V.FOCUS_NONE_ON_MAP, style = MaterialTheme.typography.bodySmall)
                val kinds = options.filter { it.isClass }
                val makers = options.filter { !it.isClass }
                if (kinds.isNotEmpty()) Text(V.FOCUS_CLASSES, fontWeight = FontWeight.SemiBold)
                kinds.forEach { o ->
                    val on = f.classMask and (1 shl o.value) != 0
                    Row(Modifier.clickable { apply(f.withClass(o.value)) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { apply(f.withClass(o.value)) })
                        Text("${o.label} (${o.count})")
                    }
                }
                if (makers.isNotEmpty()) Text(V.FOCUS_MAKERS, fontWeight = FontWeight.SemiBold)
                makers.forEach { o ->
                    val on = f.companyId == o.value
                    Row(Modifier.clickable { apply(f.withCompany(o.value)) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { apply(f.withCompany(o.value)) })
                        Text("${o.label} (${o.count})")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(V.FOCUS_HIDE_OTHERS, Modifier.weight(1f))
                    Switch(checked = f.hideOthers, onCheckedChange = { apply(f.copy(hideOthers = it)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(BtrxBearingText.DONE) } },
        dismissButton = { TextButton(onClick = onClear) { Text(BtrxBearingText.CLEAR_FOCUS) } },
    )
}

/**
 * "Ignore all except this" (Nov 17): asks first with the count ("Ignore 27 other sources?"),
 * then ignores every other tracked source here and saves the strongest on the Drone Pod's list
 * (op 3, within its 32 entries) on the worker. Disabled when there is nothing else to ignore.
 */
@Composable
fun BtrxIgnoreAllExceptButton(src: BtrxBearingSource, color: Color = Color.Unspecified) {
    val snap by BtrxManager.bearing.snapshot.collectAsState()
    val pod by BtrxManager.snapshot.collectAsState()
    var plan by remember { mutableStateOf<BtrxIgnoreAllPlan?>(null) }
    val s = snap ?: return
    val preview = BtrxIgnoreAllExcept.plan(s.sources, s.tUs, src, pod.savedIgnores, pod.podListStored, pod.savedIgnoreSupported)
    TextButton(onClick = { plan = preview }, enabled = preview.count > 0) {
        Text(BtrxBearingText.IGNORE_ALL_EXCEPT, color = if (preview.count > 0) color else Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
    plan?.let { p ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text(BtrxBearingText.ignoreAllTitle(p.count)) },
            text = { Text(BtrxBearingText.ignoreAllDetail(p, pod.savedIgnoreSupported)) },
            confirmButton = { TextButton(onClick = { plan = null; BtrxManager.ignoreAllExcept(p) }) { Text(BtrxBearingText.IGNORE_ALL_CONFIRM) } },
            dismissButton = { TextButton(onClick = { plan = null }) { Text(BtrxBearingText.CANCEL) } },
        )
    }
}

/** "Restore ignored (n)": undoes only what "Ignore all except this" ignored, here and on the pod. Hidden when there is nothing to undo. */
@Composable
fun BtrxRestoreIgnoredButton(color: Color = Color.Unspecified) {
    val byAll by BtrxManager.bearing.ignoredByAll.collectAsState()
    val pod by BtrxManager.snapshot.collectAsState()
    val onPod = pod.ignoreAll?.let { it.saved.size + it.waiting } ?: 0
    if (byAll.isEmpty() && onPod == 0) return
    TextButton(onClick = { BtrxManager.restoreIgnoreAll() }) {
        Text(BtrxBearingText.restoreLabel(maxOf(byAll.size, onPod)), color = color, style = MaterialTheme.typography.bodySmall)
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
