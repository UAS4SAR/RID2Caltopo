package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ncssar.rid2caltopo.app.ArchiveWritability
import org.ncssar.rid2caltopo.storage.ArchiveWritabilityText
import org.ncssar.rid2caltopo.storage.ArchiveWritabilityVerdict
import org.ncssar.rid2caltopo.storage.ArchiveWriteProblem
import org.ncssar.rid2caltopo.storage.ArchiveStateProblem
import org.ncssar.rid2caltopo.storage.MissingPreviousStorage
import org.ncssar.rid2caltopo.storage.MissingPreviousStorageNotice
import org.ncssar.rid2caltopo.storage.MissingPreviousStorageText
import org.ncssar.rid2caltopo.video.NotCapturingStream
import org.ncssar.rid2caltopo.video.NotCapturingStreams
import org.ncssar.rid2caltopo.video.StreamRegistry

/** One alert per archive state change: read-only/failing card, with the two ways forward. */
@Composable
fun ArchiveStorageAlertDialog(
    verdict: ArchiveWritabilityVerdict,
    fallbackAvailable: Boolean,
    onUseTabletStorage: () -> Unit,
    onManageStorage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ArchiveWritabilityText.alertTitle(verdict)) },
        text = { Text(ArchiveWritabilityText.alertMessage(verdict, fallbackAvailable)) },
        confirmButton = {
            TextButton(onClick = onUseTabletStorage) { Text(ArchiveWritabilityText.USE_TABLET_STORAGE) }
        },
        dismissButton = {
            TextButton(onClick = onManageStorage) { Text(ArchiveWritabilityText.MANAGE_STORAGE) }
        },
    )
}

/**
 * The single archive-storage alert: a read-only/failing card, retained state on a previous card
 * that is no longer in the tablet, or both in one dialog. Nothing is deleted or moved from here.
 */
@Composable
internal fun ArchiveStorageAlertDialog(
    verdict: ArchiveWritabilityVerdict?,
    fallbackAvailable: Boolean,
    missing: MissingPreviousStorageNotice?,
    onUseTabletStorage: () -> Unit,
    onManageStorage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (verdict == null && missing == null) return
    if (verdict != null && missing == null) {
        ArchiveStorageAlertDialog(verdict, fallbackAvailable, onUseTabletStorage, onManageStorage, onDismiss)
        return
    }
    val title = verdict?.let { ArchiveWritabilityText.alertTitle(it) } ?: MissingPreviousStorageText.title(missing!!)
    val message = listOfNotNull(
        verdict?.let { ArchiveWritabilityText.alertMessage(it, fallbackAvailable) },
        missing?.let { MissingPreviousStorageText.message(it) },
    ).joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (verdict != null) TextButton(onClick = onUseTabletStorage) { Text(ArchiveWritabilityText.USE_TABLET_STORAGE) }
            else TextButton(onClick = onDismiss) { Text(MissingPreviousStorageText.OK) }
        },
        dismissButton = {
            TextButton(onClick = onManageStorage) { Text(ArchiveWritabilityText.MANAGE_STORAGE) }
        },
    )
}

/** Steady map status while the archive is not writable or a previous card is missing. Tapping re-opens the alert. */
@Composable
fun ArchiveStorageMapChip(onClick: () -> Unit = { ArchiveWritability.reshowAlert() }) {
    val verdict by ArchiveWritability.state.collectAsStateWithLifecycle()
    val missing by MissingPreviousStorage.notice.collectAsStateWithLifecycle()
    if (verdict.writable) {
        missing?.let { notice ->
            AssistChip(
                onClick = onClick,
                label = { Text(missingChipText(notice), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) },
                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFE65100), labelColor = Color.White),
                modifier = Modifier.padding(PaddingValues(0.dp)),
            )
        }
        return
    }
    val context = LocalContext.current
    val fallback = remember(verdict) { ArchiveWritability.tabletFallbackAvailable(context) }
    ArchiveStorageChip(verdict, fallback, onClick)
}

internal fun missingChipText(notice: MissingPreviousStorageNotice): String =
    if (notice.problem == ArchiveStateProblem.PREVIOUS_ARCHIVE_REQUIRED) "Previous archive folder missing"
    else "Previous SD card missing" + (notice.volume?.let { " ($it)" } ?: "")

@Composable
fun ArchiveStorageChip(verdict: ArchiveWritabilityVerdict, fallbackAvailable: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(ArchiveWritabilityText.chip(verdict, fallbackAvailable), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) },
        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFB3261E), labelColor = Color.White),
        modifier = Modifier.padding(PaddingValues(0.dp)),
    )
}

/** Steady status line for Flight Storage (Manage Storage). */
@Composable
fun ArchiveStorageStatusLine() {
    val verdict by ArchiveWritability.state.collectAsStateWithLifecycle()
    if (verdict.writable) return
    val context = LocalContext.current
    val fallback = remember(verdict) { ArchiveWritability.tabletFallbackAvailable(context) }
    Text(
        ArchiveWritabilityText.status(verdict, fallback),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Flight Storage line while retained state is on a card that is not in the tablet. */
@Composable
fun MissingPreviousStorageStatusLine() {
    val missing by MissingPreviousStorage.notice.collectAsStateWithLifecycle()
    val notice = missing ?: return
    Text(
        MissingPreviousStorageText.status(notice),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Compact strip above the map: live stream, Capture Streams off. */
@Composable
fun NotCapturingStreamPanelHost() {
    val streams by StreamRegistry.streams.collectAsStateWithLifecycle()
    val capture by NotCapturingStreams.captureEnabled.collectAsStateWithLifecycle()
    val dismissed by NotCapturingStreams.dismissed.collectAsStateWithLifecycle()
    LaunchedEffect(streams) {
        NotCapturingStreams.prune(streams.values.map(NotCapturingStreams::key).toSet())
    }
    val pending = remember(streams, capture, dismissed) {
        NotCapturingStreams.pending(streams.values, capture, dismissed)
    }
    val first = pending.firstOrNull() ?: return
    NotCapturingStreamPanel(
        stream = first,
        moreCount = pending.size - 1,
        onConfirm = { NotCapturingStreams.confirm(first) },
        onTurnOn = { NotCapturingStreams.turnOn() },
    )
}

@Composable
fun NotCapturingStreamPanel(
    stream: NotCapturingStream,
    moreCount: Int,
    onConfirm: () -> Unit,
    onTurnOn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = Color(0xFFFFF3E0), contentColor = Color(0xFF1F2937), modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                NotCapturingStreams.title(stream) + if (moreCount > 0) " (+$moreCount more)" else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onConfirm) { Text(NotCapturingStreams.CONFIRM) }
            TextButton(onClick = onTurnOn) { Text(NotCapturingStreams.TURN_ON) }
        }
    }
}

private val previewVerdict = ArchiveWritabilityVerdict(
    writable = false, problem = ArchiveWriteProblem.READ_ONLY, detail = "Read-only file system",
    sinceMs = 1_795_532_460_000L, removable = true, generation = 1,
)

@Preview(widthDp = 900, heightDp = 60)
@Composable
private fun NotCapturingStreamPanelPreview() {
    MaterialTheme { NotCapturingStreamPanel(NotCapturingStream("p#1", "1sar7DjMtrc4td"), 0, {}, {}) }
}

@Preview(widthDp = 500, heightDp = 360)
@Composable
private fun ArchiveStorageAlertPreview() {
    MaterialTheme { ArchiveStorageAlertDialog(previewVerdict, true, {}, {}, {}) }
}

@Preview(widthDp = 500, heightDp = 360)
@Composable
private fun MissingPreviousStorageAlertPreview() {
    MaterialTheme {
        ArchiveStorageAlertDialog(null, true,
            MissingPreviousStorageNotice("4A21-0000", ArchiveStateProblem.PREVIOUS_STORAGE_MISSING, setOf("unfinished-recordings", "queue-state")),
            {}, {}, {})
    }
}

@Preview(widthDp = 420, heightDp = 60)
@Composable
private fun ArchiveStorageChipPreview() {
    MaterialTheme { Column { ArchiveStorageChip(previewVerdict, true) {} } }
}
