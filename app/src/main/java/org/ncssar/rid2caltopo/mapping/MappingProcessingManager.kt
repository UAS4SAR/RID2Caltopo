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
    val progress:Double=0.0,val remainingSeconds:Long?=null,val layer:MappingLayer?=null,val error:String?=null,val surfacePreviewId:String?=null,val elapsedSeconds:Long=0,val activityAtNanos:Long=System.nanoTime(),
    val estimateSummary:String?=null)

/** One bounded, cancellable local job. Completed layers survive app/server restarts. */
object MappingProcessingManager {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default+org.ncssar.rid2caltopo.app.StartupCoroutineSafety.handler)
    private val current=MutableStateFlow<MappingProcessingState?>(null)
    val state=current.asStateFlow()
    private var job:Job?=null
    @Volatile private var activeWorkId:String?=null
    @Volatile private var activeLayerId:String?=null
    @Volatile private var testProtectedIds:Set<String> = emptySet()
    internal fun protectedMappingIds():Set<String> = listOfNotNull(activeWorkId,activeLayerId).toSet()+testProtectedIds
    internal fun withProtectedIdsForTest(ids:Set<String>,block:()->Unit){
        testProtectedIds=ids;try{block()}finally{testProtectedIds=emptySet()}
    }

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
        val app=context.applicationContext
        // Publish UI state immediately; Archive verify / volume prep runs inside the job.
        val missionFile=File(app.filesDir,"mapping-missions/$id/mission.json")
        val mission=runCatching{JSONObject(missionFile.readText())}.getOrElse{
            throw IllegalArgumentException("Mission metadata unreadable; originals retained")
        }
        require(mission.getString("profile")=="daylight-rgb"){"Select a visible-wide photo flight for local stitching."}
        val title=mission.getString("title")
        MappingLayerRecovery.ensureScheduled(app)
        val statusRoot=File(app.filesDir,"mapping-missions/$id")
        MappingPhotoStore.writeJson(File(statusRoot,"processing-status.json"),JSONObject().put("active",true).put("stage","Preparing local map").put("progress",0.0)
            .put("engine",engine).put("completionSource",completionSource)
            .put("selectedDigests",selection?.let{JSONArray(it)} ?: JSONObject.NULL)
            .put("updatedAt",Instant.now().toString()))
        current.value=MappingProcessingState(id,title,stage="Preparing local map",progress=0.0)
        val nextJob=mappingJob(scope,onStopped={finished,cause->
            // A job cancelled before its first dispatch never enters the worker's try/finally.
            if(job===finished&&current.value?.missionId==id&&current.value?.active==true)
                stopped(statusRoot,id,title,cause ?: IllegalStateException("Local processing stopped; originals retained"))
        }) {
            var lease:String?=null;var wake:PowerManager.WakeLock?=null
            val root=MappingVolume.directory(app,"mapping-missions",id)
            // A stable work id resumes this mission/engine/selection without touching other jobs.
            val workKey=id+engine+(selection?.sorted()?.joinToString() ?: "all")
            val workId=UUID.nameUUIDFromBytes(workKey.toByteArray()).toString()
            val layerId=UUID.randomUUID().toString()
            activeWorkId=workId;activeLayerId=layerId
            val scratch=MappingVolume.directory(app,"mapping-processing",workId)
            val scratchRoot=scratch.canonicalFile
            var budgetRoot=scratchRoot
            val started=System.nanoTime();var lastSaved=0L;var budgetChecked=0L
            var lastProgressLog=0L;var lastPhase:String?=null;var phaseStarted=started
            var photoCountForWeights=selection?.size ?: expectedPhotos ?: 50
            var phases=MappingProgressPhases.tracker(engine, photoCountForWeights, MappingProgressPhases.Costs.load(app.filesDir))
            fun ensureCancel(){ ensureActive() }
            fun checkBudget(){
                ensureCancel()
                // Native geometry reports frequently. Poll storage on the existing budget
                // interval, not once per triangle; cancellation remains immediate.
                if(System.nanoTime()-budgetChecked>30_000_000_000L){
                    MappingStorageBudget.checkExisting(budgetRoot, bucket="Mapping work space")
                    budgetChecked=System.nanoTime()
                }
            }
            // Stitchers still call check(); keep budget while scratch exists.
            fun check()=checkBudget()
            fun log(level:String,message:String){
                if(level=="warn") org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn("MappingProcessing",message)
                else org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("MappingProcessing",message)
            }
            fun reportPhase(phase:String,local:Double?,unitIndex:Int?=null,unitTotal:Int?=null,stageOverride:String?=null){
                val now=System.nanoTime()
                val phaseElapsedMs=(now-phaseStarted)/1_000_000
                val estimated=phases.estimatedPhaseMs(phase)
                val timeHint=if(local==null && estimated>1.0) (phaseElapsedMs.toDouble()/estimated).coerceIn(0.0,0.85) else null
                val fraction=phases.overall(phase,local,timeHint)
                val stage=stageOverride ?: phases.stage(phase,unitIndex,unitTotal)
                if(phase!=lastPhase){
                    val ended=lastPhase
                    if(ended!=null) {
                        val endedMs=(now-phaseStarted)/1_000_000
                        val w=phases.weight(ended)
                        log("debug","phase end id=$ended N=$photoCountForWeights durationMs=$endedMs weight=${"%.4f".format(java.util.Locale.US,w)}")
                        MappingProgressPhases.Costs.record(app.filesDir,ended,endedMs,photoCountForWeights)
                    }
                    log("debug","phase start id=$phase photos=$photoCountForWeights")
                    lastPhase=phase;phaseStarted=now;lastProgressLog=now
                } else if(now-lastProgressLog>15_000_000_000L){
                    log("debug","phase progress id=$phase fraction=${"%.3f".format(java.util.Locale.US,fraction)} elapsedMs=${(now-started)/1_000_000} photos=$photoCountForWeights")
                    lastProgressLog=now
                }
                // Persist at least every few seconds so long stages never sit silent.
                if(now-lastSaved<2_000_000_000L&&fraction<1&&local!=null)return
                if(now-lastSaved<1_000_000_000L&&fraction<1&&local==null)return
                lastSaved=now
                val elapsed=(now-started)/1_000_000_000
                val remaining=phases.etaSeconds(fraction,now)
                val next=MappingProcessingState(id,title,stage=stage,progress=fraction,remainingSeconds=remaining,elapsedSeconds=elapsed,
                    surfacePreviewId=if(File(budgetRoot,"surface/surface.glb").isFile)workId else null,estimateSummary=current.value?.estimateSummary)
                current.value=next
                MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",true).put("stage",stage).put("progress",fraction).put("remainingSeconds",remaining ?: JSONObject.NULL).put("elapsedSeconds",elapsed)
                    .put("estimate",current.value?.estimateSummary?.let{JSONObject().put("summary",it)} ?: JSONObject.NULL).put("updatedAt",Instant.now().toString()))
                MappingProcessingForegroundService.update(app,next)
            }
            fun progress(stage:String,fraction:Double){
                // Stitcher absolute fractions are remapped into phase bands; optimizer cost never drives %.
                // Matching photo seams: JNI passes a real 0..1 local fraction as the second arg.
                when {
                    stage.startsWith("Matching photo seams") || stage.contains("Matching photo seams") -> {
                        val local=fraction.coerceIn(0.0,1.0)
                        val pct=(local*100).toInt()
                        reportPhase(MappingProgressPhases.MATCH_SEAMS,local,stageOverride="${phases.title(MappingProgressPhases.MATCH_SEAMS)} · $pct%")
                    }
                    stage.startsWith("Checking original") || stage.startsWith("Reading photo") -> {
                        val match=Regex("""(\d+)/(\d+)""").find(stage)
                        val i=match?.groupValues?.get(1)?.toIntOrNull();val n=match?.groupValues?.get(2)?.toIntOrNull()
                        if(i!=null&&n!=null) reportPhase(MappingProgressPhases.CHECK_ORIGINALS,(i-1).coerceAtLeast(0).toDouble()/n,i,n,stage)
                        else reportPhase(MappingProgressPhases.CHECK_ORIGINALS,fraction.coerceIn(0.0,1.0),stageOverride=stage)
                    }
                    stage.startsWith("Preparing original image colors") || stage.startsWith("Preparing image pixels") || stage.startsWith("Coloring the 3D") -> {
                        val match=Regex("""(\d+)/(\d+)""").find(stage)
                        val i=match?.groupValues?.get(1)?.toIntOrNull();val n=match?.groupValues?.get(2)?.toIntOrNull()
                        if(i!=null&&n!=null) reportPhase(MappingProgressPhases.PREPARE_COLORS,(i-1).coerceAtLeast(0).toDouble()/n,i,n,stage)
                        else reportPhase(MappingProgressPhases.PREPARE_COLORS,null,stageOverride=stage)
                    }
                    stage.startsWith("Projecting") || stage.startsWith("Stitching map tile") -> {
                        val match=Regex("""(\d+)/(\d+)""").find(stage)
                        val i=match?.groupValues?.get(1)?.toIntOrNull();val n=match?.groupValues?.get(2)?.toIntOrNull()
                        if(i!=null&&n!=null) reportPhase(MappingProgressPhases.PROJECT_TILES,i.toDouble()/n,i,n,stage)
                        else reportPhase(MappingProgressPhases.PROJECT_TILES,null,stageOverride=stage)
                    }
                    stage.startsWith("Building map zoom") ->
                        reportPhase(MappingProgressPhases.ZOOM_LEVELS,(fraction-0.86).coerceIn(0.0,1.0)/0.08,stageOverride=stage)
                    stage.startsWith("Packaging") || stage.startsWith("Verifying 3D") || stage.startsWith("Preparing the map") -> {
                        val match=Regex("""(\d+)/(\d+)""").find(stage)
                        val i=match?.groupValues?.get(1)?.toIntOrNull();val n=match?.groupValues?.get(2)?.toIntOrNull()
                        if(i!=null&&n!=null) reportPhase(MappingProgressPhases.PACKAGE,i.toDouble()/n,i,n,stage)
                        else reportPhase(MappingProgressPhases.PACKAGE,null,stageOverride=stage)
                    }
                    stage.startsWith("Finding image detail") || stage.startsWith("Aligning") || stage.startsWith("Refining") ||
                        stage.contains("reconstruct",true) || stage.startsWith("3D surface ready") ->
                        reportPhase(MappingProgressPhases.RECONSTRUCT,null,stageOverride=stage)
                    else -> reportPhase(MappingProgressPhases.RECONSTRUCT,null,stageOverride=stage)
                }
            }
            try {
            MappingPhotoStore.writeJson(File(scratch,"work-owner.json"),JSONObject().put("missionId",id).put("startedAt",Instant.now().toString()))
                ensureCancel();lease=MappingWorkRuntime.begin(::cancel,survivesTaskRemoval=true)
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
                photoCountForWeights=records.size
                phases=MappingProgressPhases.tracker(engine, photoCountForWeights, MappingProgressPhases.Costs.load(app.filesDir))
                val estimate=MappingStorageEstimate.plan(mission,records.size)
                val layersRoot=File(app.filesDir,"mapping-layers").apply{mkdirs()}
                val assessment=MappingStorageEstimate.assess(app,estimate,root,scratchRoot,layersRoot)
                val estimateText=assessment.summary()
                MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",true).put("stage","Preparing local map").put("progress",0.0)
                    .put("estimate",assessment.json()).put("updatedAt",Instant.now().toString()))
                current.value=MappingProcessingState(id,title,stage="Preparing local map",estimateSummary=estimateText)
                log("debug","start missionId=${id.take(8)} photos=${records.size} engine=$engine $estimateText")
                if(assessment.shortages.isNotEmpty()) error(estimateText)
                val digests=records.mapIndexed{index,record->
                    checkBudget();reportPhase(MappingProgressPhases.CHECK_ORIGINALS,index.toDouble()/records.size,index+1,records.size)
                    val digest=record.getString("sha256");MappingPhotoStore.checkDigest(digest)
                    require(store.progress(digest).getBoolean("complete")){"A selected original is missing or incomplete."}
                    val file=File(root,"originals/$digest.jpg")
                    require(MappingFileProgress.sha(file,::checkBudget){bytes->
                        reportPhase(MappingProgressPhases.CHECK_ORIGINALS,index.toDouble()/records.size,index+1,records.size,
                            "Checking originals · ${index+1}/${records.size} · ${MappingFileProgress.label(bytes,file.length())}")
                    }==digest){"Original checksum changed. Re-import this photo before processing."}
                    require(DjiOriginalCamera.matches(file,mission)){"A selected photo does not match the mission camera and focal length."};digest
                }
                MappingPhotoStore.writeJson(File(root,"processing-input.json"),JSONObject().put("sha256",JSONArray(digests)).put("completionSource",completionSource).put("selectedAt",Instant.now().toString()))
                reportPhase(MappingProgressPhases.RECONSTRUCT,null)
                val report=if(engine=="rtk-surface") {
                    if(MappingRegionProcessor.needsRegions(mission,digests.size))MappingRegionProcessor.run(app,mission,digests,root,scratch,::progress,::check)
                    else MappingSurfaceStitcher.run(app,MappingRegionProcessor.singleMission(mission),digests,root,scratch,::progress,::check)
                }
                    else MappingStitcher.run(app,mission,digests,root,scratch,::progress,::check)
                checkBudget();reportPhase(MappingProgressPhases.PACKAGE,0.0,stageOverride=phases.title(MappingProgressPhases.PACKAGE))
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
                    indices.forEachIndexed{tileNumber,index->checkBudget();reportPhase(MappingProgressPhases.PACKAGE,0.5*tileNumber/indices.size,tileNumber+1,indices.size,"Packaging · ${tileNumber+1}/${indices.size}")
                        val z=MapTileIndex.getZoom(index);val x=MapTileIndex.getX(index);val y=MapTileIndex.getY(index);val name="mapping-layers/$layerId/$z/$x/$y.png"
                        zip.putNextEntry(ZipEntry(name));File(scratch,"$z/$x/$y.png").inputStream().use{it.copyTo(zip)};zip.closeEntry()
                        entries.put(JSONObject().put("source","R2C-Mapping-$layerId").put("z",z).put("x",x).put("y",y).put("path",name).put("expires_at_epoch_ms",0))}
                    val models=MappingSurfaceFiles.files(File(scratch,"surface"))
                    models.forEachIndexed{modelNumber,file->
                        checkBudget();val name="mapping-layers/$layerId/surface/${file.relativeTo(File(scratch,"surface")).invariantSeparatorsPath}";zip.putNextEntry(ZipEntry(name));file.inputStream().use{input->MappingFileProgress.transfer(input,zip,::checkBudget){bytes->reportPhase(MappingProgressPhases.PACKAGE,0.5+0.4*modelNumber/models.size.coerceAtLeast(1),modelNumber+1,models.size,"Packaging · ${modelNumber+1}/${models.size} · ${MappingFileProgress.label(bytes,file.length())}")}};zip.closeEntry()
                        surfaceEntries.put(JSONObject().put("layerId",layerId).put("path",name).put("bytes",file.length()).put("sha256",MappingFileProgress.sha(file,::checkBudget){bytes->reportPhase(MappingProgressPhases.PACKAGE,0.9,modelNumber+1,models.size,"Packaging · ${modelNumber+1}/${models.size} · ${MappingFileProgress.label(bytes,file.length())}")}))}
                    manifest.put("surface_entries",surfaceEntries)
                    manifest.put("tile_entries",entries);zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
                }
                checkBudget();val destination=MappingVolume.directory(app,"mapping-layers",layerId)
                require(destination.listFiles().orEmpty().isEmpty()) { "This result already exists. Open it in Local maps." }
                val target=destination.canonicalFile;require(target.delete())
                require(scratch.canonicalFile.renameTo(target)){"Cannot publish the completed map."}
                if(runCatching{android.system.Os.readlink(scratch.path)}.isSuccess)scratch.delete()
                // After rename, budget checks must use the published destination — never the deleted scratch.
                budgetRoot=target
                reportPhase(MappingProgressPhases.PUBLISH,0.0)
                log("debug","publish layerId=${layerId.take(8)} tiles=${indices.size}")
                // Checkpoints are redundant only after the complete result is published.
                File(destination,"regions").deleteRecursively()
                fun markReady(ready:MappingLayer){
                    val ended=lastPhase
                    if(ended!=null) {
                        val endedMs=(System.nanoTime()-phaseStarted)/1_000_000
                        val w=phases.weight(ended)
                        log("debug","phase end id=$ended N=$photoCountForWeights durationMs=$endedMs weight=${"%.4f".format(java.util.Locale.US,w)}")
                        MappingProgressPhases.Costs.record(app.filesDir,ended,endedMs,photoCountForWeights)
                    }
                    MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",false).put("stage","Map ready").put("layerId",layerId).put("photoCount",digests.size).put("progress",1.0).put("updatedAt",Instant.now().toString()))
                    current.value=MappingProcessingState(id,title,active=false,stage="Map ready",progress=1.0,layer=ready,surfacePreviewId=if(ready.surfaceModel.isNotBlank())ready.id else null,estimateSummary=current.value?.estimateSummary)
                }
                try {
                    // Post-publish uses cancel-only; retainTree can take >30s on SD FUSE.
                    org.ncssar.rid2caltopo.storage.OperationalStorage.retainTree(destination) { ensureCancel() }
                    org.ncssar.rid2caltopo.storage.OperationalStorage.remove(scratch)
                    MappingLayer.visibility(app,layer.incidentId,layer.id,true)
                    MappingLayer.save(app,layer.incidentId,MappingLayer.load(app,layer.incidentId).filterNot{it.id==layer.id}+layer)
                    // Select a newly completed result once, here on the processing worker. Replaying
                    // this from MapPane composition can overwrite a later library selection.
                    MappingLayer.selectLocal(app,layer)
                    MappingWorkspaceState.layersChanged()
                    markReady(layer)
                } catch(post:Exception) {
                    // A published layer must not become a storage-full failure.
                    val published=runCatching {
                        MappingLayer.parse(JSONObject(File(destination,"layer.json").readText()))
                    }.getOrNull()?.takeIf{MappingLayer.hasTileManifest(destination)}
                    if(published!=null){
                        log("warn","post-publish err=${post.message} layerId=${layerId.take(8)}")
                        runCatching{MappingLayerRecovery.adoptCompleteOrphans(app)}
                        runCatching{
                            MappingLayer.visibility(app,published.incidentId,published.id,true)
                            MappingLayer.save(app,published.incidentId,MappingLayer.load(app,published.incidentId).filterNot{it.id==published.id}+published)
                        }
                        markReady(published)
                    } else throw post
                }
            }catch(e:Throwable){
                // Errors (e.g. UnsatisfiedLinkError) are not Exception; still clear active and keep originals.
                val msg=e.message.orEmpty()
                if(msg.contains("need ")&&msg.contains("free ")) log("warn","failure $msg")
                else log("warn","failure err=${e.javaClass.simpleName}: ${e.message}")
                stopped(root,id,title,e)
            }finally{
                activeWorkId=null;activeLayerId=null
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

    private val resumeScheduled=java.util.concurrent.atomic.AtomicBoolean(false)
    /** After process death, re-queue the active job from processing-status + processing-input. Off UI thread. */
    fun ensureResume(context:Context) {
        if(!resumeScheduled.compareAndSet(false,true))return
        val app=context.applicationContext
        scope.launch {
            runCatching { resumeInterruptedJobs(app) }
                .onFailure{org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn("MappingProcessing","resume failed err=${it.message}")}
        }
    }

    /** Clear crash residue (active=true, no live job) so hydrate is not postponed and UI is not "running". */
    private fun resumeInterruptedJobs(context:Context) {
        if(current.value?.active==true||job?.isCompleted==false)return
        val root=File(context.filesDir,"mapping-missions")
        for(dir in root.listFiles().orEmpty().filter{it.isDirectory}) {
            val statusFile=File(dir,"processing-status.json")
            val status=runCatching{JSONObject(statusFile.readText())}.getOrNull() ?: continue
            if(!status.optBoolean("active"))continue
            val id=dir.name
            if(runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false).not())continue
            val title=runCatching{JSONObject(File(dir,"mission.json").readText()).getString("title")}.getOrDefault(id.take(8))
            val message="Processing interrupted; originals retained"
            org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("MappingProcessing","crash residue cleared id=${id.take(8)}")
            runCatching{
                MappingPhotoStore.writeJson(statusFile,JSONObject().put("active",false).put("stage",message)
                    .put("cancelled",false).put("error",message).put("updatedAt",Instant.now().toString()))
            }
        }
    }

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
                .onFailure{org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn("MappingProcessing","Could not persist automatic selection err=${it.message}")}
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
