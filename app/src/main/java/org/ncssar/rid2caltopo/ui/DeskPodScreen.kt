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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.PairStep
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText.StepStatus
import org.ncssar.rid2caltopo.btrx.BtrxManager

/**
 * Main menu > Desk Pod: the btrx Desk Pod link (Bluetooth, or USB for debugging),
 * its status and link health, and the "Pair drone pod" flow. Same sections, order
 * and wording as the iPad's DeskPodView. "Dismiss" only hides this panel; the link
 * keeps running. "Cancel pairing" ends a pairing in progress.
 */
@Composable
fun DeskPodScreen(onDismiss: () -> Unit) {
    val s by BtrxManager.snapshot.collectAsState()
    val bluetoothOn by BtrxManager.bluetoothEnabled.collectAsState()
    val usbDebugOn by BtrxManager.usbDebugEnabled.collectAsState()
    val config = LocalConfiguration.current
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
                        OutlinedButton(onClick = { BtrxManager.forgetDeskPod() }) { Text("Forget this Desk Pod") }
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
                    Text(BtrxDeskPodText.countsLine("Live frames", s.liveCounts), style = MaterialTheme.typography.bodySmall)
                    Text(BtrxDeskPodText.countsLine("Backlog frames (old, display only)", s.backlogCounts), style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle("Pair drone pod")
                    BtrxDeskPodText.pairedLine(s)?.let { line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(line, Modifier.weight(1f))
                            TextButton(onClick = { BtrxManager.forgetPairedPod() }) { Text("Forget") }
                        }
                        BtrxDeskPodText.pairedPodWarnings(s).forEach {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (s.windowOpen && s.pairing.step == PairStep.IDLE) {
                        Text("The Desk Pod pairing window is open (${s.windowSecondsLeft} s left).", style = MaterialTheme.typography.bodySmall)
                    }
                    PairingSection(s)

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle("Steering")
                    val st = s.lastSteer
                    if (st == null) {
                        Text(BtrxDeskPodText.STEER_NONE, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SteeringMark(st, 36.dp)
                            Text(BtrxDeskPodText.steerLine(st), Modifier.padding(start = 8.dp))
                        }
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
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun PairingSection(s: org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.Snapshot) {
    val p = s.pairing
    val active = p.step in org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.ACTIVE_STEPS
    if (p.step == PairStep.IDLE) {
        Button(onClick = { BtrxManager.startPairing() }) { Text("Pair drone pod") }
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
            Button(onClick = { BtrxManager.startPairing() }) { Text(if (p.step == PairStep.PAIRED) "Pair another" else "Try again") }
            TextButton(onClick = { BtrxManager.clearPairingResult() }) { Text("Clear result") }
        }
    }
    Spacer(Modifier.fillMaxWidth().height(8.dp))
}
