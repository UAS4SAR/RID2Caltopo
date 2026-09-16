package org.ncssar.rid2caltopo.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.app.FlightStorage
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache
import org.ncssar.rid2caltopo.video.mapcache.MapCacheMaintenanceScheduler

@Composable
internal fun StorageManagementDialog(onDismiss: () -> Unit, onFlight: () -> Unit) {
    val context = LocalContext.current
    var flight by remember { mutableStateOf<FlightStorage.Snapshot?>(null) }
    var mapBytes by remember { mutableStateOf<Long?>(null) }
    var showCache by remember { mutableStateOf(false) }
    LaunchedEffect(showCache) {
        withContext(Dispatchers.IO) {
            FlightStorage.snapshot(context) to UnifiedMapCache.usage(context).total
        }.let { (snapshot, cache) -> flight = snapshot; mapBytes = cache }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Manage Storage") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Map & Terrain Cache: ${mapBytes?.let(MapCacheSettings::formatDecimalGb) ?: "Scanning…"}, Max Size: ${MapCacheSettings.formatDecimalGb(MapCacheSettings.maxCacheBytes(context))}, Max Age: ${MapCacheSettings.maxTileAgeDays(context)} days",
                modifier = Modifier.clickable { showCache = true }, color = MaterialTheme.colorScheme.primary)
            Text("Flight Storage: ${flight?.let { if (it.archiveReady) MapCacheSettings.formatDecimalGb(it.used) else "Archive usage unavailable" } ?: "Scanning…"}, Max Size: ${MapCacheSettings.formatDecimalGb(FlightStorage.maximumBytes(context))}, Max Age: ${FlightStorage.maximumDays(context)} days",
                modifier = Modifier.clickable(onClick = onFlight), color = MaterialTheme.colorScheme.primary)
            Text("These limits are app allowances, not the device’s total capacity.", style = MaterialTheme.typography.bodySmall)
            flight?.let {
                Text("Device free space: ${MapCacheSettings.formatDecimalGb(it.deviceAvailable)}")
                if (!it.archiveReady) Text("Map cache usage currently covers accessible local data only; archive caches are not included until the folder is connected.", style = MaterialTheme.typography.bodySmall)
                Text("Includes ${MapCacheSettings.formatDecimalGb(it.auxiliary)} of local clue files and recording working copies.", style = MaterialTheme.typography.bodySmall)
                if (it.blocked) Text(it.message)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (showCache) StorageCacheDialog { showCache = false }
}

@Composable
private fun StorageCacheDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var size by remember { mutableStateOf((MapCacheSettings.maxCacheBytes(context) / 1e9).toString()) }
    var days by remember { mutableStateOf(MapCacheSettings.maxTileAgeDays(context).toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cache Management") }, text = {
        Column {
            OutlinedTextField(size, { size = it }, label = { Text("Max Size (GB)") })
            OutlinedTextField(days, { days = it }, label = { Text("Max Age (days)") })
            Text("Includes maps, terrain, AOL and supporting caches. AOL and active terrain are protected from ordinary trimming.")
        }
    }, confirmButton = { TextButton(onClick = {
        val gb = size.toDoubleOrNull(); val age = days.toLongOrNull()
        if (gb == null || !gb.isFinite() || gb !in 0.1..1000.0 || age == null || age !in 1..3650) {
            CaltopoClient.ShowToast("Enter 0.1–1,000 GB and 1–3,650 days.")
        } else {
            MapCacheSettings.setMaxCacheBytes(context, (gb * 1e9).toLong())
            MapCacheSettings.setMaxTileAgeDays(context, age)
            MapCacheMaintenanceScheduler.request(context.applicationContext)
            onDismiss()
        }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun FlightStorageLimits() {
    val context = LocalContext.current
    var size by remember { mutableStateOf((FlightStorage.maximumBytes(context) / 1e9).toString()) }
    var days by remember { mutableStateOf(FlightStorage.maximumDays(context).toString()) }
    Column {
        OutlinedTextField(size, { size = it }, label = { Text("Max Size (GB)") })
        OutlinedTextField(days, { days = it }, label = { Text("Max Age (days)") })
        TextButton(onClick = {
            val gb = size.toDoubleOrNull(); val age = days.toLongOrNull()
            if (gb == null || !gb.isFinite() || gb !in 0.1..1000.0 || age == null || age !in 1..3650) {
                CaltopoClient.ShowToast("Enter 0.1–1,000 GB and 1–3,650 days.")
            } else {
                FlightStorage.save(context, (gb * 1e9).toLong(), age)
                CaltopoClient.ShowToast("Saved. Automatic cleanup applies these limits.")
            }
        }) { Text("Save Limits") }
        Text("Default allowance: 10 GB. Cleanup runs at startup and on demand near 90% usage, restoring 10% free. Today and active files stay protected. If cleanup cannot restore that space, increase the allowance.", style = MaterialTheme.typography.bodySmall)
    }
}

/** In-app filesystem browser works with both local storage and Android document providers. */
@Composable
internal fun FlightDirectoryBrowser(day: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var stack by remember(day) { mutableStateOf(emptyList<DocumentFile>()) }
    var entries by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val directory = stack.lastOrNull()
    LaunchedEffect(directory?.uri, day) {
        loading = true
        entries = withContext(Dispatchers.IO) { (directory?.listFiles()?.toList() ?: FlightStorage.documentsForDay(context, day)).sortedBy { it.name } }
        loading = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(directory?.name ?: day) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (loading) Text("Loading…")
            else if (entries.isEmpty()) Text("Empty folder")
            entries.forEach { file ->
                TextButton(onClick = {
                    if (file.isDirectory) stack = stack + file
                    else try {
                        val uri = if (file.uri.scheme == "file") androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", java.io.File(file.uri.path!!)) else file.uri
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.type ?: "application/octet-stream")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) { CaltopoClient.ShowToast("No file viewer is available for this file type.") }
                }) { Text(file.name ?: "File") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }, dismissButton = {
        if (stack.isNotEmpty()) TextButton(onClick = { stack = stack.dropLast(1) }) { Text("Back") }
    })
}
