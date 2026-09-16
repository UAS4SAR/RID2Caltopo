package org.ncssar.rid2caltopo.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.MutualAidPackageShareSession
import org.ncssar.rid2caltopo.ui.MutualAidPackageShareDialog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun parseMutualAidPackageExpiry(
    dateText: String,
    timeText: String,
    zoneId: ZoneId,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter
): Long {
    return runCatching {
        val date = LocalDate.parse(dateText.trim(), dateFormatter)
        val time = LocalTime.parse(timeText.trim(), timeFormatter)
        LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}

@Composable
internal fun MapPaneMutualAidDialogs(
    showPackageDialog: Boolean,
    onShowPackageDialogChange: (Boolean) -> Unit,
    sourceLabel: String,
    includeMapAccess: Boolean,
    onIncludeMapAccessChange: (Boolean) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    incident: String,
    onIncidentChange: (String) -> Unit,
    opPeriod: String,
    onOpPeriodChange: (String) -> Unit,
    mapId: String,
    onMapIdChange: (String) -> Unit,
    mapTitle: String,
    onMapTitleChange: (String) -> Unit,
    expiryDateText: String,
    onExpiryDateTextChange: (String) -> Unit,
    expiryTimeText: String,
    onExpiryTimeTextChange: (String) -> Unit,
    useMapPaneExtents: Boolean,
    onUseMapPaneExtentsChange: (Boolean) -> Unit,
    parsedExpiryEpochMs: Long,
    preparingShare: Boolean,
    onStartShare: () -> Unit,
    activeShareSession: MutualAidPackageShareSession?,
    onShareDone: () -> Unit
) {
    if (showPackageDialog) {
        val nowMs = System.currentTimeMillis()
        AlertDialog(
            onDismissRequest = { onShowPackageDialogChange(false) },
            title = { Text("Export MA Package") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Export a mutual-aid package from the current map using already-cached imagery and DEM data only.",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeMapAccess, onCheckedChange = onIncludeMapAccessChange)
                        Text("Include shared map access")
                    }
                    Text("Leave off for tiles only: cached map and terrain data, without account credentials or changes to the recipient’s map/bookmark.", fontSize = 12.sp)
                    if (includeMapAccess && !CaltopoClient.HasMutualAidTemplate()) {
                        Text("Configure the Mutual Aid account in Settings to include map access.", color = MaterialTheme.colorScheme.error)
                    }
                    if (includeMapAccess) Text("Source org: ${sourceLabel.ifBlank { "Not configured in Settings" }}")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = onDisplayNameChange,
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = incident,
                        onValueChange = onIncidentChange,
                        label = { Text("Incident") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = opPeriod,
                        onValueChange = onOpPeriodChange,
                        label = { Text("Op period") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    if (includeMapAccess) {
                        OutlinedTextField(
                            value = mapId,
                            onValueChange = onMapIdChange,
                            label = { Text("Map ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mapTitle,
                            onValueChange = onMapTitleChange,
                            label = { Text("Map title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = useMapPaneExtents,
                            onCheckedChange = onUseMapPaneExtentsChange
                        )
                        Text("Use MapPane extents")
                    }
                    Text(
                        if (useMapPaneExtents) {
                            "Export uses the current MapPane viewport instead of the offline-prep boundary selection."
                        } else {
                            "Export uses the offline-prep boundary selection when one is active; otherwise it uses the current MapPane viewport."
                        },
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (includeMapAccess) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = expiryDateText,
                            onValueChange = onExpiryDateTextChange,
                            label = { Text("Expiry date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = expiryTimeText,
                            onValueChange = onExpiryTimeTextChange,
                            label = { Text("Expiry time") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (includeMapAccess && parsedExpiryEpochMs <= nowMs) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Expiry must be a future local date/time in yyyy-MM-dd and HH:mm format.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (!includeMapAccess || (CaltopoClient.HasMutualAidTemplate() &&
                        sourceLabel.isNotBlank() && incident.isNotBlank() &&
                        opPeriod.isNotBlank() && mapId.isNotBlank() &&
                        parsedExpiryEpochMs > nowMs)) &&
                        !preparingShare,
                    onClick = {
                        onShowPackageDialogChange(false)
                        onStartShare()
                    }
                ) { Text("Prepare MA Package") }
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
            title = { Text("Preparing MA Package") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Packaging cached map and DEM data for transfer…")
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
