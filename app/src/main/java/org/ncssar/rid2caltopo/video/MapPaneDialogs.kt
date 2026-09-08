package org.ncssar.rid2caltopo.video

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.DEFAULT_ACTIVE_TRACK_COLOR
import org.ncssar.rid2caltopo.data.DEFAULT_ARCHIVE_TRACK_COLOR
import org.ncssar.rid2caltopo.data.PilotDisplayPreference
import org.ncssar.rid2caltopo.data.sanitizeTrackColor
import org.ncssar.rid2caltopo.notam.NearbyNotam
import org.ncssar.rid2caltopo.ui.MapFoldersDialog
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings

private val PILOT_DISPLAY_COLOR_PALETTE = listOf(
    DEFAULT_ACTIVE_TRACK_COLOR,
    DEFAULT_ARCHIVE_TRACK_COLOR,
    "#E53935",
    "#FB8C00",
    "#FDD835",
    "#43A047",
    "#00ACC1",
    "#3949AB",
    "#8E24AA",
    "#6D4C41",
    "#FFFFFF",
    "#212121"
)

@Composable
internal fun PilotDisplaySettingsContent(
    settings: PilotDisplaySettingsState,
    onPreferenceChange: (PilotDisplaySettingsState, PilotDisplayPreference) -> Unit,
    onPickColor: (PilotDisplayColorSlot) -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pilot Display: ${settings.displayName}", style = MaterialTheme.typography.titleSmall)
        PilotDisplayColorRow(
            label = "Active",
            colorHex = settings.preference.activeTrackColor,
            onClick = { onPickColor(PilotDisplayColorSlot.Active) }
        )
        PilotDisplayColorRow(
            label = "Archive",
            colorHex = settings.preference.archiveTrackColor,
            onClick = { onPickColor(PilotDisplayColorSlot.Archive) }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.preference.bearingEnabled,
                onCheckedChange = { enabled ->
                    onPreferenceChange(
                        settings,
                        settings.preference.copy(bearingEnabled = enabled)
                    )
                }
            )
            Text("Bearing")
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onReset) { Text("Reset") }
        }
    }
}

@Composable
private fun PilotDisplayColorRow(
    label: String,
    colorHex: String,
    onClick: () -> Unit
) {
    val sanitized = sanitizeTrackColor(colorHex, DEFAULT_ACTIVE_TRACK_COLOR)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clip(CircleShape)
                .background(Color(trackColorInt(sanitized, DEFAULT_ACTIVE_TRACK_COLOR)))
        )
        Spacer(Modifier.width(12.dp))
        Text(label)
        Spacer(Modifier.width(12.dp))
        Text(sanitized, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PilotTrackColorPickerDialog(
    target: PilotColorPickerTarget,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    val currentColor = when (target.slot) {
        PilotDisplayColorSlot.Active -> target.settings.preference.activeTrackColor
        PilotDisplayColorSlot.Archive -> target.settings.preference.archiveTrackColor
    }
    val title = when (target.slot) {
        PilotDisplayColorSlot.Active -> "Active Track Color"
        PilotDisplayColorSlot.Archive -> "Archive Track Color"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PILOT_DISPLAY_COLOR_PALETTE.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { colorHex ->
                            val selected = sanitizeTrackColor(currentColor, DEFAULT_ACTIVE_TRACK_COLOR) == colorHex
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape
                                    )
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(Color(trackColorInt(colorHex, DEFAULT_ACTIVE_TRACK_COLOR)))
                                    .clickable { onColorSelected(colorHex) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
internal fun MapPaneNotamDialogs(
    selectedNotam: NearbyNotam?,
    onSelectedNotamChange: (NearbyNotam?) -> Unit,
    selectedNotamGroup: List<NearbyNotam>?,
    onSelectedNotamGroupChange: (List<NearbyNotam>?) -> Unit
) {
    selectedNotam?.let { notice ->
        AlertDialog(
            onDismissRequest = { onSelectedNotamChange(null) },
            title = { Text("NOTAM Detail") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (notice.proximityText.isNotBlank()) {
                        Text(notice.proximityText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(notice.title, style = MaterialTheme.typography.titleMedium)
                    notice.rawReference.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val metaText = buildString {
                        if (notice.intersectsPilotBubble) append("intersects 1 mi operating area")
                        if (notice.effectiveText.isNotBlank()) {
                            if (isNotBlank()) append(" • ")
                            append(notice.effectiveText)
                        }
                    }
                    if (metaText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(metaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (notice.summary.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(notice.summary)
                    }
                    if (notice.details.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(notice.details)
                    }
                    if (notice.rawText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "FAA text: ${notice.rawTitle.ifBlank { notice.rawText }}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (notice.rawText.isNotBlank() && notice.rawText != notice.rawTitle) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Translation: ${notice.rawText}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSelectedNotamChange(null) }) { Text("Close") }
            },
            dismissButton = {}
        )
    }
    selectedNotamGroup?.let { notices ->
        AlertDialog(
            onDismissRequest = { onSelectedNotamGroupChange(null) },
            title = {
                Text(if (notices.size == 1) "NOTAM Here" else "NOTAMs Here")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    notices.forEach { notice ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectedNotamGroupChange(null)
                                    onSelectedNotamChange(notice)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            if (notice.proximityText.isNotBlank()) {
                                Text(
                                    notice.proximityText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(notice.title, style = MaterialTheme.typography.titleMedium)
                            notice.effectiveText.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSelectedNotamGroupChange(null) }) { Text("Close") }
            },
            dismissButton = {}
        )
    }
}

@Composable
internal fun MapPaneManagementDialogs(
    context: Context,
    badTileDialogState: BadTileDialogState?,
    quarantineMatchingHash: Boolean,
    onQuarantineMatchingHashChange: (Boolean) -> Unit,
    onBadTileDialogStateChange: (BadTileDialogState?) -> Unit,
    onRemoveBadTile: (BadTileDialogState, Boolean) -> Unit,
    showMapFoldersDialog: Boolean,
    onShowMapFoldersDialogChange: (Boolean) -> Unit,
    artifactStoreById: Map<String, JSONObject>,
    hiddenFolderIds: MutableSet<String>,
    hiddenItemIds: MutableSet<String>,
    onFolderVisibilityChanged: (String, Boolean) -> Unit,
    onItemVisibilityChanged: (String, Boolean) -> Unit,
    onAllItemsToggled: (List<String>, Boolean) -> Unit,
    onZoomToItem: (String) -> Unit,
    showBadTilesHowToDialog: Boolean,
    onShowBadTilesHowToDialogChange: (Boolean) -> Unit,
    showMapCacheSizeDialog: Boolean,
    onShowMapCacheSizeDialogChange: (Boolean) -> Unit,
    mapCacheSizeInput: String,
    mapCacheAvailableBytes: Long?,
    mapCacheCurrentBytes: Long,
    onMapCacheSizeInputChange: (String) -> Unit,
    onMapCacheSizeSaved: (Long) -> Unit,
    showMapTileAgeDialog: Boolean,
    onShowMapTileAgeDialogChange: (Boolean) -> Unit,
    mapTileAgeDaysInput: String,
    onMapTileAgeDaysInputChange: (String) -> Unit,
    onMapTileAgeSaved: (Long) -> Unit
) {
    badTileDialogState?.let { dlg ->
        AlertDialog(
            onDismissRequest = { onBadTileDialogStateChange(null) },
            title = { Text("Remove Bad Tile?") },
            text = {
                Column {
                    Text("Tile z=${dlg.zoom} x=${dlg.x} y=${dlg.y}")
                    Text("Hash: ${dlg.hash.take(12)}...")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = quarantineMatchingHash,
                            onCheckedChange = onQuarantineMatchingHashChange
                        )
                        Text("Also quarantine same-hash tiles")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onRemoveBadTile(dlg, quarantineMatchingHash) }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { onBadTileDialogStateChange(null) }) { Text("Cancel") }
            }
        )
    }

    if (showMapFoldersDialog) {
        MapFoldersDialog(
            folders = buildMapFolderUiStates(artifactStoreById),
            hiddenFolderIds = hiddenFolderIds,
            hiddenItemIds = hiddenItemIds,
            onFolderVisibilityChanged = onFolderVisibilityChanged,
            onItemVisibilityChanged = onItemVisibilityChanged,
            onAllItemsToggled = onAllItemsToggled,
            onZoomToItem = onZoomToItem,
            onDismiss = { onShowMapFoldersDialogChange(false) }
        )
    }

    if (showBadTilesHowToDialog) {
        AlertDialog(
            onDismissRequest = { onShowBadTilesHowToDialogChange(false) },
            title = { Text("Bad Tiles How To") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Use this when map tiles show a cached error page such as OpenStreetMap's \"Access blocked\" tile.")
                    Text("1. Turn on Auto Remove Bad Tiles if you want quarantined tiles removed automatically when encountered.")
                    Text("2. Long-press a bad tile on the map.")
                    Text("3. In the Remove Bad Tile dialog, leave \"Also quarantine same-hash tiles\" checked and press Remove.")
                    Text("4. The selected tile is removed from cache, and matching bad tiles can be suppressed across the map.")
                    Text("Clear Bad Tile Flags removes the quarantine list only. It does not remove tiles already cached.")
                    Text("Export Bad Tile Hashes saves the quarantined hashes for troubleshooting or sharing.")
                }
            },
            confirmButton = {
                TextButton(onClick = { onShowBadTilesHowToDialogChange(false) }) {
                    Text("OK")
                }
            }
        )
    }

    if (showMapCacheSizeDialog) {
        AlertDialog(
            onDismissRequest = { onShowMapCacheSizeDialogChange(false) },
            title = { Text("Max Cache Size") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the maximum tile cache size in decimal GB.")
                    Text("Currently configured: ${MapCacheSettings.formatDecimalGb(MapCacheSettings.maxCacheBytes(context))}")
                    Text(
                        mapCacheAvailableBytes?.let {
                            "Available on cache volume: ${MapCacheSettings.formatDecimalGb(it)}"
                        } ?: "Available space on cache volume: unavailable"
                    )
                    reasonableCacheMaximumBytes(mapCacheCurrentBytes, mapCacheAvailableBytes)?.let {
                        Text("Largest recommended maximum now: ${MapCacheSettings.formatDecimalGb(it)}")
                    }
                    OutlinedTextField(
                        value = mapCacheSizeInput,
                        onValueChange = onMapCacheSizeInputChange,
                        label = { Text("Decimal GB") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val gb = mapCacheSizeInput.toDoubleOrNull()
                        if (gb == null || gb <= 0.0) {
                            CaltopoClient.ShowToast("Enter a positive cache size in GB.")
                            return@TextButton
                        }
                        onMapCacheSizeSaved((gb * 1_000_000_000.0).toLong())
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowMapCacheSizeDialogChange(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMapTileAgeDialog) {
        AlertDialog(
            onDismissRequest = { onShowMapTileAgeDialogChange(false) },
            title = { Text("Maximum Tile Age") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the maximum tile retention age in days.")
                    OutlinedTextField(
                        value = mapTileAgeDaysInput,
                        onValueChange = { onMapTileAgeDaysInputChange(it.filter { ch -> ch.isDigit() }) },
                        label = { Text("Days") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val days = mapTileAgeDaysInput.toLongOrNull()
                        if (days == null || days <= 0L) {
                            CaltopoClient.ShowToast("Enter a positive tile age in days.")
                            return@TextButton
                        }
                        onMapTileAgeSaved(days)
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowMapTileAgeDialogChange(false) }) {
                    Text("Cancel")
                }
            }
        )
    }
}
