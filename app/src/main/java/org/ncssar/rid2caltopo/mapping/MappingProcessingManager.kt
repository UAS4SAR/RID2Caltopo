package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class MappingProcessingState(val missionId:String,val title:String,val active:Boolean=true,val stage:String="Preparing local map",
    val progress:Double=0.0,val remainingSeconds:Long?=null,val layer:MappingLayer?=null,val error:String?=null,val surfacePreviewId:String?=null,val elapsedSeconds:Long=0,val activityAtNanos:Long=System.nanoTime())

/** One bounded, cancellable local job. Completed layers survive app/server restarts. */
object MappingProcessingManager {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val current=MutableStateFlow<MappingProcessingState?>(null)
    val state=current.asStateFlow()
    private var job:Job?=null
    @Synchronized fun start(context:Context,id:String,expectedPhotos:Int?=null,completionSource:String="operator_selected_verified_photos",engine:String="flat",selectedDigests:List<String>?=null) {
        MappingFlightDeletion.requireAvailable()
        require(UUID.fromString(id).toString()==id)
        require(engine in listOf("flat","rtk-surface"))
        if(current.value?.active==true){require(current.value?.missionId==id){"Another map is already being processed."};return}
        require(job?.isCompleted!=false){"The previous job is finishing. Try again in a moment."}
        val selection=selectedDigests?.toList()?.also { values ->
            require(values.isNotEmpty()&&values.distinct().size==values.size){"Choose a nonempty photo selection."}
            values.forEach(MappingPhotoStore::checkDigest)
        }
        val app=context.applicationContext;val root=MappingVolume.directory(app,"mapping-missions",id)
        val mission=JSONObject(File(root,"mission.json").readText())
        require(mission.getString("profile")=="daylight-rgb"){"Select a visible-wide photo flight for local stitching."}
        MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",true).put("stage","Preparing local map").put("progress",0.0).put("updatedAt",Instant.now().toString()))
        current.value=MappingProcessingState(id,mission.getString("title"))
        val nextJob=mappingJob(scope,onStopped={finished,cause->
            // A job cancelled before its first dispatch never enters the worker's try/finally.
            if(job===finished&&current.value?.missionId==id&&current.value?.active==true)
                stopped(root,id,mission.getString("title"),cause ?: IllegalStateException("Local processing stopped; originals retained"))
        }) {
            var lease:String?=null;var wake:PowerManager.WakeLock?=null
            // A stable work id resumes this mission/engine/selection without touching other jobs.
            val workKey=id+engine+(selection?.sorted()?.joinToString() ?: "all")
            val workId=UUID.nameUUIDFromBytes(workKey.toByteArray()).toString()
            val layerId=UUID.randomUUID().toString()
            val scratch=MappingVolume.directory(app,"mapping-processing",workId)
            val started=System.nanoTime();var lastSaved=0L;var budgetChecked=0L
            fun check(){
                ensureActive()
                // Native geometry reports frequently. Poll storage on the existing budget
                // interval, not once per triangle; cancellation remains immediate.
                if(System.nanoTime()-budgetChecked>30_000_000_000L){
                    MappingVolume.checkBudget(app)
                    require(scratch.usableSpace>MappingPhotoStore.FREE_SPACE_RESERVE){"Tablet storage is full. Originals are retained."}
                    budgetChecked=System.nanoTime()
                }
            }
            fun progress(stage:String,fraction:Double){
                val now=System.nanoTime();if(now-lastSaved<1_000_000_000L&&fraction<1)return
                lastSaved=now
                val elapsed=(now-started)/1_000_000_000
                // Stereo, decoding and tile export have different costs; checkpoint jumps make
                // a linear whole-job ETA particularly misleading. Show elapsed time for 3D.
                val remaining=if(engine=="flat"&&fraction>0.08)(elapsed*(1-fraction)/fraction).toLong() else null
                val next=MappingProcessingState(id,mission.getString("title"),stage=stage,progress=fraction,remainingSeconds=remaining,elapsedSeconds=elapsed,
                    surfacePreviewId=if(File(scratch,"surface/surface.glb").isFile)workId else null)
                current.value=next
                MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",true).put("stage",stage).put("progress",fraction).put("remainingSeconds",remaining ?: JSONObject.NULL).put("elapsedSeconds",elapsed).put("updatedAt",Instant.now().toString()))
                MappingProcessingForegroundService.update(app,next)
            }
            try {
            MappingPhotoStore.writeJson(File(scratch,"work-owner.json"),JSONObject().put("missionId",id).put("startedAt",Instant.now().toString()))
                ensureActive();lease=MappingWorkRuntime.begin(::cancel,survivesTaskRemoval=true)
                wake=(app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"R2C:LocalMapping").apply{setReferenceCounted(false);acquire()}
                ContextCompat.startForegroundService(app,Intent(app,MappingProcessingForegroundService::class.java))
                val store=MappingPhotoStore(root);require(selection!=null||store.inventory().getInt("pendingCount")==0){"Some originals are still transferring. Wait for them to finish."}
                val available=File(root,"inventory").listFiles().orEmpty().filter{it.extension=="json"}.map{JSONObject(it.readText())}.filter{it.optBoolean("complete")}.sortedBy{it.getString("name")}
                val records=if(selection==null)available else {
                    val byDigest=available.associateBy{it.getString("sha256")}
                    selection.map{byDigest[it] ?: throw IllegalArgumentException("A selected original is missing or incomplete.")}
                }
                require(expectedPhotos==null||records.size==expectedPhotos){"Verified photo count changed. Review the current flight before processing."}
                require(records.size in 3..(if(engine=="rtk-surface")MappingVolume.MAX_PHOTOS else 200)){"Choose 3–10,000 visible originals for RTK reconstruction (flat preview supports 200)."}
                if(records.size>200)require(MappingVolume.card(app)!=null && scratch.canonicalPath.startsWith(MappingVolume.card(app)!!.canonicalPath+"/")){"Insert the mapping microSD card before processing this flight."}
                val digests=records.mapIndexed{index,record->
                    check();progress("Checking original ${index+1}/${records.size}",0.04*index/records.size)
                    val digest=record.getString("sha256");MappingPhotoStore.checkDigest(digest)
                    require(store.progress(digest).getBoolean("complete")){"A selected original is missing or incomplete."}
                    val file=File(root,"originals/$digest.jpg")
                    require(MappingFileProgress.sha(file,::check){bytes->progress("Checking original ${index+1}/${records.size} · ${MappingFileProgress.label(bytes,file.length())}",0.04*index/records.size)}==digest){"Original checksum changed. Re-import this photo before processing."}
                    require(DjiOriginalCamera.matches(file,mission)){"A selected photo does not match the mission camera and focal length."};digest
                }
                MappingPhotoStore.writeJson(File(root,"processing-input.json"),JSONObject().put("sha256",JSONArray(digests)).put("completionSource",completionSource).put("selectedAt",Instant.now().toString()))
                val report=if(engine=="rtk-surface") {
                    if(MappingRegionProcessor.needsRegions(mission,digests.size))MappingRegionProcessor.run(app,mission,digests,root,scratch,::progress,::check)
                    else MappingSurfaceStitcher.run(app,MappingRegionProcessor.singleMission(mission),digests,root,scratch,::progress,::check)
                }
                    else MappingStitcher.run(app,mission,digests,root,scratch,::progress,::check)
                check();progress("Preparing the map for viewing and sharing",0.96)
                report.put("originalSha256",JSONArray(digests)).put("type","r2c_mapping_layer").put("version",1).put("tileScheme","xyz").put("layerId",layerId)
                    .put("assignmentName",mission.getString("title")).put("missionId",id).put("cameraProfileId",mission.optString("cameraProfileId")).put("incidentId",mission.getString("incidentId")).put("assignmentId",mission.getString("assignmentId"))
                    .put("title",mission.getString("title")+" · Visible"+(if(report.optString("reconstructionMode")=="approximate-plane")" · Approximate photo map" else if(engine=="rtk-surface")if(report.optString("positioningMode")=="gnss-assisted")" · GNSS-assisted surface" else " · RTK surface" else "")+" · "+report.getString("captureTimeLocal"))
                    .put("tileRoot","mapping-layers/$layerId").put("tileUrlTemplate","http://127.0.0.1:8765/local-layer/$layerId/tiles/{Z}/{X}/{Y}.png")
                    .put("processedAt",Instant.now().toString()).put("processingSeconds",(System.nanoTime()-started)/1_000_000_000)
                val indices=LocalMappingTiles.indices(scratch);require(indices.isNotEmpty()){ "No viewable map tiles were generated." }
                report.put("tileCount",indices.size)
                MappingPhotoStore.writeJson(File(scratch,"layer.json"),report)
                val layer=MappingLayer.parse(report)
                val entries=JSONArray();val surfaceEntries=JSONArray()
                val manifest=JSONObject().put("format","rid2caltopo_mutual_aid_package").put("version",1).put("generated",Instant.now().toString())
                    .put("package_name",layer.title).put("source_org","").put("profile_enc","").put("aol_entries",JSONArray()).put("dem_entries",JSONArray()).put("mapping_layers",JSONArray(listOf(layer.json())))
                ZipOutputStream(File(scratch,"share.zip").outputStream().buffered()).use{zip->
                    indices.forEachIndexed{tileNumber,index->check();progress("Packaging 2D tiles · ${tileNumber+1}/${indices.size}",.96+.02*tileNumber/indices.size);val z=MapTileIndex.getZoom(index);val x=MapTileIndex.getX(index);val y=MapTileIndex.getY(index);val name="mapping-layers/$layerId/$z/$x/$y.png"
                        zip.putNextEntry(ZipEntry(name));File(scratch,"$z/$x/$y.png").inputStream().use{it.copyTo(zip)};zip.closeEntry()
                        entries.put(JSONObject().put("source","R2C-Mapping-$layerId").put("z",z).put("x",x).put("y",y).put("path",name).put("expires_at_epoch_ms",0))}
                    val models=MappingSurfaceFiles.files(File(scratch,"surface"))
                    models.forEachIndexed{modelNumber,file->
                        check();val name="mapping-layers/$layerId/surface/${file.relativeTo(File(scratch,"surface")).invariantSeparatorsPath}";zip.putNextEntry(ZipEntry(name));file.inputStream().use{input->MappingFileProgress.transfer(input,zip,::check){bytes->progress("Packaging 3D files · ${modelNumber+1}/${models.size} · ${MappingFileProgress.label(bytes,file.length())}",.98)}};zip.closeEntry()
                        surfaceEntries.put(JSONObject().put("layerId",layerId).put("path",name).put("bytes",file.length()).put("sha256",MappingFileProgress.sha(file,::check){bytes->progress("Verifying 3D package · ${modelNumber+1}/${models.size} · ${MappingFileProgress.label(bytes,file.length())}",.99)}))}
                    manifest.put("surface_entries",surfaceEntries)
                    manifest.put("tile_entries",entries);zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
                }
                check();val destination=MappingVolume.directory(app,"mapping-layers",layerId)
                require(destination.listFiles().orEmpty().isEmpty()) { "This result already exists. Open it in Local maps." }
                val target=destination.canonicalFile;require(target.delete())
                require(scratch.canonicalFile.renameTo(target)){"Cannot publish the completed map."}
                if(runCatching{android.system.Os.readlink(scratch.path)}.isSuccess)scratch.delete()
                // Checkpoints are redundant only after the complete result is published.
                File(destination,"regions").deleteRecursively()
                MappingLayer.visibility(app,layer.incidentId,layer.id,true)
                MappingLayer.save(app,layer.incidentId,MappingLayer.load(app,layer.incidentId).filterNot{it.id==layer.id}+layer)
                MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",false).put("stage","Map ready").put("layerId",layerId).put("photoCount",digests.size).put("progress",1.0).put("updatedAt",Instant.now().toString()))
                current.value=MappingProcessingState(id,mission.getString("title"),active=false,stage="Map ready",progress=1.0,layer=layer,surfacePreviewId=if(layer.surfaceModel.isNotBlank())layer.id else null)
            }catch(e:Exception){
                stopped(root,id,mission.getString("title"),e)
            }finally{
                // Keep completed region checkpoints after cancellation, failure or process death.
                runCatching{wake?.let{if(it.isHeld)it.release()}}
                runCatching{app.stopService(Intent(app,MappingProcessingForegroundService::class.java))}
                lease?.let(MappingWorkRuntime::finish)
            }
        }
        job=nextJob
        nextJob.start()
    }
    private fun stopped(root:File,id:String,title:String,error:Throwable) {
        val cancelled=error is CancellationException&&error.message=="Processing cancelled; originals retained"
        val message=if(error is CancellationException)if(cancelled)error.message!! else "Processing interrupted; originals retained"
            else error.message ?: "Local processing stopped; originals retained"
        current.value=MappingProcessingState(id,title,active=false,stage=message,error=if(cancelled)null else message)
        runCatching{MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",false).put("stage",message)
            .put("cancelled",cancelled).put("error",if(cancelled)JSONObject.NULL else message).put("updatedAt",Instant.now().toString()))}
    }
    internal fun requireIdleForDeletion() {
        check(current.value?.active != true && job?.isCompleted != false) { "Wait for reconstruction to finish or finish cancelling before deleting." }
    }
    @Synchronized fun cancel(){job?.cancel(CancellationException("Processing cancelled; originals retained"))}
    @Synchronized fun interrupt(){job?.cancel(CancellationException("Processing interrupted by Android; originals retained"))}
    fun dismiss(){if(current.value?.active!=true)current.value=null}
    @Synchronized internal fun maybeStartCompleted(context:Context,id:String,mission:JSONObject){
        val root=File(context.filesDir,"mapping-missions/$id")
        if(mission.optString("profile")!="daylight-rgb"||current.value?.active==true||job?.isCompleted==false)return
        val group=runCatching{JSONObject(File(root,"cloud/group-upload.json").readText())}.getOrNull() ?: return
        if(!group.optBoolean("ready"))return
        val selection=MappingFlightRepository.completionDigests(root)
        if(selection.size !in 3..MappingVolume.MAX_PHOTOS||selection.size!=group.optInt("verifiedCount"))return
        if(!MappingAutomaticReconstruction.isNewSelection(root,selection))return
        runCatching{
            start(context,id,expectedPhotos=selection.size,selectedDigests=selection,
                completionSource="pilot_group_complete",engine="rtk-surface")
            runCatching{MappingAutomaticReconstruction.record(root,selection)}
                .onFailure{android.util.Log.w("MappingProcessing","Could not persist automatic selection",it)}
        }.onFailure{error->
            current.value=MappingProcessingState(id,mission.getString("title"),active=false,
                stage="Originals retained. Automatic reconstruction could not start.",error=error.message)
        }
    }
    private fun sha(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val block=ByteArray(65536);while(true){val n=input.read(block);if(n<0)break;hash.update(block,0,n)}}
        return LocalS3.hex(hash.digest())
    }
}
