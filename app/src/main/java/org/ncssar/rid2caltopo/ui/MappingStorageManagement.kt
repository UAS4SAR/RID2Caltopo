package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import org.ncssar.rid2caltopo.mapping.MappingLocalLayerDialog
import org.ncssar.rid2caltopo.mapping.MappingProcessingManager
import org.ncssar.rid2caltopo.mapping.MappingStorageSummary
import org.ncssar.rid2caltopo.mapping.MappingStorageUsage
import org.ncssar.rid2caltopo.mapping.MappingWorkspaceState
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings

/**
 * Manage Storage → Local Maps. Archive totals and project sizes stay at the top.
 * The mission list below is the former Manage Local Maps library: sort, distance,
 * new flight, view map, Pilot 2 connection, hide preview, and delete.
 * Cached sizes show immediately; a background rescan runs only when top-level mapping
 * artifacts are newer than the cache, or after delete invalidation.
 * Archive totals use on-card allocated bytes; the operational-work projection is a
 * separate Working copy line.
 */
@Composable
internal fun MappingStorageManagementDialog(onDismiss: () -> Unit, onView: (org.ncssar.rid2caltopo.mapping.MappingLayer?) -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf(MappingStorageSummary.peekMemory()) }
    var totalBytes by remember { mutableLongStateOf(report?.totalBytes ?: 0L) }
    var complete by remember { mutableStateOf(report?.complete ?: true) }
    var loading by remember { mutableStateOf(report == null) }
    var refreshing by remember { mutableStateOf(false) }
    var needsRescan by remember { mutableStateOf(false) }
    var scanNonce by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var flightRequest by remember { mutableStateOf<String?>(null) }
    var pendingLayerId by remember { mutableStateOf<String?>(null) }
    var inspectingLayer by remember { mutableStateOf<org.ncssar.rid2caltopo.mapping.MappingLayer?>(null) }

    LaunchedEffect(pendingLayerId) {
        val id = pendingLayerId ?: return@LaunchedEffect
        val layer = withContext(Dispatchers.IO) {
            org.ncssar.rid2caltopo.mapping.MappingLayer.allLocal(context).firstOrNull { it.id == id }
        }
        pendingLayerId = null
        if (layer != null) inspectingLayer = layer
        else error = "That map layer is no longer available."
    }

    LaunchedEffect(scanNonce) {
        error = ""
        if (scanNonce > 0) refreshing = true
        try {
            val filesDir = context.filesDir
            val cached = withContext(Dispatchers.IO) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                MappingStorageSummary.loadCached(filesDir)
            }
            if (cached != null) {
                report = cached
                totalBytes = cached.totalBytes
                complete = cached.complete
                loading = false
            } else {
                // First open: show project rows immediately with calculating… sizes.
                val placeholders = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    MappingStorageSummary.projectPlaceholders(filesDir, context)
                }
                report = placeholders
                totalBytes = 0L
                complete = false
                loading = false
            }
            val live = MappingProcessingManager.state.value
            // Force only after delete/move; otherwise stamp decides incremental vs cache.
            val force = needsRescan
            val next = withContext(Dispatchers.IO) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                MappingStorageSummary.cachedOrRefresh(
                    filesDir,
                    live = live,
                    context = context,
                    force = force,
                )
            }
            report = next
            totalBytes = next.totalBytes
            complete = next.complete
            needsRescan = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message ?: "Local Maps storage is unavailable."
        } finally {
            loading = false
            refreshing = false
        }
    }

    val current = report
    MappingLocalLayerDialog(
        onView = onView,
        onDismiss = onDismiss,
        title = "Local Maps",
        confirmLabel = "Close",
        listMaxHeight = 560.dp,
        manageStorage = true,
        requestedFlightId = flightRequest,
        onRequestedFlightHandled = { flightRequest = null },
        requestedLayer = inspectingLayer,
        onRequestedLayerHandled = { inspectingLayer = null },
        onLayersChanged = {
            MappingWorkspaceState.layersChanged()
            MappingStorageSummary.invalidate(context.filesDir)
            needsRescan = true
            scanNonce++
        },
        onMissionClosed = { if (needsRescan) scanNonce++ },
        leading = {
            item(key = "storage-summary") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Total: ${if (!complete && totalBytes > 0) "at least " else ""}${MapCacheSettings.formatDecimalGb(totalBytes)}" +
                            if (current?.logicalBytes != null && current.logicalBytes > 0 && current.logicalBytes != totalBytes) {
                                " (logical ${MapCacheSettings.formatDecimalGb(current.logicalBytes)})"
                            } else "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("mapping-storage-total"),
                    )
                    Text(
                        "Archive on-card size. Projects, originals, map layers and processing checkpoints. Not deleted by map cache cleanup.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (loading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Measuring Local Maps storage…")
                        }
                    } else if (refreshing || current?.projects?.any { !it.sized } == true) {
                        Text("Updating sizes…", style = MaterialTheme.typography.bodySmall)
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            item(key = "storage-summary-divider") { HorizontalDivider() }
            if (current != null) {
                items(current.projects, key = { "summary-${it.id}" }) { entry ->
                    MappingStorageProjectRow(entry) {
                        if (entry.flightId != null) flightRequest = entry.flightId
                        else if (entry.layerId != null) pendingLayerId = entry.layerId
                    }
                }
                if (current.sharedLayersBytes > 0) {
                    item(key = "shared-layers") { MappingStorageCategoryRow("Shared layers", current.sharedLayersBytes) }
                }
                if (current.statsBytes > 0) {
                    item(key = "stats") { MappingStorageCategoryRow("Stats", current.statsBytes) }
                }
                if (current.processingBytes > 0) {
                    item(key = "processing") { MappingStorageCategoryRow("Processing", current.processingBytes) }
                }
                if (current.otherBytes > 0) {
                    item(key = "other") { MappingStorageCategoryRow("Other", current.otherBytes) }
                }
                if (current.projects.isEmpty() &&
                    current.sharedLayersBytes == 0L &&
                    current.statsBytes == 0L &&
                    current.processingBytes == 0L &&
                    current.otherBytes == 0L &&
                    error.isEmpty() &&
                    current.projects.all { it.sized }
                ) {
                    item(key = "storage-empty") { Text("No mapping projects yet.") }
                }
            } else if (!loading && error.isEmpty()) {
                item(key = "storage-empty") { Text("No mapping projects yet.") }
            }
        },
    )
}

@Composable
private fun MappingStorageProjectRow(entry: MappingStorageSummary.ProjectLine, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp)
            .testTag("mapping-storage-entry-${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        if (entry.timeLabel.isNotEmpty()) {
            Text(entry.timeLabel, style = MaterialTheme.typography.bodySmall)
        }
        Text("${entry.photoCount} originals", style = MaterialTheme.typography.bodySmall)
        Text(
            if (!entry.sized) {
                "Size: calculating…"
            } else {
                "Size: ${if (entry.complete) "" else "At least "}${MappingStorageUsage.format(entry.bytes)}" +
                    " · layers ${MappingStorageUsage.format(entry.layersBytes)}" +
                    " · checkpoints ${MappingStorageUsage.format(entry.workingBytes)}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Tap to inspect or delete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun MappingStorageCategoryRow(label: String, bytes: Long) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("mapping-storage-category-${label.lowercase().replace(' ', '-')}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text("Size: ${MappingStorageUsage.format(bytes)}", style = MaterialTheme.typography.bodySmall)
    }
}

internal data class MappingStorageReport(
    val entries: List<org.ncssar.rid2caltopo.mapping.MappingLibraryEntry>,
    val totalBytes: Long,
    val complete: Boolean,
)

/** Off-main measurement of the Local Maps repository total for the Manage Storage row. */
internal fun measureMappingRepositoryBytes(context: android.content.Context): Pair<Long, Boolean> {
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    val report = MappingStorageSummary.cachedOrRefresh(
        context.filesDir,
        live = MappingProcessingManager.state.value,
        context = context,
        force = false,
    )
    return report.totalBytes to report.complete
}
