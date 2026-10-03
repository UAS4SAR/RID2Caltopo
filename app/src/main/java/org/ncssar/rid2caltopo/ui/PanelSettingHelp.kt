package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/** Separate help action: opening an explanation never changes the setting. */
@Composable
fun PanelSettingHelpButton(title: String) {
    var showHelp by remember { mutableStateOf(false) }
    IconButton(onClick = { showHelp = true }, modifier = Modifier.semantics { contentDescription = "Help: $title" }) {
        Box(Modifier.size(20.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(title) },
            text = { Text(SettingsFieldHelp.description(title)) },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Done") } }
        )
    }
}

@Composable
fun PanelSettingLabel(title: String, helpKey: String = title, centered: Boolean = false) {
    Row(
        modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.primary)
        PanelSettingHelpButton(helpKey)
    }
}
