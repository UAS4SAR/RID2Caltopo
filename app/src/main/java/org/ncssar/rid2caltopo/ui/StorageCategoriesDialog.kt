package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.app.FlightStorage
import org.ncssar.rid2caltopo.mapping.MappingStorageSummary
import org.ncssar.rid2caltopo.storage.StorageCategory
import org.ncssar.rid2caltopo.storage.StorageCategoryCache
import org.ncssar.rid2caltopo.storage.StorageCategoryReport
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache

/**
 * Secondary storage breakdown. Known Map Cache, Flight, and Local Maps sizes show
 * immediately. A full walk runs only when nothing is known, or when the operator refreshes.
 */
@Composable
internal fun StorageBreakdown(refreshNonce: Int = 0) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<StorageCategoryReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val mapKnown = UnifiedMapCache.peekUsage()?.total
    val flightKnown = FlightStorage.peekSnapshot()?.used
    val mapsKnown = MappingStorageSummary.peekMemory()?.totalBytes
    LaunchedEffect(refreshNonce) {
        message = ""
        val cached = withContext(Dispatchers.IO) { StorageCategoryCache.peek() }
        if (cached != null && refreshNonce == 0) {
            report = cached
            return@LaunchedEffect
        }
        val known = mapKnown != null || flightKnown != null || mapsKnown != null
        if (refreshNonce == 0 && known) return@LaunchedEffect
        scanning = true
        try {
            report = withContext(Dispatchers.IO) {
                StorageCategoryCache.read(context, force = refreshNonce > 0) { }
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { message = "Storage could not be read. Reconnect Archive Dir and try again." }
        finally { scanning = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("storage-breakdown").semantics {
        contentDescription = "Storage breakdown"
    }) {
        Text("Storage breakdown", style = MaterialTheme.typography.titleSmall)
        if (report != null) {
            val current = report!!
            for (category in StorageCategory.entries) {
                Text("${category.label}: ${MapCacheSettings.formatDecimalGb(current.bytes[category] ?: 0)}")
            }
            Text("Total reported: ${MapCacheSettings.formatDecimalGb(current.total)}")
            if (!current.complete) Text("Some locations are unavailable; the inventory is incomplete.")
        } else {
            Text("Map Cache: ${mapKnown?.let(MapCacheSettings::formatDecimalGb) ?: "scanning…"}")
            Text("Flight Storage: ${flightKnown?.let(MapCacheSettings::formatDecimalGb) ?: "scanning…"}")
            Text("Local Maps: ${mapsKnown?.let(MapCacheSettings::formatDecimalGb) ?: "scanning…"}")
        }
        if (scanning) Row { CircularProgressIndicator(); Text("Scanning storage…") }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        Text("Temporary files are separate from retained data and its allowances.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000L -> String.format(java.util.Locale.US, "%.1f kB", bytes / 1e3)
    else -> "$bytes B"
}
