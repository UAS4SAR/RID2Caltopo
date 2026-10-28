package org.ncssar.rid2caltopo.ui

import org.ncssar.rid2caltopo.ui.AlertDialog

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.ncssar.rid2caltopo.app.FlightStorage
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache
import org.ncssar.rid2caltopo.video.mapcache.MapCacheMaintenanceScheduler

@Composable
internal fun StorageManagementDialog(
    onDismiss: () -> Unit,
    onFlight: () -> Unit,
    onArchive: () -> Unit,
    loadCache: suspend (android.content.Context) -> MapStorageSnapshot = ::readMapStorage
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var flight by remember { mutableStateOf<FlightStorage.Snapshot?>(null) }
    var mapBytes by remember { mutableStateOf<Long?>(null) }
    var archiveFree by remember { mutableStateOf<Long?>(null) }
    var cacheMaximum by remember { mutableStateOf<Long?>(null) }
    var flightMaximum by remember { mutableStateOf<Long?>(null) }
    var cacheSnapshot by remember { mutableStateOf<MapStorageSnapshot?>(null) }
    var loadingCache by remember { mutableStateOf(false) }
    var cacheError by remember { mutableStateOf("") }
    var flightError by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(false) }
    var localMaps by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    var browseOther by remember { mutableStateOf(false) }
    val storageState by org.ncssar.rid2caltopo.storage.OperationalStorage.status.collectAsState()
    if (browseOther) { OtherArchiveFilesDialog { browseOther = false }; return }
    if (other) { OtherStorageDialog({ browseOther = true }, { other = false }); return }
    if (localMaps) {
        org.ncssar.rid2caltopo.mapping.MappingLocalLayerDialog(onView = { layer ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    org.ncssar.rid2caltopo.mapping.MappingLayer.selectLocal(context, layer)
                    org.ncssar.rid2caltopo.mapping.MappingWorkspaceState.layersChanged()
                }
                localMaps = false; onDismiss()
            }
        }, onDismiss = { localMaps = false })
        return
    }
    if (categories) {
        StorageCategoriesDialog(onCache = { categories = false; loadingCache = true }, onFlight = onFlight,
            onMaps = { categories = false; localMaps = true }, onAdditional = { categories = false; other = true },
            onDismiss = { categories = false })
        return
    }
    cacheSnapshot?.let { snapshot ->
        MapCacheManagementDialog(snapshot) { cacheSnapshot = null }
        return
    }
    LaunchedEffect(loadingCache) {
        if (!loadingCache) return@LaunchedEffect
        try {
            // Keep the parent visible until the complete panel can be presented.
            // Disposing this dialog cancels the load and prevents a late navigation.
            cacheSnapshot = withContext(Dispatchers.IO) { loadCache(context) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { cacheError = "Storage usage could not be read. Please try again." }
        finally { loadingCache = false }
    }
    // These panels have independent I/O. Display flight metadata without waiting
    // for map-cache initialization or the operational-data hydration barrier.
    LaunchedEffect(storageState.ready) {
        flightError = ""
        try {
          val (snapshot, maximum) = withContext(Dispatchers.IO) {
            val current = currentCoroutineContext()
            FlightStorage.snapshot(context) { current.ensureActive() } to FlightStorage.maximumBytes(context)
        }
          flight = snapshot; flightMaximum = maximum
          archiveFree = withContext(Dispatchers.IO) { org.ncssar.rid2caltopo.video.queryAvailableArchiveBytes(context) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { flightError = error.message ?: "Flight storage is unavailable. Reconnect Archive Dir and retry." }
    }
    LaunchedEffect(Unit) {
        try {
            val (used, maximum) = withContext(Dispatchers.IO) {
                if (CaltopoClient.GetArchiveUri() != null) org.ncssar.rid2caltopo.storage.OperationalStorage.requireReady(context)
                UnifiedMapCache.usage(context).total to MapCacheSettings.maxCacheBytes(context)
            }
            mapBytes = used; cacheMaximum = maximum
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { cacheError = error.message ?: "Map storage is unavailable. Reconnect Archive Dir and retry." }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Manage Storage") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (storageState.busy) { CircularProgressIndicator(Modifier.size(20.dp)); Text(storageState.message) }
            StorageActionButton(enabled = !loadingCache, onClick = { cacheError = ""; loadingCache = true }) {
                Text("Map Cache Management...${mapBytes?.let { " — used ${MapCacheSettings.formatDecimalGb(it)} / maximum ${MapCacheSettings.formatDecimalGb(cacheMaximum ?: 0)}" } ?: " — scanning…"}")
            }
            if (loadingCache) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Scanning storage…")
            }
            if (cacheError.isNotEmpty()) Text(cacheError, color = MaterialTheme.colorScheme.error)
            StorageActionButton(onClick = onFlight) { Text("Flight Storage${flight?.let { " — ${if (it.preparingWorkingCopies) "measured so far" else "used"} ${MapCacheSettings.formatDecimalGb(it.used)} / maximum ${MapCacheSettings.formatDecimalGb(flightMaximum ?: 0)}" } ?: if (flightError.isNotEmpty()) " — unavailable" else " — scanning…"}") }
            if (flightError.isNotEmpty()) Text(flightError, color = MaterialTheme.colorScheme.error)
            StorageActionButton(onClick = onArchive) { Text("Archive Storage...") }
            StorageActionButton(onClick = { categories = true }) { Text("Storage Categories...") }
            StorageActionButton(onClick = { localMaps = true }) { Text("Manage Local Maps...") }
            StorageActionButton(onClick = { other = true }) { Text("Other Storage...") }
            flight?.let {
                if (it.preparingWorkingCopies) Text("Flight files sized. Working copies are still being prepared; usage will refresh when preparation finishes.", style = MaterialTheme.typography.bodySmall)
                if (it.archiveReady) {
                    Text("Archive storage free now: ${archiveFree?.let(MapCacheSettings::formatDecimalGb) ?: "Unavailable"}")
                    StorageAllowanceRows(StorageAllocation(archiveFree, mapBytes ?: 0, cacheMaximum ?: 0, it.used, flightMaximum ?: 0))
                }
                else Text("Archive storage is not connected.", style = MaterialTheme.typography.bodySmall)
                Text("Includes ${MapCacheSettings.formatDecimalGb(it.auxiliary)} of local clue files and recording working copies.", style = MaterialTheme.typography.bodySmall)
                if (it.blocked) Text(it.message)
            }
        }
    }, confirmButton = { StorageActionButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
internal fun MapCacheManagementDialog(snapshot: MapStorageSnapshot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember(snapshot) { mutableStateOf(String.format(java.util.Locale.US, "%.3f", snapshot.maximumBytes / 1e9).trimEnd('0').trimEnd('.')) }
    var days by remember(snapshot) { mutableStateOf(snapshot.maximumDays.toString()) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var currentSnapshot by remember(snapshot) { mutableStateOf(snapshot) }
    var clearing by remember { mutableStateOf<Boolean?>(null) }
    clearing?.let { aolOnly ->
        AlertDialog(onDismissRequest = { if (!saving) clearing = null }, title = { Text(if (aolOnly) "Delete prepared AOL data?" else "Clear unused map caches?") }, text = {
            Column { Text("Removes cached downloads and prepared AOL data. Local Maps, original photos and flight files remain. Active downloads and terrain stay protected."); if (message.isNotEmpty()) Text(message) }
        }, confirmButton = { StorageActionButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try {
                    val refreshed = withContext(Dispatchers.IO) { UnifiedMapCache.clearUnused(context, aolOnly); readMapStorage(context) }
                    currentSnapshot = refreshed; clearing = null; message = "Cache cleanup completed."
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "Cache cleanup failed." }
                finally { saving = false }
            }
        }) { Text(if (saving) "Clearing…" else "Clear") } }, dismissButton = { StorageActionButton(enabled = !saving, onClick = { clearing = null }) { Text("Cancel") } })
        return
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Map Cache Management") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            currentSnapshot.let { value ->
                fun gb(bytes: Long) = when {
                    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
                    bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
                    bytes >= 1_000L -> String.format(java.util.Locale.US, "%.1f kB", bytes / 1e3)
                    else -> "$bytes B"
                }
                Text("Cache usage", style = MaterialTheme.typography.titleSmall)
                Text("Used: ${gb(value.cache.total)} / maximum: ${gb(value.maximumBytes)}")
                Text("Map imagery: ${gb(value.cache.imagery)}")
                Text("Terrain files: ${gb(value.cache.terrain)}")
                Text("Decoded terrain: ${gb(value.cache.decoded)}")
                Text("AOL obstacle tiles: ${gb(value.cache.aol)}")
                Text("Icons and elevation samples: ${gb(value.cache.other)}")
                Text("Total cache used: ${gb(value.cache.total)}")
                Text("Downloads reserved: ${gb(value.cache.reserved)}")
                HorizontalDivider()
                Text("Local Maps", style = MaterialTheme.typography.titleSmall)
                Text("Projects, originals and project work: ${gb(value.local.projects)}")
                Text("Map layers and models: ${gb(value.local.layers)}")
                Text("Processing work: ${gb(value.local.processing)}")
                Text("Total local maps: ${gb(value.local.total)}")
                if (!value.local.complete) Text("Some local-map storage is unavailable; these figures are incomplete.")
                Text("Total map storage: ${gb(value.cache.total + value.local.total)}")
                HorizontalDivider()
                Text("Cache location: ${value.cacheLocation}")
                Text("Cache storage free: ${value.cacheFree?.let(::gb) ?: "Unavailable"}")
                Text("Currently configured: ${gb(value.maximumBytes)}, ${value.maximumDays} days")
                StorageAllowanceRows(StorageAllocation(value.cacheFree, value.cache.total, value.maximumBytes, value.flightUsed, value.flightMaximum))
                org.ncssar.rid2caltopo.video.reasonableCacheMaximumBytes(value.cache.total, value.cacheFree)?.coerceAtMost(MapCacheSettings.MAX_CACHE_BYTES)?.let { recommended ->
                    Text("Largest recommended maximum now: ${gb(recommended)}")
                    StorageActionButton(enabled = !saving, onClick = { size = String.format(java.util.Locale.US, "%.2f", kotlin.math.floor(recommended / 10_000_000.0) / 100.0) }) { Text("Allow up to 80% of free space") }
                }
            }
            OutlinedTextField(size, { size = it; message = "" }, enabled = !saving, label = { Text("Max Cache Size (GB)") }, singleLine = true)
            OutlinedTextField(days, { days = it; message = "" }, enabled = !saving, label = { Text("Max Age (days)") }, singleLine = true)
            Text("Size applies to the combined caches. Age applies to downloaded map tiles (minimum 7 days for OSM caching). AOL and active terrain stay protected. Local Maps are shown separately and are not deleted by cache cleanup.", style = MaterialTheme.typography.bodySmall)
            StorageActionButton(enabled = !saving, onClick = { message = ""; clearing = false }) { Text("Clear Unused Map Caches") }
            StorageActionButton(enabled = !saving, onClick = { message = ""; clearing = true }) { Text("Delete Prepared AOL Data") }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { StorageActionButton(enabled = !saving, onClick = {
        val limit = parseStorageLimits(size, days)
        if (limit == null || limit.days < 7) { message = "Enter 0.1–1,000 GB and 7–3,650 days."; return@StorageActionButton }
        val current = currentSnapshot
        val recommended = org.ncssar.rid2caltopo.video.reasonableCacheMaximumBytes(current.cache.total, current.cacheFree)
        if (recommended != null && limit.bytes > recommended) { message = "Requested size exceeds the recommended maximum for this cache storage."; return@StorageActionButton }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(MapCacheSettings.saveLimits(context, limit.bytes, limit.days)) { "Limits could not be saved." }
                    MapCacheMaintenanceScheduler.request(context.applicationContext)
                }
                saving = false; onDismiss()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { saving = false; message = "Limits could not be saved. Please try again." }
        }
    }) { Text(if (saving) "Saving…" else "Save") } }, dismissButton = { StorageActionButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun StorageAllowanceRows(value: StorageAllocation) {
    fun gb(bytes: Long) = MapCacheSettings.formatDecimalGb(bytes)
    Text("Unused cache allowance: ${gb(value.remainingCache)}")
    Text("Unused flight allowance: ${gb(value.remainingFlight)}")
    value.freeAfterAllowances?.let { remaining ->
        Text(if (remaining >= 0) "Free after remaining allowances: ${gb(remaining)}" else "Allowances exceed free space by ${gb(-remaining)}")
    }
    Text("Limits do not reserve disk space. Free now includes unused allowances. Free after remaining allowances estimates the space left if both categories grow to their maximums.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun FlightStorageLimits() {
    val context = LocalContext.current
    var savedBytes by remember { mutableLongStateOf(FlightStorage.maximumBytes(context)) }
    var savedDays by remember { mutableLongStateOf(FlightStorage.maximumDays(context)) }
    var size by remember { mutableStateOf((savedBytes / 1e9).toString()) }
    var days by remember { mutableStateOf(savedDays.toString()) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val parsed = parseStorageLimits(size, days)
    val changed = parsed?.let { it.bytes != savedBytes || it.days != savedDays } ?: true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(size, { size = it; message = "" }, enabled = !saving, label = { Text("Max Size (GB)") })
        OutlinedTextField(days, { days = it; message = "" }, enabled = !saving, label = { Text("Max Age (days)") })
        StorageActionButton(enabled = changed && parsed != null && !saving, onClick = {
            val limits = parsed ?: return@StorageActionButton
            saving = true
            scope.launch {
                val success = withContext(Dispatchers.IO) { FlightStorage.save(context, limits.bytes, limits.days) }
                saving = false
                if (success) {
                    savedBytes = FlightStorage.maximumBytes(context)
                    savedDays = FlightStorage.maximumDays(context)
                    message = "Saved: ${MapCacheSettings.formatDecimalGb(savedBytes)}, $savedDays days."
                } else message = "Limits could not be saved. Please try again."
            }
        }) { Text(if (saving) "Saving…" else "Save Limits") }
        if (parsed == null) Text("Enter 0.1–1,000 GB and 1–3,650 days.", color = MaterialTheme.colorScheme.error)
        Text(if (message.isNotEmpty()) message else "Saved limits: ${MapCacheSettings.formatDecimalGb(savedBytes)}, $savedDays days.")
        Text("Cleanup runs at startup and on demand near 90% usage, restoring 10% free. Today and active files stay protected. If cleanup cannot restore that space, increase the allowance.", style = MaterialTheme.typography.bodySmall)
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
                StorageActionButton(onClick = {
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
    }, confirmButton = { StorageActionButton(onClick = onDismiss) { Text("Close") } }, dismissButton = {
        if (stack.isNotEmpty()) StorageActionButton(onClick = { stack = stack.dropLast(1) }) { Text("Back") }
    })
}

@Composable
internal fun StorageActionButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(
        containerColor = if (dark) androidx.compose.ui.graphics.Color(0xFF242424) else androidx.compose.ui.graphics.Color(0xFFF0F0F0),
        contentColor = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
        disabledContainerColor = if (dark) androidx.compose.ui.graphics.Color(0xFF303030) else androidx.compose.ui.graphics.Color(0xFFE0E0E0),
        disabledContentColor = if (dark) androidx.compose.ui.graphics.Color(0xFF888888) else androidx.compose.ui.graphics.Color(0xFF777777)
    ), content = content)
}
