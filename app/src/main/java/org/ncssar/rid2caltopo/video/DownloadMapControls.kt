package org.ncssar.rid2caltopo.video

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun DownloadMapCheckbox(title: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Text(title)
    }
}

@Composable
internal fun DownloadMapChoice(selected: String, choices: List<String>, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$selected ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; onSelect(choice) })
            }
        }
    }
}

@Composable
internal fun DownloadMapZoomRow(
    label: String,
    minZoom: Int,
    maxZoom: Int,
    sourceMin: Int,
    sourceMax: Int,
    enabled: Boolean,
    onMinChange: (Int) -> Unit,
    onMaxChange: (Int) -> Unit,
) {
    val zooms = (sourceMin..sourceMax).map { it.toString() }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Min", style = MaterialTheme.typography.bodySmall)
            DownloadMapChoice(minZoom.toString(), zooms, enabled, modifier = Modifier.weight(1f)) { value ->
                val next = value.toInt().coerceIn(sourceMin, sourceMax)
                onMinChange(next)
                if (next > maxZoom) onMaxChange(next)
            }
            Text("Max", style = MaterialTheme.typography.bodySmall)
            DownloadMapChoice(maxZoom.toString(), zooms, enabled, modifier = Modifier.weight(1f)) { value ->
                val next = value.toInt().coerceIn(sourceMin, sourceMax)
                onMaxChange(next)
                if (next < minZoom) onMinChange(next)
            }
        }
    }
}

/** Stacks at large text sizes or narrow widths instead of squeezing the value. */
@Composable
internal fun DownloadMapSummaryRow(label: String, value: String) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, modifier = Modifier.padding(end = 16.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
