package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.ncssar.rid2caltopo.storage.*

@Composable
internal fun StorageCategoriesDialog(onCache: () -> Unit, onFlight: () -> Unit, onMaps: () -> Unit, onAdditional: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<StorageCategoryReport?>(null) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            report = withContext(Dispatchers.IO) {
                val job = coroutineContext
                readStorageCategoryReport(context, OperationalStorage.ready()) { job.ensureActive() }
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { message = "Storage could not be read. Reconnect Archive Dir and try again." }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Storage Categories") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (report == null && message.isEmpty()) { CircularProgressIndicator(); Text("Scanning storage…") }
            report?.let { current ->
                for (category in StorageCategory.entries) Text("${category.label}: ${formatStorageBytes(current.bytes[category] ?: 0)}")
                Text("Total reported: ${formatStorageBytes(current.total)}")
                if (!current.complete) Text("Some locations are unavailable; the inventory is incomplete.")
                Text("Temporary files are separate from retained data and its allowances.", style = MaterialTheme.typography.bodySmall)
                StorageActionButton(onClick = onCache) { Text("Manage Map Cache") }
                StorageActionButton(onClick = onFlight) { Text("Manage Flight Storage") }
                StorageActionButton(onClick = onMaps) { Text("Manage Local Maps") }
                StorageActionButton(onClick = onAdditional) { Text("Manage Other Storage") }
            }
            if (message.isNotEmpty()) Text(message)
        }
    }, confirmButton = { StorageActionButton(onClick = onDismiss) { Text("Close") } })
}

internal fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000L -> String.format(java.util.Locale.US, "%.1f kB", bytes / 1e3)
    else -> "$bytes B"
}
