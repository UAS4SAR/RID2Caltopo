package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun incidentMapDisplayValue(state: CaltopoConnectionState): String {
    if (state !is CaltopoConnectionState.MapSelected) return org.ncssar.rid2caltopo.data.CaltopoClient.GetStandaloneIncident() + " · No map"
    return state.map.title.trim().ifEmpty {
        state.map.id.trim().ifEmpty { "Standalone" }
    }
}

@Composable
fun CaltopoActionInterface(
    state: CaltopoConnectionState,
    onActionClicked: () -> Unit,
    modifier: Modifier = Modifier,
    // Live View status line: AssistChip form factor matching Proximity Alerts /
    // NOTAM chips (single line, same height/padding/corners).
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val label = incidentMapDisplayValue(state)
    if (compact) {
        AssistChip(
            onClick = onActionClicked,
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = colors.surfaceVariant,
                labelColor = colors.onSurfaceVariant,
            ),
            modifier = modifier.semantics {
                contentDescription = "Incident Map: $label"
            },
        )
    } else {
        androidx.compose.foundation.layout.Box(
            modifier = modifier
                .clickable(role = Role.Button, onClick = onActionClicked)
                .semantics { contentDescription = "Incident Map: $label" }
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = {},
                enabled = false,
                readOnly = true,
                maxLines = 2,
                label = { Text("Incident Map") },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = colors.onSurface,
                    disabledBorderColor = colors.outline,
                    disabledLabelColor = colors.onSurfaceVariant,
                ),
            )
        }
    }
}


@Composable
fun ConnectedOptionsDialog(
    mapName: String,
    onDismiss: () -> Unit,
    onSwitchMap: () -> Unit,      // Generic callback
    onDisconnect: () -> Unit      // Generic callback
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map Options") },
        text = { Text("You are currently synced with: $mapName") },
        confirmButton = {
            Button(onClick = onSwitchMap) { // Just call the lambda
                Text("Switch Map")
            }
        },
        dismissButton = {
            TextButton(onClick = onDisconnect) { // Just call the lambda
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
