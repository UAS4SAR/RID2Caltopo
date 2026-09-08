package org.ncssar.rid2caltopo.video

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.video.mapcache.BadTilePolicy
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings

@Composable
internal fun BoxScope.MapPaneSettingsMenus(
    context: Context,
    settingsMenuExpanded: Boolean,
    onSettingsMenuExpandedChange: (Boolean) -> Unit,
    mapManagementMenuExpanded: Boolean,
    onMapManagementMenuExpandedChange: (Boolean) -> Unit,
    baseLayerMenuExpanded: Boolean,
    onBaseLayerMenuExpandedChange: (Boolean) -> Unit,
    badTilesMenuExpanded: Boolean,
    onBadTilesMenuExpandedChange: (Boolean) -> Unit,
    baseLayer: BaseLayerOption,
    predictiveHeadEnabled: Boolean,
    followFocusedDroneEnabled: Boolean,
    mapReloadInFlight: Boolean,
    mapCacheAvailableBytes: Long?,
    downloadMapStatus: String?,
    mapName: String?,
    autoRemoveBadTiles: Boolean,
    contourOverlayEnabled: Boolean,
    hasMapFolders: Boolean,
    onTogglePredictiveHead: () -> Unit,
    onDownloadMap: () -> Unit,
    onOpenMapFolders: () -> Unit,
    onToggleFollowFocusedDrone: () -> Unit,
    onReloadMap: () -> Unit,
    onOpenBadTiles: () -> Unit,
    onOpenBadTilesHowTo: () -> Unit,
    onOpenCacheSize: () -> Unit,
    onOpenTileAge: () -> Unit,
    onOpenMutualAidPackage: () -> Unit,
    onToggleAutoRemoveBadTiles: () -> Unit,
    onClearBadTileFlags: () -> Unit,
    onExportBadTileHashes: () -> Unit,
    onBaseLayerSelected: (BaseLayerOption) -> Unit,
    onToggleContours: () -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
    ) {
        IconButton(
            onClick = { onSettingsMenuExpandedChange(true) },
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Map settings"
            )
        }
        DropdownMenu(
            expanded = settingsMenuExpanded,
            onDismissRequest = {
                onSettingsMenuExpandedChange(false)
                onMapManagementMenuExpandedChange(false)
                onBaseLayerMenuExpandedChange(false)
                onBadTilesMenuExpandedChange(false)
            }
        ) {
            DropdownMenuItem(
                text = { Text("Layer: ${baseLayer.label}") },
                onClick = {
                    onSettingsMenuExpandedChange(false)
                    onBaseLayerMenuExpandedChange(true)
                }
            )
            DropdownMenuItem(
                text = { Text(if (predictiveHeadEnabled) "Predictive Head: On" else "Predictive Head: Off") },
                onClick = onTogglePredictiveHead
            )
            DropdownMenuItem(
                text = {
                    Text(downloadMapStatus?.let { "Download Map: $it" } ?: "Download Map...")
                },
                onClick = onDownloadMap
            )
            DropdownMenuItem(
                text = { Text("Map Folders...") },
                onClick = onOpenMapFolders,
                enabled = hasMapFolders
            )
            DropdownMenuItem(
                text = { Text("Map Management...") },
                onClick = {
                    onSettingsMenuExpandedChange(false)
                    onMapManagementMenuExpandedChange(true)
                }
            )
        }
        DropdownMenu(
            expanded = mapManagementMenuExpanded,
            onDismissRequest = { onMapManagementMenuExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text(if (followFocusedDroneEnabled) "Follow Focused Drone: On" else "Follow Focused Drone: Off") },
                onClick = onToggleFollowFocusedDrone
            )
            DropdownMenuItem(
                text = { Text(if (mapReloadInFlight) "Reload Map..." else "Reload Map") },
                onClick = onReloadMap,
                enabled = mapName != null && !mapReloadInFlight
            )
            DropdownMenuItem(
                text = { Text("Bad Tiles...") },
                onClick = onOpenBadTiles
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "Max Cache Size: ${MapCacheSettings.formatDecimalGb(MapCacheSettings.maxCacheBytes(context))}" +
                            (mapCacheAvailableBytes?.let { " (${MapCacheSettings.formatDecimalGb(it)} available)" } ?: "")
                    )
                },
                onClick = onOpenCacheSize
            )
            DropdownMenuItem(
                text = { Text("Maximum Tile Age: ${MapCacheSettings.formatTileAge(MapCacheSettings.maxTileAgeDays(context))}") },
                onClick = onOpenTileAge
            )
            DropdownMenuItem(
                text = { Text("Export MA Package...") },
                onClick = onOpenMutualAidPackage
            )
        }
        DropdownMenu(
            expanded = badTilesMenuExpanded,
            onDismissRequest = { onBadTilesMenuExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("How To") },
                onClick = onOpenBadTilesHowTo
            )
            DropdownMenuItem(
                text = { Text(if (autoRemoveBadTiles) "Auto Remove Bad Tiles: On" else "Auto Remove Bad Tiles: Off") },
                onClick = onToggleAutoRemoveBadTiles
            )
            DropdownMenuItem(
                text = { Text("Clear Bad Tile Flags (${BadTilePolicy.blockedHashCount(context)})") },
                onClick = onClearBadTileFlags
            )
            DropdownMenuItem(
                text = { Text("Export Bad Tile Hashes") },
                onClick = onExportBadTileHashes
            )
        }
        DropdownMenu(
            expanded = baseLayerMenuExpanded,
            onDismissRequest = { onBaseLayerMenuExpandedChange(false) }
        ) {
            BaseLayerOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        val selected = if (option == baseLayer) " \u2713" else ""
                        Text("${option.label}$selected")
                    },
                    onClick = { onBaseLayerSelected(option) }
                )
            }
            DropdownMenuItem(
                text = { Text(if (contourOverlayEnabled) "Contours: On" else "Contours: Off") },
                onClick = onToggleContours
            )
        }
    }
}
