package org.ncssar.rid2caltopo.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.ncssar.rid2caltopo.ui.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.data.MutualAidPackageShareSession
import org.ncssar.rid2caltopo.ui.MutualAidPackageShareDialog

@Composable
internal fun MapPaneMutualAidDialogs(
    showPackageDialog: Boolean,
    onShowPackageDialogChange: (Boolean) -> Unit,
    selectedTiles: Set<String>,
    availableMappingLayers: Int,
    onSelectedTilesChange: (Set<String>) -> Unit,
    preparingShare: Boolean,
    onStartShare: () -> Unit,
    activeShareSession: MutualAidPackageShareSession?,
    onShareDone: () -> Unit
) {
    if (showPackageDialog) {
        AlertDialog(
            onDismissRequest = { onShowPackageDialogChange(false) },
            title = { Text("Export Map Package") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "OSM and Esri basemap tiles are excluded. This exports permitted cached data only; it does not download anything. For the checked tile types, all cached zoom levels overlapping the current visible map region are included. R2C mapping layers are copied for offline use on the receiving device.",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    (listOf("Contours", "DEM", "AOL") +
                        if (availableMappingLayers > 0) listOf("R2C Mapping layers") else emptyList()).forEach { tile ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = tile in selectedTiles, onCheckedChange = { checked ->
                                onSelectedTilesChange(if (checked) selectedTiles + tile else selectedTiles - tile)
                            })
                            Text(tile)
                        }
                    }
                    if (availableMappingLayers > 0) Text("$availableMappingLayers mapping layer(s) available for this incident.",fontSize=12.sp)

                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedTiles.isNotEmpty() && !preparingShare,
                    onClick = {
                        onShowPackageDialogChange(false)
                        onStartShare()
                    }
                ) { Text("Prepare Map Package") }
            },
            dismissButton = {
                TextButton(onClick = { onShowPackageDialogChange(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (preparingShare) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Preparing Map Package") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Packaging selected cached tiles for transfer…")
                }
            },
            confirmButton = {}
        )
    }

    activeShareSession?.let { session ->
        MutualAidPackageShareDialog(
            session = session,
            onDone = onShareDone
        )
    }
}
