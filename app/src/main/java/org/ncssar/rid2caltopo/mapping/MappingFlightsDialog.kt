package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.ncssar.rid2caltopo.ui.AlertDialog
import java.io.File

@Composable
fun MappingFlightsDialog(mission: MappingMission?, onDismiss: () -> Unit, onConnection: () -> Unit,
    onViewLayer: (MappingLayer) -> Unit, onLayersChanged: () -> Unit, initialFlightId: String? = null, detailOnly: Boolean = false) {
    val context = LocalContext.current
    val assignmentId = remember(mission?.id) { mission?.json(false)?.getString("assignmentId") }
    val processing by MappingProcessingManager.state.collectAsState()
    val changes by MappingWorkspaceState.layerChanges.collectAsState()
    var flightsLoaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val flights by produceState(emptyList<MappingFlightStatus>(), assignmentId, mission?.incidentId, processing, changes) {
        while (isActive) {
            runCatching { withContext(Dispatchers.IO) { MappingFlightRepository.list(context.filesDir, assignmentId, mission?.incidentId, processing, includeStorage=false, onlyMissionId=if(detailOnly) initialFlightId else null) } }
                .onSuccess { value = it; loadError = null }
                .onFailure { loadError = it.message ?: "Could not read saved missions." }
            flightsLoaded = true
            delay(if(processing?.active==true)1500 else 5000)
        }
    }
    val overview by produceState<Pair<Long,MappingStorageUsage>?>(null, assignmentId, processing, changes) {
        while(isActive && !detailOnly) {
            value=withContext(Dispatchers.IO) { val index=MappingMissionStorage.index(context.filesDir,processing);(MappingVolume.card(context) ?: context.filesDir).usableSpace to index.unassigned() }
            delay(5000)
        }
    }
    var selectedId by rememberSaveable(initialFlightId) { mutableStateOf(initialFlightId) }
    var inspectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selectedSnapshot = flights.firstOrNull { it.id == if (detailOnly) initialFlightId else selectedId }
    val selectedUsage by produceState<MappingStorageUsage?>(null, selectedSnapshot?.id, selectedSnapshot?.photoCount, selectedSnapshot?.resultIds, changes) {
        value=null
        val id=selectedSnapshot?.id
        if(id!=null)value=withContext(Dispatchers.IO){MappingMissionStorage.index(context.filesDir,processing).mission(id)}
    }
    val selected=selectedSnapshot?.copy(storage=selectedUsage)
    val results by produceState(emptyList<MappingLayer>(), selected?.id, selected?.resultIds, changes) {
        value = withContext(Dispatchers.IO) {
            (selected?.resultIds.orEmpty() + listOfNotNull(selected?.layerId)).distinct().mapNotNull { id ->
                runCatching { MappingLayer.parse(JSONObject(File(context.filesDir, "mapping-layers/$id/layer.json").readText())) }.getOrNull()
            }.sortedWith(compareByDescending<MappingLayer> { it.generatedInstant }.thenBy { it.id })
        }
    }
    val capturedAt by produceState<String?>(null, selected?.id, selected?.photoCount) {
        value = null
        value = withContext(Dispatchers.IO) { selected?.let {
            MappingLibraryRepository.capture(File(context.filesDir,"mapping-missions/${it.id}"))
        }.orEmpty() }
    }
    if (detailOnly && selected == null) {
        AlertDialog(onDismissRequest=onDismiss,title={Text("Mapping mission")},text={Text(loadError ?: if (flightsLoaded) "This saved mission is no longer available. Return to Local maps and select a mission." else "Loading mission details…")},
            confirmButton={TextButton(onClick=onDismiss){Text("Back to local maps")}})
        return
    }
    val layer = results.firstOrNull { it.id == inspectedId } ?: results.firstOrNull { it.id == selected?.layerId }
    if (inspectedId != null && layer != null) {
        MappingLayerInspector(layer, onLayersChanged, { inspectedId = null }, onViewLayer)
        return
    }
    MappingFlightsContent(flights, selected, busy, error, results = results, capturedAt = capturedAt,
        onInspectResult = { inspectedId = it.id }, onViewResult = onViewLayer, freeBytes=overview?.first, unassignedStorage=overview?.second, detailOnly = detailOnly, onSelect = { if (it == null && detailOnly) onDismiss() else selectedId = it; error = null }, onDismiss = onDismiss,
        onInspect = { inspectedId = layer?.id }, onView = { layer?.let(onViewLayer) },
        onConnect = { flight ->
            busy = true; error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { DjiCloudManager.resume(context, flight.id, httpDiagnostic = true) } }
                    .onSuccess { onConnection() }.onFailure { error = it.message ?: "Could not reopen the flight connection." }
                busy = false
            }
        }, onDelete = { flight, originals ->
            busy = true; error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { MappingFlightDeletion.delete(context, flight.id, originals) } }
                    .onSuccess { if (detailOnly) onDismiss() else selectedId = null; onLayersChanged() }
                    .onFailure { error = it.message ?: "Could not delete the saved flight." }
                busy = false
            }
        }, onProcess = { flight, completedOnly ->
            busy = true; error = null
            scope.launch {
            runCatching { withContext(Dispatchers.IO) { MappingProcessingManager.start(context, flight.id,
                expectedPhotos = if (completedOnly) flight.completedDigests.size else flight.photoCount,
                selectedDigests = if (completedOnly) flight.completedDigests else null,
                completionSource = if (completedOnly) "operator_selected_completed_batch" else "operator_selected_verified_photos",
                engine = "rtk-surface") } }.onFailure { error = it.message }
            busy = false
            }
        })
}

/** Synthetic UI tests can exercise navigation and scrolling without a controller or processing job. */
@Composable
internal fun MappingFlightsContent(flights: List<MappingFlightStatus>, selected: MappingFlightStatus?, busy: Boolean, error: String?,
    onSelect: (String?) -> Unit, onDismiss: () -> Unit, onInspect: () -> Unit, onView: () -> Unit,
    onConnect: (MappingFlightStatus) -> Unit, onProcess: (MappingFlightStatus, Boolean) -> Unit,
    freeBytes:Long?=null,unassignedStorage:MappingStorageUsage?=null,
    onDelete: (MappingFlightStatus, Boolean) -> Unit = { _, _ -> },
    results: List<MappingLayer> = emptyList(), onInspectResult: (MappingLayer) -> Unit = {},
    onViewResult: (MappingLayer) -> Unit = {}, detailOnly: Boolean = false, capturedAt: String? = "") {
    var browsingOriginals by remember(selected?.id) { mutableStateOf(false) }
    if (browsingOriginals && selected != null) MappingOriginalPhotosDialog(selected.id) { browsingOriginals = false }
    var deletingOriginals by remember(selected?.id) { mutableStateOf<Boolean?>(null) }
    if (selected != null && deletingOriginals != null) {
        val originals = deletingOriginals == true
        AlertDialog(onDismissRequest = { deletingOriginals = null },
            title = { Text(if (originals) "Delete entire saved flight?" else "Delete generated results?") },
            text = { Text(if (originals)
                "${selected.title} · ${selected.time}\n\nAny Pilot 2 connection for this mission will close before deletion. Permanently delete this flight plan, ALL original photos and controller returns, generated 2D/3D results, export packages, and unfinished processing files from this tablet and its microSD card. This cannot be undone. Copies already shared elsewhere are unaffected."
                else "${selected.title} · ${selected.time}\n\nAny Pilot 2 connection for this mission will close before deletion. Delete this flight’s generated 2D/3D results, export packages, and unfinished processing files. Keep the original photos and saved flight so you can reconstruct again.") },
            confirmButton = { TextButton(onClick = { deletingOriginals = null; onDelete(selected, originals) }, modifier = Modifier.testTag("mapping-confirm-delete")) {
                Text(if (originals) "Delete flight and original photos" else "Delete results", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deletingOriginals = null }) { Text("Cancel") } })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) if (selected != null) onSelect(null) else onDismiss() },
        title = { Text(if (selected == null) "Saved flights" else selected.title) },
        text = {
            if (selected == null) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("mapping-flight-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            freeBytes?.let { Text("Free mapping storage: ${MappingStorageUsage.format(it)}") }
                            unassignedStorage?.let { usage ->
                                val products=usage.total-usage.working
                                if(products>0)Text("Other local map products: ${MappingStorageUsage.format(products)} · inspect in Local maps")
                                if(usage.working>0)Text("Other temporary processing files: ${MappingStorageUsage.format(usage.working)}")
                            }
                        }
                    }
                    error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (flights.isEmpty()) item { Text("No saved flights yet.") }
                    items(flights, key = { it.id }) { flight ->
                        Card(Modifier.fillMaxWidth().testTag("flight-${flight.id}").clickable { onSelect(flight.id) }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(flight.title, style = MaterialTheme.typography.titleMedium)
                                Text(flight.time, style = MaterialTheme.typography.bodySmall)
                                Text(flight.inventoryLabel + " · ${flight.resultIds.size} map results")
                                flight.storage?.let { Text("Total mapping storage: ${it.totalLabel}",modifier=Modifier.testTag("storage-${flight.id}")) }
                                Text(flight.stage)
                                if (flight.active) flight.progress?.let { Text("${(it * 100).toInt()}%") }
                                if (flight.pendingCount > 0) Text("${flight.pendingCount} transfers incomplete")
                            }
                        }
                    }
                }
            } else Column(Modifier.verticalScroll(rememberScrollState()).testTag("mapping-flight-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selected.stage)
                TextButton(enabled = !busy && !selected.active, onClick = { deletingOriginals = false }, modifier = Modifier.testTag("mapping-delete-results")) {
                    Text("Delete generated results · keep photos")
                }
                TextButton(enabled = !busy && !selected.active, onClick = { deletingOriginals = true }, modifier = Modifier.testTag("mapping-delete-flight")) {
                    Text("Delete entire saved flight…", color = MaterialTheme.colorScheme.error)
                }
                if (busy) Text("Please wait…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Flight plan", style = MaterialTheme.typography.titleMedium)
                Text("Generated: ${MappingTimestamps.generated(selected.createdAt)}")
                selected.plannedPhotos?.let { photos -> Text("Planned: $photos photos"+(selected.plannedSeconds?.let{" · %.1f min route time".format(it/60.0)}?:"")) }
                selected.plannedHeightMeters?.let{Text("Planned height: ${org.ncssar.rid2caltopo.data.Units.current.length(it)} above takeoff")}
                if(selected.hasRoute && selected.photoCount==0) {
                    TextButton(enabled=!busy,onClick={onConnect(selected)},modifier=Modifier.testTag("mapping-connect-prepared")){Text(if(busy)"Connecting…" else "Connect to Pilot 2")}
                    Text("No photos collected yet. Review the route and start the flight in Pilot 2.")
                }
                Text("Captured images", style = MaterialTheme.typography.titleMedium)
                Text("Captured (first photo): ${capturedAt?.let { MappingTimestamps.captured(it) } ?: "Loading…"}")
                Text("Camera: ${selected.cameraLabel}")
                Text(selected.inventoryLabel)
                if (selected.photoCount > 0) TextButton(onClick = { browsingOriginals = true }, modifier = Modifier.testTag("mapping-browse-originals")) { Text("Browse original photos") }
                selected.storage?.takeIf{selected.photoCount>0||selected.pendingCount>0}?.let { usage ->
                    Text("Total mapping storage: ${usage.totalLabel}",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("mapping-storage-total"))
                    Text("Original photos: ${MappingStorageUsage.format(usage.originals)}")
                    Text("Other controller files / partial uploads: ${MappingStorageUsage.format(usage.controllerReturns)}")
                    Text("Retained controller files and unfinished transfers, including unmatched files or additional upload copies. Verified mission originals are listed separately.",style=MaterialTheme.typography.bodySmall)
                    Text("2D map tiles: ${MappingStorageUsage.format(usage.maps2D)}")
                    Text("3D models and surface data: ${MappingStorageUsage.format(usage.models3D)}")
                    Text("Export packages: ${MappingStorageUsage.format(usage.exports)}")
                    Text("Temporary processing files: ${MappingStorageUsage.format(usage.working)}")
                    Text("Planning files: ${MappingStorageUsage.format(usage.planning)}")
                    Text("Other metadata: ${MappingStorageUsage.format(usage.metadata)}")
                    Text("Includes all associated result versions. Shared photo files are counted once within this mission; export ZIPs occupy additional space.")
                    if(!usage.complete)Text("Some files could not be measured. The displayed total is a minimum.")
                }
                if (selected.active) {
                    selected.progress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
                    selected.remainingSeconds?.let { Text("About ${(it + 59) / 60} min remaining") }
                }
                if (selected.pendingCount > 0) Text("${selected.pendingCount} originals still transferring")
                if (selected.completedDigests.isNotEmpty()) {
                    Text("Completed transfer selection: ${selected.completedDigests.size} originals")
                    if (selected.extraCount > 0) Text("${selected.extraCount} additional originals are retained outside that selection. Choose the completed set or all received originals explicitly.")
                } else if(selected.photoCount>0) Text("No completed transfer selection is recorded. Received originals can be selected explicitly for reconstruction.")
                Text("Map results · ${results.size}", style = MaterialTheme.typography.titleMedium)
                results.forEach { result ->
                    Card(Modifier.fillMaxWidth().testTag("mapping-result-${result.id}")) {
                        Column(Modifier.padding(8.dp)) {
                            Text(result.resultTitle)
                            Text("Generated: ${result.generatedLabel}")
                            Text("${result.photoCount} photos · ${if (result.surfaceModel.isNotBlank()) "2D and 3D" else "2D"}")
                            TextButton(enabled = !busy, onClick = { onViewResult(result) }) { Text("View 2D map") }
                            TextButton(enabled = !busy, onClick = { onInspectResult(result) }) { Text("Inspect / share result · 2D and 3D") }
                        }
                    }
                }
                if (results.isEmpty() && selected.layerId == null) Text("No completed map results yet.")
                if (results.isEmpty() && selected.layerId != null) {
                    TextButton(enabled = !busy, onClick = onView) { Text("View 2D map") }
                    TextButton(enabled = !busy, onClick = onInspect) { Text("Inspect / share result · 2D and 3D") }
                }
                if (selected.visible && !selected.active && selected.photoCount>0) {
                    if (selected.completedDigests.size in 3..MappingVolume.MAX_PHOTOS) TextButton(enabled = !busy, onClick = { onProcess(selected, true) }) {
                        Text("Reconstruct completed set · ${selected.completedDigests.size} originals")
                    }
                    if (selected.photoCount in 3..MappingVolume.MAX_PHOTOS && selected.pendingCount == 0 &&
                        (selected.completedDigests.isEmpty() || selected.extraCount > 0)) TextButton(enabled = !busy, onClick = { onProcess(selected, false) }) {
                        Text("Reconstruct all received · ${selected.photoCount} originals")
                    }
                    Text("Reconstruction stays on screen until it finishes or you cancel it. Originals are retained.")
                }
                if (selected.hasRoute && selected.photoCount>0) TextButton(enabled = !busy, onClick = { onConnect(selected) }) { Text(if (busy) "Connecting…" else "Connect to Pilot 2") }
                Text("The connection receives originals for this saved flight. Review clearance and start any flight in Pilot 2.")
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { if (selected == null) onDismiss() else onSelect(null) }) {
            Text(if (detailOnly || selected == null) "Back to local maps" else "Back to flights")
        } })
}
