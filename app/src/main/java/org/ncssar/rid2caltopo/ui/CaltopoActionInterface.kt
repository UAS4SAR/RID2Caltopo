package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.ncssar.rid2caltopo.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

internal fun incidentMapDisplayValue(state: CaltopoConnectionState): String {
    if (state !is CaltopoConnectionState.MapSelected) return "Standalone"
    return state.map.title.trim().ifEmpty {
        state.map.id.trim().ifEmpty { "Standalone" }
    }
}

@Composable
fun CaltopoActionInterface(
    state: CaltopoConnectionState,
    onActionClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(
        modifier = modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onActionClicked)
            .semantics { contentDescription = "Incident Map: ${incidentMapDisplayValue(state)}" }
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = incidentMapDisplayValue(state),
            onValueChange = {},
            enabled = false,
            readOnly = true,
            maxLines = 2,
            label = { Text("Incident Map") },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = colors.onSurface,
                disabledBorderColor = colors.outline,
                disabledLabelColor = colors.onSurfaceVariant,
            ),
        )
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
