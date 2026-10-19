package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.ncssar.rid2caltopo.ui.AlertDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun MappingProcessingDialog(state:MappingProcessingState,onView:(MappingLayer)->Unit,onCancel:()->Unit=MappingProcessingManager::cancel){
    val context=LocalContext.current
    if(state.active) {
        var now by remember(state.missionId){mutableLongStateOf(System.nanoTime())}
        LaunchedEffect(state.missionId){while(true){now=System.nanoTime();kotlinx.coroutines.delay(1000)}}
        val sinceUpdate=((now-state.activityAtNanos)/1_000_000_000).coerceAtLeast(0)
        val elapsed=state.elapsedSeconds+sinceUpdate
        org.ncssar.rid2caltopo.ui.Dialog(onDismissRequest={},properties=DialogProperties(
            dismissOnBackPress=false,dismissOnClickOutside=false,usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize().testTag("mapping-job")) {
                Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("Reconstructing mission imagery",style=MaterialTheme.typography.headlineMedium)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(state.title,style=MaterialTheme.typography.titleLarge)
                        Text(state.stage)
                        LinearProgressIndicator(progress={state.progress.toFloat()},modifier=Modifier.fillMaxWidth())
                        Text("${(state.progress*100).toInt()}% · ${elapsed/60} min ${elapsed%60} sec elapsed"+(state.remainingSeconds?.let{" · about ${(it+59)/60} min remaining"}?:""))
                        Text(if(sinceUpdate<5)"Worker updated just now" else "Last worker update ${sinceUpdate} sec ago · waiting for the current operation")
                        Text("Overall progress is approximate. Saving and packaging may take several minutes; file and byte counts show completed work.")
                        Text("Processing on this tablet. This job stays on screen until it finishes or you cancel it. Originals are retained.")
                    }
                    OutlinedButton(onClick=onCancel,modifier=Modifier.align(androidx.compose.ui.Alignment.End)){Text("Cancel processing")}
                }
            }
        }
        return
    }
    AlertDialog(modifier=Modifier.testTag("mapping-job"),onDismissRequest={MappingProcessingManager.dismiss()},title={Text(if(state.error!=null)"Reconstruction stopped" else state.stage)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(state.title)
            if(state.error==null)Text(state.stage)
            state.layer?.let{Text("${it.photoCount} visible photos · estimated ${Units.current.resolution(it.resolutionCmPerPixel)}");Text(it.quality)}
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={state.layer?.let(onView)?:MappingProcessingManager.dismiss()}){
        Text(if(state.layer!=null)"View map" else "Done")
    }},dismissButton={if(state.surfacePreviewId!=null)TextButton(onClick={context.startActivity(android.content.Intent(context,MappingSurfaceViewActivity::class.java).putExtra("layerId",state.surfacePreviewId))}){Text("View 3D surface")}
        else if(state.layer!=null)TextButton(onClick={MappingProcessingManager.dismiss()}){Text("Done")}})
}

@Composable fun MappingLayerInspector(layer:MappingLayer,onDeleted:()->Unit,onDismiss:()->Unit,onView2D:((MappingLayer)->Unit)?=null){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember(layer.id) { mutableStateOf(false) }
    val exportState=remember(layer.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var exportJob by exportState
    DisposableEffect(layer.id) { onDispose { exportState.value?.cancel() } }
    var limitGeoTiffForCalTopo by remember(layer.id) { mutableStateOf(false) }
    var exportReducedDetail by remember(layer.id) { mutableStateOf(false) }
    var exportBytes by remember(layer.id) { mutableStateOf(0L) }
    var exportZoom by remember(layer.id) { mutableStateOf<Int?>(null) }
    var browsingOriginals by remember { mutableStateOf(false) }
    var sharingSurfaces by remember { mutableStateOf(false) }
    var error by remember(layer.id){mutableStateOf<String?>(null)}
    val files by produceState<MappingInspectorFiles?>(null,layer) {
        val loaded=withContext(Dispatchers.IO){runCatching { MappingInspectorFiles.inspect(context.filesDir,LocalMappingTiles.directory(context,layer.id),layer) }}
        value=loaded.getOrNull();error=loaded.exceptionOrNull()?.localizedMessage
    }
    if(browsingOriginals) files?.missionId?.let { MappingOriginalPhotosDialog(it) { browsingOriginals=false } }
    fun share(path:String,mime:String) {
        runCatching {
            val uri=android.net.Uri.parse("content://${context.packageName}.mapping-share/${layer.id}/$path")
            val intent=Intent(Intent.ACTION_SEND).apply { type=mime;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("Map data",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(Intent.createChooser(intent,"Share map data"))
        }.onFailure { error=it.message }
    }
    var softEdges by remember(layer.id){mutableStateOf(MappingBoundary.soft(context,layer.id))}
    val boundary by produceState<MappingBoundary?>(null,layer){value=withContext(Dispatchers.IO){MappingBoundary.resolve(context.filesDir,layer)}}
    val storage by produceState<MappingStorageUsage?>(null,layer.id) {
        value=withContext(Dispatchers.IO){MappingMissionStorage.layer(context.filesDir,layer.id)}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Map Data Inspector")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(layer.title);storage?.let { Text("This result on tablet: ${it.totalLabel} · 2D, 3D and export package") };Text("Captured: ${MappingTimestamps.captured(layer.capturedAt)}")
        Text("Camera: ${files?.cameraLabel ?: MappingResolution.cameraLabel(layer.cameraProfileId)}")
        Text("${layer.photoCount} source photos used to create this map")
        if(layer.resolutionCmPerPixel>0)Text("Estimated ground detail: ${Units.current.resolution(layer.resolutionCmPerPixel)}")
        runCatching{JSONObject(layer.surfaceModel).getDouble("meshSpacingMeters")}.getOrNull()?.takeIf{it.isFinite()&&it>0}?.let{Text("3D preview mesh: ${Units.current.length(it)} spacing")}
        Text("Zoom levels: ${layer.minZoom}–${layer.maxZoom}");Text(layer.engine);Text(layer.quality)
        if(boundary!=null)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Switch(checked=softEdges,onCheckedChange={softEdges=it;MappingBoundary.setSoft(context,layer.id,it)},modifier=Modifier.testTag("mapping-soft-edges"))
            Text(if(softEdges)"Target region · 5 m outer fade" else "Full captured extent")
        }
        if(onView2D!=null)TextButton(onClick={onView2D(layer)}){Text("View 2D map")}
        if(layer.surfaceModel.isNotBlank())TextButton(onClick={onView2D?.invoke(layer);context.startActivity(android.content.Intent(context,MappingSurfaceViewActivity::class.java).putExtra("layerId",layer.id))}){Text("View 3D surface")}
        files?.let { local ->
            if(local.surfaces.size==1) TextButton(onClick={share(local.surfaces.first(),"model/gltf-binary")}) { Text("Share 3D surface (.glb)") }
            else if(local.surfaces.isNotEmpty()) TextButton(onClick={sharingSurfaces=true}) { Text("Share 3D surfaces (.glb)…") }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked=limitGeoTiffForCalTopo,enabled=!busy,onCheckedChange={
                    limitGeoTiffForCalTopo=it;exportZoom=null;exportBytes=0;exportReducedDetail=false;error=null
                },modifier=Modifier.testTag("mapping-geotiff-caltopo-limit"))
                Text("Limit to 40 MB for CalTopo")
            }
            TextButton(enabled=!busy && exportZoom==null,onClick={
                val limitForCalTopo=limitGeoTiffForCalTopo
                busy=true;error=null
                exportJob=scope.launch {
                    try {
                        val result=MappingGeoTiffExport.export(LocalMappingTiles.directory(context,layer.id),layer.minZoom,layer.maxZoom,limitForCalTopo)
                        exportZoom=result.zoom;exportBytes=result.byteCount;exportReducedDetail=result.reducedDetail
                    } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                    catch(failure:Exception){error=failure.localizedMessage}
                    finally {busy=false;exportJob=null}
                }
            }) { Text("Prepare compressed GeoTIFF (.tif)") }
            if(exportJob!=null) {LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={exportJob?.cancel()}){Text("Cancel GeoTIFF export")}}
            exportZoom?.let { zoom ->
                Text("GeoTIFF ready · ${if(exportReducedDetail)"reduced detail to fit 40 MB" else "full saved detail"} · zoom $zoom · ${String.format(java.util.Locale.ROOT,"%.1f",exportBytes/1_000_000.0)} MB")
                if(exportBytes>40_000_000) Text("CalTopo documents a 40 MB Map Sheet upload limit. This full-resolution export is larger and may be rejected.")
                TextButton(enabled=!busy,onClick={share("map.tif","image/tiff")}){Text("Share GeoTIFF (.tif)")}
            }
            if(local.archiveAvailable) TextButton(onClick={share("share.zip","application/zip")}) { Text("Share this layer (.zip)") }
            if(local.hasOriginals) TextButton(onClick={browsingOriginals=true},modifier=Modifier.testTag("mapping-browse-originals")) { Text("Browse original photos") }
            else Text("Original photos are not stored on this device for this result.",style=MaterialTheme.typography.bodySmall)
        } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("GeoTIFF uses lossless compression and preserves transparent gaps. Leave the limit unchecked for full saved detail. When checked, the highest saved resolution whose compressed file fits 40 MB is used. In CalTopo, choose + Add → Map Sheet → Upload New.")
        Text("The ZIP contains this result’s 2D tiles, 3D data when available, and metadata. Original flight photos are shared separately.")
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Done")}},dismissButton={TextButton(enabled=!busy,onClick={
        busy=true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                MappingLayer.save(context,layer.incidentId,MappingLayer.load(context,layer.incidentId).filterNot{it.id==layer.id})
                MappingLayer.visibility(context,layer.incidentId,layer.id,false)
                LocalMappingTiles.directory(context,layer.id).deleteRecursively()
            } }.onSuccess { onDeleted();onDismiss() }.onFailure { error=it.message }
            busy=false
        }
    }){Text("Delete layer from this tablet")}})
    if(sharingSurfaces) org.ncssar.rid2caltopo.ui.AlertDialog(onDismissRequest={sharingSurfaces=false},title={Text("3D surfaces")},text={
        Column { files?.surfaces.orEmpty().forEachIndexed { index,path -> TextButton(onClick={share(path,"model/gltf-binary")}) { Text("Share region ${index+1} (.glb)") } } }
    },confirmButton={TextButton(onClick={sharingSurfaces=false}){Text("Done")}})
}
