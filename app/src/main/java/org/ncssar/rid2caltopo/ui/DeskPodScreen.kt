package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.PairStep
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText
import org.ncssar.rid2caltopo.btrx.BtrxImuCal
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText.StepStatus
import org.ncssar.rid2caltopo.btrx.BtrxManager

/**
 * Main menu > BTRX: the btrx Desk Pod link (Bluetooth, or USB for debugging),
 * its status and link health, and the "Pair drone pod" flow. Same sections, order
 * and wording as the iPad's DeskPodView. "Dismiss" only hides this panel; the link
 * keeps running (a calibration in progress too). "Cancel pairing" ends a pairing in
 * progress; "Cancel calibration" ends the IMU calibration flow. One Desk Pod and one
 * Drone Pod per app: "Replace Desk Pod" / "Replace Drone Pod" ask first, then forget or
 * unpair the current one; there is no "Pair another".
 */
@Composable
fun DeskPodScreen(onDismiss: () -> Unit) {
    val s by BtrxManager.snapshot.collectAsState()
    val bluetoothOn by BtrxManager.bluetoothEnabled.collectAsState()
    val usbDebugOn by BtrxManager.usbDebugEnabled.collectAsState()
    val overlayShown by BtrxManager.overlayShown.collectAsState()
    val config = LocalConfiguration.current
    var replacing by remember { mutableStateOf<Replacing?>(null) }
    replacing?.let { r ->
        AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text(if (r == Replacing.DRONE_POD) BtrxDeskPodText.REPLACE_POD_TITLE else BtrxDeskPodText.REPLACE_DESK_TITLE) },
            text = { Text(if (r == Replacing.DRONE_POD) BtrxDeskPodText.replacePodMessage(s) else BtrxDeskPodText.replaceDeskMessage(s)) },
            confirmButton = {
                Button(onClick = {
                    replacing = null
                    if (r == Replacing.DRONE_POD) BtrxManager.replacePairedPod() else BtrxManager.forgetDeskPod()
                }) { Text(if (r == Replacing.DRONE_POD) BtrxDeskPodText.REPLACE_POD_OK else BtrxDeskPodText.REPLACE_DESK_OK) }
            },
            dismissButton = { TextButton(onClick = { replacing = null }) { Text(BtrxDeskPodText.REPLACE_KEEP) } },
        )
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = minOf(720, config.screenWidthDp - 24).dp).heightIn(max = (config.screenHeightDp - 48).dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(BtrxDeskPodText.TITLE, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LearnMoreLink()
                    SectionTitle("Connection")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BtrxDeskPodText.BLUETOOTH_TOGGLE, Modifier.weight(1f))
                        Switch(checked = bluetoothOn, onCheckedChange = { BtrxManager.setBluetoothEnabled(it) })
                    }
                    if (s.bluetoothPausedForUsb) {
                        Text(BtrxDeskPodText.USB_PAUSED, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                    }
                    Text(BtrxDeskPodText.linkLine(s), fontWeight = FontWeight.SemiBold)
                    if (s.link.detail.isNotEmpty()) Text(s.link.detail, style = MaterialTheme.typography.bodySmall)
                    BtrxDeskPodText.deviceLine(s)?.let { Text(it) }
                    Text(BtrxDeskPodText.PIN_HINT, style = MaterialTheme.typography.bodySmall)
                    if (bluetoothOn) {
                        OutlinedButton(onClick = { replacing = Replacing.DESK_POD }) { Text(BtrxDeskPodText.REPLACE_DESK) }
                    }
                    // Android only: the iPad has no USB link.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BtrxDeskPodText.USB_DEBUG_TOGGLE, Modifier.weight(1f))
                        Switch(checked = usbDebugOn, onCheckedChange = { BtrxManager.setUsbDebugEnabled(it) })
                    }
                    Text(BtrxDeskPodText.USB_DEBUG_HINT, style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle("Desk Pod")
                    Text(BtrxDeskPodText.roleLine(s))
                    Text(BtrxDeskPodText.firmwareLine(s))

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle("Link health")
                    BtrxDeskPodText.healthLines(s).forEach { Text(it) }
                    Text(BtrxDeskPodText.liveFramesLine(s), style = MaterialTheme.typography.bodySmall)
                    Text(BtrxDeskPodText.countsLine("Backlog frames (old, display only)", s.backlogCounts), style = MaterialTheme.typography.bodySmall)
                    BtrxBearingStatusLines()

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle("Pair drone pod")
                    BtrxDeskPodText.pairedLine(s)?.let { line ->
                        Text(line)
                        BtrxDeskPodText.pairedPodWarnings(s).forEach {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (s.windowOpen && s.pairing.step == PairStep.IDLE) {
                        Text("The Desk Pod pairing window is open (${s.windowSecondsLeft} s left).", style = MaterialTheme.typography.bodySmall)
                    }
                    PairingSection(s, onReplace = { replacing = Replacing.DRONE_POD })

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle(BtrxDeskPodText.IMU_CAL_TITLE)
                    ImuCalSection(s)

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle("Steering")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BtrxDeskPodText.OVERLAY_TOGGLE, Modifier.weight(1f))
                        Switch(checked = overlayShown, onCheckedChange = { BtrxManager.setOverlayShown(it) })
                    }
                    Text(BtrxDeskPodText.OVERLAY_TOGGLE_HINT, style = MaterialTheme.typography.bodySmall)
                    val st = s.lastSteer
                    if (st == null) {
                        Text(BtrxDeskPodText.STEER_NONE, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SteeringMark(st, 36.dp)
                            Text(BtrxDeskPodText.steerLine(st), Modifier.padding(start = 8.dp))
                        }
                        Text(st.clue, fontWeight = FontWeight.SemiBold)
                        if (st.muted) Text(BtrxDeskPodText.STEER_MUTED, fontWeight = FontWeight.SemiBold)
                    }
                    Text(BtrxDeskPodText.STEER_MUTE_HINT, style = MaterialTheme.typography.bodySmall)
                    if (s.pairedPod != null) {
                        OutlinedButton(onClick = { BtrxManager.clearSteeringMutes() }) { Text(BtrxDeskPodText.CLEAR_MUTES) }
                    }
                    val steeringChosen by BtrxManager.steeringStream.collectAsState()
                    Text(BtrxDeskPodText.steerTileLine(steeringChosen, BtrxManager.liveSteeringStreams().size), style = MaterialTheme.typography.bodySmall)
                    if (steeringChosen != null) {
                        OutlinedButton(onClick = { BtrxManager.chooseSteeringStream(null) }) { Text(BtrxDeskPodText.STEER_CHOOSE_AGAIN) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BtrxDeskPodText.STEER_ROLL, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = s.steeringRollCorrection, onCheckedChange = { BtrxManager.setSteeringRollCorrection(it) })
                    }

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle(org.ncssar.rid2caltopo.btrx.BtrxBearingText.SECTION)
                    BtrxBearingSection()
                }
            }
        }
    }
}

/**
 * Drone Pod IMU calibration (design doc section 29.3): Calibrate (level step at once),
 * then Start starboard step (countdown, then starboard down), results in plain words,
 * Retry starboard step after a failed one, Check and Clear saved calibration. Same as the
 * iPad's imuCalSection.
 */
@Composable
private fun ImuCalSection(s: org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.Snapshot) {
    val v = s.imuCal ?: return
    Text(BtrxImuCal.calibratedLine(v), fontWeight = if (v.calibrated == true) FontWeight.SemiBold else FontWeight.Normal)
    Text(BtrxDeskPodText.IMU_CAL_HINT, style = MaterialTheme.typography.bodySmall)
    if (v.message.isNotEmpty()) {
        Text(
            v.message,
            color = if (v.step == BtrxImuCal.Step.FAILED) MaterialTheme.colorScheme.error else Color.Unspecified,
            fontWeight = if (v.step == BtrxImuCal.Step.CALIBRATED) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
    if (v.step == BtrxImuCal.Step.STBD_COUNTDOWN) {
        Text(BtrxDeskPodText.imuCalCountdown(v.countdownS), style = MaterialTheme.typography.headlineSmall)
    }
    val enabled = s.pairedPod != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (v.canStartStarboard) {
            Button(onClick = { BtrxManager.startImuCalStarboard() }, enabled = enabled) {
                Text(if (v.step == BtrxImuCal.Step.FAILED) BtrxDeskPodText.IMU_CAL_STBD_RETRY else BtrxDeskPodText.IMU_CAL_STBD)
            }
        }
        if (v.canStart) {
            val first = v.step == BtrxImuCal.Step.IDLE
            if (first && !v.canStartStarboard) {
                Button(onClick = { BtrxManager.startImuCal() }, enabled = enabled) { Text(BtrxDeskPodText.IMU_CAL_START) }
            } else {
                OutlinedButton(onClick = { BtrxManager.startImuCal() }, enabled = enabled) {
                    Text(if (first) BtrxDeskPodText.IMU_CAL_START else BtrxDeskPodText.IMU_CAL_START_AGAIN)
                }
            }
        }
        if (v.canCancel) OutlinedButton(onClick = { BtrxManager.cancelImuCal() }) { Text(BtrxDeskPodText.IMU_CAL_CANCEL) }
        if (v.canClose) TextButton(onClick = { BtrxManager.closeImuCalResult() }) { Text(BtrxDeskPodText.IMU_CAL_CLOSE) }
    }
    if (v.canCheckOrClear) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { BtrxManager.checkImuCal() }, enabled = enabled) { Text(BtrxDeskPodText.IMU_CAL_CHECK) }
            OutlinedButton(onClick = { BtrxManager.clearImuCal() }, enabled = enabled) { Text(BtrxDeskPodText.IMU_CAL_CLEAR) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

private enum class Replacing { DESK_POD, DRONE_POD }

/** "Pair drone pod" with none paired; "Replace Drone Pod" (asks first) once one is paired. */
@Composable
private fun PairOrReplaceButton(s: org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.Snapshot, onReplace: () -> Unit, retry: Boolean = false) {
    if (s.pairedPod != null) Button(onClick = onReplace) { Text(BtrxDeskPodText.REPLACE_POD) }
    else Button(onClick = { BtrxManager.startPairing() }) { Text(if (retry) "Try again" else BtrxDeskPodText.PAIR_POD) }
}

@Composable
private fun PairingSection(s: org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.Snapshot, onReplace: () -> Unit) {
    val p = s.pairing
    val active = p.step in org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.ACTIVE_STEPS
    if (p.step == PairStep.IDLE) {
        PairOrReplaceButton(s, onReplace)
        return
    }
    BtrxDeskPodText.steps(p).forEachIndexed { i, step ->
        val (mark, color) = when (step.status) {
            StepStatus.DONE -> "✓" to Color(0xFF2E7D32)
            StepStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
            StepStatus.ACTIVE -> "▶" to MaterialTheme.colorScheme.primary
            StepStatus.PENDING -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(mark, color = color, modifier = Modifier.width(24.dp), fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text("${i + 1}. ${step.title}", fontWeight = if (step.status == StepStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal)
                if (step.detail.isNotEmpty()) Text(step.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    p.candidate?.warnings?.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (p.message.isNotEmpty()) {
        val color = when (p.step) {
            PairStep.FAILED -> MaterialTheme.colorScheme.error
            PairStep.PAIRED -> Color(0xFF2E7D32)
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(p.message, color = color, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.step == PairStep.POD_FOUND && !p.podStale) Button(onClick = { BtrxManager.confirmPod() }) { Text("Confirm pod") }
        if (active) OutlinedButton(onClick = { BtrxManager.cancelPairing() }) { Text("Cancel pairing") }
        if (!active) {
            PairOrReplaceButton(s, onReplace, retry = p.step == PairStep.FAILED)
            TextButton(onClick = { BtrxManager.clearPairingResult() }) { Text("Clear result") }
        }
    }
    Spacer(Modifier.fillMaxWidth().height(8.dp))
}

/** Top link of the BTRX pane (Developer Tools > BTRX): the BTRX page in the system browser (matches the iPad's Link). */
@Composable
private fun LearnMoreLink() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(BtrxDeskPodText.LEARN_MORE_URL),
            ).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { android.util.Log.w("DeskPodScreen", "No browser for ${BtrxDeskPodText.LEARN_MORE_URL}: $it") }
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
    ) { Text(BtrxDeskPodText.LEARN_MORE) }
}
