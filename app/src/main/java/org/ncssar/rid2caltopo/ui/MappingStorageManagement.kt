package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.mapping.MappingFlightsDialog
import org.ncssar.rid2caltopo.mapping.MappingLayerInspector
import org.ncssar.rid2caltopo.mapping.MappingLibraryEntry
import org.ncssar.rid2caltopo.mapping.MappingLibraryIndex
import org.ncssar.rid2caltopo.mapping.MappingMissionStorage
import org.ncssar.rid2caltopo.mapping.MappingProcessingManager
import org.ncssar.rid2caltopo.mapping.MappingStorageUsage
import org.ncssar.rid2caltopo.mapping.MappingWorkspaceState
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings

/**
 * Manage Storage → Mapping repository: project list with storage breakdown.
 * Sizes are measured off the main thread via [MappingMissionStorage] (cached, refreshed when artifacts change).
 * Tapping a project opens the existing flight/layer inspector (inspect + delete).
 */
@Composable
internal fun MappingStorageManagementDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<MappingLibraryEntry>>(emptyList()) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var complete by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selectedFlight by remember { mutableStateOf<String?>(null) }
    var inspectingLayer by remember { mutableStateOf<org.ncssar.rid2caltopo.mapping.MappingLayer?>(null) }

    selectedFlight?.let { flightId ->
        MappingFlightsDialog(
            mission = null,
            onDismiss = { selectedFlight = null },
            onConnection = {},
            onViewLayer = {},
            onLayersChanged = MappingWorkspaceState::layersChanged,
            initialFlightId = flightId,
            detailOnly = true,
        )
        return
    }
    inspectingLayer?.let { layer ->
        MappingLayerInspector(layer, MappingWorkspaceState::layersChanged, { inspectingLayer = null }, onView2D = {})
        return
    }

    LaunchedEffect(Unit) {
        loading = true
        error = ""
        try {
            val report = withContext(Dispatchers.IO) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val live = MappingProcessingManager.state.value
                val refreshed = MappingLibraryIndex.refresh(context, live)
                val index = MappingMissionStorage.index(context.filesDir, live)
                var allComplete = !refreshed.stale
                var sum = 0L
                val measured = refreshed.entries.map { entry ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val usage = entry.flight?.let { index.mission(it.id) }
                        ?: entry.layer?.let { MappingMissionStorage.layer(context.filesDir, it.id) }
                    if (usage != null) {
                        sum += usage.total
                        if (!usage.complete) allComplete = false
                        entry.copy(storage = usage)
                    } else entry
                }
                MappingStorageReport(MappingLibraryEntry.sorted(measured, newestFirst = true), sum, allComplete)
            }
            entries = report.entries
            totalBytes = report.totalBytes
            complete = report.complete
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message ?: "Mapping storage is unavailable."
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mapping") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Total: ${if (!complete && totalBytes > 0) "at least " else ""}${MapCacheSettings.formatDecimalGb(totalBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("mapping-storage-total"),
                )
                Text(
                    "Projects, originals, map layers and processing checkpoints. Not deleted by map cache cleanup.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (loading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Measuring mapping storage…")
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).testTag("mapping-storage-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!loading && entries.isEmpty() && error.isEmpty()) {
                        item { Text("No mapping projects yet.") }
                    }
                    items(entries, key = { it.id }) { entry ->
                        MappingStorageProjectRow(entry) {
                            if (entry.flight != null) selectedFlight = entry.flight.id
                            else inspectingLayer = entry.layer
                        }
                    }
                }
            }
        },
        confirmButton = { StorageActionButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MappingStorageProjectRow(entry: MappingLibraryEntry, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp)
            .testTag("mapping-storage-entry-${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        Text(entry.timeLabel, style = MaterialTheme.typography.bodySmall)
        Text("${entry.photoCount} originals", style = MaterialTheme.typography.bodySmall)
        val size = entry.storage
        if (size == null) {
            Text("Storage: calculating…", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "Size: ${size.totalLabel} · layers ${MappingStorageUsage.format(size.maps2D + size.models3D)} · checkpoints ${MappingStorageUsage.format(size.working)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Tap to inspect or delete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

internal data class MappingStorageReport(
    val entries: List<MappingLibraryEntry>,
    val totalBytes: Long,
    val complete: Boolean,
)

/** Off-main measurement of the Mapping repository total for the Manage Storage row. */
internal fun measureMappingRepositoryBytes(context: android.content.Context): Pair<Long, Boolean> {
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    val external = context.getExternalFilesDirs(null).filterNotNull().map { java.io.File(it, "local-mapping") }
    val groups = listOf("mapping-missions", "mapping-layers", "mapping-processing").map { category ->
        listOf(java.io.File(context.filesDir, category)) + external.map { java.io.File(it, category) }
    }
    val local = localMapStorage(groups)
    return local.total to local.complete
}
