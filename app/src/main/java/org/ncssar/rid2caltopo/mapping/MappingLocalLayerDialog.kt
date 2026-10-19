package org.ncssar.rid2caltopo.mapping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.ui.AlertDialog

@OptIn(ExperimentalLayoutApi::class)
@Composable fun MappingLocalLayerDialog(onView:(MappingLayer?)->Unit,onDismiss:()->Unit, assignments:List<MappingMission> = emptyList(),onCreate:(MappingMission)->Unit = {}){
    val context=LocalContext.current
    var inspecting by remember{mutableStateOf<MappingLayer?>(null)}
    val changes by MappingWorkspaceState.layerChanges.collectAsState()
    val processing by MappingProcessingManager.state.collectAsState()
    var entries by remember{mutableStateOf(emptyList<MappingLibraryEntry>())}
    var loaded by remember{mutableStateOf(false)}
    var loadError by remember{mutableStateOf<String?>(null)}
    var measuring by remember { mutableStateOf(false) }
    var measuredCount by remember { mutableIntStateOf(0) }
    var measurementTotal by remember { mutableIntStateOf(0) }
    LaunchedEffect(processing?.active,changes) {
        while(isActive){
            try {
                // Publish mission rows before walking photo, tile and model trees for sizes.
                val next=withContext(Dispatchers.IO){MappingLibraryRepository.list(context,MappingProcessingManager.state.value,includeStorage=false)}
                val previous=entries.associateBy { it.id }
                entries=next.map { it.copy(storage=previous[it.id]?.storage) }
                loaded=true;loadError=null
                val index=withContext(Dispatchers.IO){MappingMissionStorage.index(context.filesDir,MappingProcessingManager.state.value)}
                val pending=mutableListOf<MappingLibraryEntry>()
                for(entry in next) {
                    val usage=withContext(Dispatchers.IO){
                        entry.flight?.let { index.cachedMission(it.id) } ?: if(entry.layer!=null)MappingMissionStorage.cachedLayer(context.filesDir,entry.layer.id) else null
                    }
                    if(usage==null)pending.add(entry)
                    else entries=entries.map { if(it.id==entry.id)it.copy(storage=usage) else it }
                }
                if(pending.isNotEmpty()) {
                    measuring=true;measuredCount=0;measurementTotal=pending.size
                    for(entry in pending) {
                        ensureActive()
                        val usage=withContext(Dispatchers.IO){
                            entry.flight?.let { index.mission(it.id) } ?: MappingMissionStorage.layer(context.filesDir,entry.layer!!.id)
                        }
                        entries=entries.map { if(it.id==entry.id)it.copy(storage=usage) else it }
                        measuredCount++
                    }
                }
            }catch(e:CancellationException){throw e}
             catch(e:Exception){loadError="Could not refresh local maps: ${e.message ?: "storage unavailable"}"}
             finally { measuring=false }
            delay(5000)
        }
    }
    var newestFirst by rememberSaveable { mutableStateOf(context.getSharedPreferences("mapping_library",0).getBoolean("newestFirst",true)) }
    val location by produceState<MappingLibraryLocation?>(null) {
        while(isActive) {
            val manual=org.ncssar.rid2caltopo.data.CaltopoMap.GetMyLocationOverride()
            val fix=manual ?: org.ncssar.rid2caltopo.data.CaltopoMap.GetDeviceLocation()
            value=fix?.let {
                val age=if(it.elapsedRealtimeNanos>0) (android.os.SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos)/1_000_000
                    else if(it.time>0) System.currentTimeMillis()-it.time else null
                MappingLibraryLocation.from(it.latitude,it.longitude,age,manual!=null)
            }
            delay(5000)
        }
    }
    val scroll=rememberLazyListState()
    var showFlights by rememberSaveable{mutableStateOf(false)}
    var selectedFlight by rememberSaveable{mutableStateOf<String?>(null)}
    var choosingAssignment by rememberSaveable{mutableStateOf(false)}
    var showConnection by rememberSaveable{mutableStateOf(false)}
    val session by DjiCloudManager.session.collectAsState()
    val flightStates=rememberSaveableStateHolder()
    if(showConnection && session!=null){DjiCloudConnectionDialog(session!!){showConnection=false};return}
    if(showFlights && selectedFlight!=null){flightStates.SaveableStateProvider(selectedFlight ?: "flights"){MappingFlightsDialog(null,{showFlights=false;selectedFlight=null},{showConnection=true},{onView(it);onDismiss()},MappingWorkspaceState::layersChanged,initialFlightId=selectedFlight,detailOnly=true)};return}
    if(choosingAssignment){
        AlertDialog(onDismissRequest={choosingAssignment=false},title={Text("New local map · choose assignment")},text={
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=480.dp).testTag("mapping-assignment-list")) {
                if(assignments.isEmpty())item{Text("Connect or refresh the incident map to load assignment boundaries. Saved flights and results remain available offline.")}
                items(assignments,key={it.id}){assignment->TextButton(onClick={choosingAssignment=false;onCreate(assignment)}){Text(assignment.title)}}
            }
        },confirmButton={TextButton(onClick={choosingAssignment=false}){Text("Back to local maps")}});return
    }
    if(inspecting!=null){MappingLayerInspector(inspecting!!,MappingWorkspaceState::layersChanged,{inspecting=null},onView2D={onView(it);inspecting=null;onDismiss()});return}
    AlertDialog(onDismissRequest=onDismiss,title={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Local maps")
        if(!loaded || measuring) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("mapping-library-progress"))
            Text(if(!loaded) "Loading saved missions…" else "Calculating storage sizes · $measuredCount of $measurementTotal missions",style=MaterialTheme.typography.bodySmall)
        }
    }},text={LazyColumn(
        Modifier.fillMaxWidth().heightIn(max=480.dp).testTag("mapping-local-layer-list"),state=scroll,
        verticalArrangement=Arrangement.spacedBy(10.dp)){
        item(key="intro"){
            Column {
                Text("Create a flight, collect photos, reconstruct, and view or share your maps here.")
                FlowRow {
                    TextButton(onClick={choosingAssignment=true},modifier=Modifier.testTag("mapping-new-flight")){Text("New flight")}

                }
            }
        }
        item(key="sort") {
            Column {
                Text("Mapping missions",style=MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick={newestFirst=true;context.getSharedPreferences("mapping_library",0).edit().putBoolean("newestFirst",true).apply()},modifier=Modifier.testTag("mapping-sort-newest")){Text(if(newestFirst)"✓ Newest first" else "Newest first")}
                    TextButton(onClick={newestFirst=false;context.getSharedPreferences("mapping_library",0).edit().putBoolean("newestFirst",false).apply()},modifier=Modifier.testTag("mapping-sort-oldest")){Text(if(!newestFirst)"✓ Oldest first" else "Oldest first")}
                }
                Text("Sorted by capture time; unflown missions use planning time.",style=MaterialTheme.typography.bodySmall)
            }
        }
        loadError?.let{message->item(key="load-error"){Text(message,color=MaterialTheme.colorScheme.error);Text("Saved missions are retained. Retrying…")}}
        if(loaded&&entries.isEmpty()&&loadError==null)item(key="empty"){Text("No mapping missions yet.")}
        items(MappingLibraryEntry.sorted(entries,newestFirst),key={it.id}){entry->
            MappingLibraryCard(entry,location?.coordinates,location?.note.orEmpty()) {
                if(entry.flight!=null){selectedFlight=entry.flight.id;showFlights=true}
                else inspecting=entry.layer
            }
        }
    }},confirmButton={TextButton(onClick=onDismiss){Text("Done")}},dismissButton={TextButton(onClick={onView(null);onDismiss()}){Text("Hide local preview")}})
}

@Composable internal fun MappingLibraryCard(entry:MappingLibraryEntry,location:Pair<Double,Double>?,locationNote:String="",onOpen:()->Unit) {
    Card(Modifier.fillMaxWidth().testTag("mapping-entry-${entry.id}")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(entry.timeLabel,style=MaterialTheme.typography.bodySmall)
            Text(entry.name,style=MaterialTheme.typography.titleMedium)
            Text(entry.statusLabel)
            Text(entry.stage,style=MaterialTheme.typography.bodySmall)
            Text(entry.distanceLabel(location) + if(location!=null && locationNote.isNotBlank()) " · $locationNote" else "")
            val size=entry.storage
            if(size==null)Text("Storage: calculating…") else {
                Text("Planning files: ${MappingStorageUsage.format(size.planning)} · Captured images: ${MappingStorageUsage.format(size.originals)}")
                Text("2D tiles: ${MappingStorageUsage.format(size.maps2D)} · 3D results: ${MappingStorageUsage.format(size.models3D)}")
                Text("Total: ${size.totalLabel} · includes exports, temporary files and other metadata",style=MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick=onOpen){Text("Open mission")}
        }
    }
}
