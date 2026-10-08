package org.ncssar.rid2caltopo.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.data.CaltopoClient

@Composable
internal fun IncidentSelectionDialog(onDismiss: () -> Unit, onConnectMap: () -> Unit, onUseName: (String) -> Unit) {
    var name by remember { mutableStateOf(CaltopoClient.GetStandaloneIncident()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Select Incident") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Current incident: ${CaltopoClient.GetIncident()}")
            StorageActionButton(onClick = onConnectMap) { Text("Connect to a map…") }
            Text("Choose personal or organization credentials, then an incident map.")
            HorizontalDivider()
            Text("Incident without a map", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Incident name") }, singleLine = true)
            Text("Using this name disconnects the current incident map. Existing archived flights keep their original incident.")
        }
    }, confirmButton = {
        StorageActionButton(onClick = { onUseName(name.trim()) }, enabled = name.isNotBlank()) { Text("Use without a map") }
    }, dismissButton = { StorageActionButton(onClick = onDismiss) { Text("Cancel") } })
}
