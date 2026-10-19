package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DjiCloudSession(val missionId:String,val title:String,val url:String,val accessCode:String,val fingerprint:String,
    val importedCertificate:Boolean,val expiresAt:Long,val status:String="Waiting for Pilot 2",val photos:Int=0,val bytes:Long=0,
    val unmatched:Int=0,val flags:Map<String,Boolean> = emptyMap(),val active:Boolean=true,val estimatedPhotos:Int=0,val estimatedSeconds:Int=0,
    val httpDiagnostic:Boolean=false,val heightMeters:Double=0.0,val returnHomeHeightMeters:Double=0.0,val groundResolutionCmPerPixel:Double=0.0,
    val storedUploadCount:Int=0,val callbackFailures:Int=0,val captureDescription:String="")

object DjiCloudManager {
    private var server:DjiCloudServer?=null
    private var generation:String?=null
    private val draining=mutableMapOf<String,MutableList<DjiCloudServer>>()
    private var app:Context?=null
    private var wake:PowerManager.WakeLock?=null
    private var workToken:String?=null
    private val current=MutableStateFlow<DjiCloudSession?>(null)
    val session=current.asStateFlow()
    @Synchronized fun start(context:Context,mission:MappingMission,profile:MappingResolution.Profile,gsd:Double,httpDiagnostic:Boolean=false,capture:MappingCaptureProfile.Profile=MappingCaptureProfile.nadir,canopyHeightMeters:Double=20.0,maximumObstacleHeightMeters:Double?=null,clearanceMeters:Double=15.0,maximumCaptureSpeedMetersPerSecond:Double?=null,transitSpeedMetersPerSecond:Double?=null) = startRecord(context,mission.json(profile,gsd,capture,canopyHeightMeters,maximumObstacleHeightMeters,clearanceMeters,maximumCaptureSpeedMetersPerSecond,transitSpeedMetersPerSecond).put("processLocally",!profile.thermal),httpDiagnostic)
    @Synchronized fun resume(context:Context,id:String,httpDiagnostic:Boolean?=null) {
        require(UUID.fromString(id).toString()==id)
        val root=File(context.filesDir,"mapping-missions/$id")
        startRecord(context,JSONObject(File(root,"mission.json").readText()),httpDiagnostic ?: savedHttpDiagnostic(root))
    }
    internal fun savedHttpDiagnostic(root:File):Boolean=runCatching{
        JSONObject(File(root,"cloud/session-transport.json").readText()).opt("httpDiagnostic")==true
    }.getOrDefault(false)
    private fun startRecord(context:Context,record:JSONObject,httpDiagnostic:Boolean=false) {
        MappingFlightDeletion.requireAvailable()
        val application=context.applicationContext
        val credential=DjiCloudCredentialStore.load(application)
        val route=DjiMappingRoute(record)
        val routeBytes=route.kmz() // Validate qualification/limits before creating or changing a mission.
        val host=MappingTransferManager.lanAddress(application)
        val tls=if(httpDiagnostic)null else DjiCloudTlsStore.load(application,host)
        val id=record.getString("missionId");require(UUID.fromString(id).toString()==id)
        val root=MappingVolume.directory(application,"mapping-missions",id,route.photoEstimate>200)
        MappingVolume.checkBudget(application,MappingStorageEstimate.plan(record,route.photoEstimate).peakAdditionalBytes)
        val saved=File(root,"mission.json")
        if(saved.exists()) {
            val old=JSONObject(saved.readText());val inventory=MappingPhotoStore(root).inventory()
            if(inventory.getInt("photoCount")+inventory.getInt("pendingCount")>0) require(old.getString("cameraProfileId")==record.getString("cameraProfileId") && old.getJSONObject("parameters").toString()==record.getJSONObject("parameters").toString() &&
                old.optJSONObject("captureProfile")?.toString()==record.optJSONObject("captureProfile")?.toString() &&
                old.getJSONObject("cameraSpecifications").toString()==record.getJSONObject("cameraSpecifications").toString()) {
                "This flight already has photos. Reopen the assignment to define a new flight with different settings."
            }
        }
        MappingPhotoStore.writeJson(saved,record)
        File(root,"mission.kmz").writeBytes(routeBytes)
        MappingPhotoStore.writeJson(File(root,"route-summary.json"),route.summary())
        MappingTransferManager.stop();stop();app=application
        val run=UUID.randomUUID().toString();generation=run
        try {
            val next=DjiCloudServer(host,tls?.host ?: host,tls?.factory ?: javax.net.ServerSocketFactory.getDefault(),record,credential,root,{name->application.assets.open("dji-cloud/$name").use{it.readBytes()}},changed={snapshot->
                synchronized(this){ if(generation==run) {
                    val flags=snapshot.optJSONObject("flags") ?: JSONObject()
                    current.value=current.value?.copy(status=snapshot.getString("status"),photos=snapshot.getInt("photoCount"),bytes=snapshot.getLong("storedBytes"),
                        unmatched=snapshot.optInt("unmatchedCount"),flags=flags.keys().asSequence().associateWith{flags.getBoolean(it)},active=!snapshot.getString("status").startsWith("Native connection expired"),
                        storedUploadCount=snapshot.optInt("storedUploadCount"),callbackFailures=snapshot.optJSONObject("transfer")?.optInt("callbackFailures") ?: 0)
                    if(current.value?.active==false) stopResources() else DjiCloudForegroundService.update(application,current.value)
                    MappingProcessingManager.maybeStartCompleted(application,id,record)
                } }
            })
            server=next
            val inventory=next.platform.snapshot()
            current.value=DjiCloudSession(id,record.getString("title"),next.baseUrl+"/cloud",next.code,tls?.fingerprint.orEmpty(),tls?.imported==true,next.expiresAt,
                photos=inventory.getInt("photoCount"),bytes=inventory.getLong("storedBytes"),estimatedPhotos=route.photoEstimate,estimatedSeconds=route.durationEstimateSeconds,httpDiagnostic=httpDiagnostic,
                captureDescription=route.passes.joinToString("; "){"${it.pitchDegrees.toInt()}° pitch / ${it.directionDegrees.toInt()}° grid / ${it.forwardOverlapPercent.toInt()}% forward, ${it.sideOverlapPercent.toInt()}% side"},
                heightMeters=route.heightMeters,returnHomeHeightMeters=route.returnHomeHeightMeters,groundResolutionCmPerPixel=route.groundResolutionCmPerPixel,
                storedUploadCount=inventory.optInt("storedUploadCount"),callbackFailures=inventory.optJSONObject("transfer")?.optInt("callbackFailures") ?: 0,
                unmatched=inventory.optInt("unmatchedCount"))
            workToken=MappingWorkRuntime.begin(::stop)
            wake=(application.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"R2C:Pilot2Platform").apply{setReferenceCounted(false);acquire((next.expiresAt-System.currentTimeMillis()).coerceAtLeast(1))}
            ContextCompat.startForegroundService(application,Intent(application,DjiCloudForegroundService::class.java))
            next.start()
            MappingPhotoStore.writeJson(File(root,"cloud/session-transport.json"),JSONObject().put("httpDiagnostic",httpDiagnostic))
            // Recovery can complete a retained transfer before callbacks are installed.
            MappingProcessingManager.maybeStartCompleted(application,id,record)
        }catch(error:Exception){stop();throw error}
    }
    /** Called with the deletion reservation held; never wait for workers under this lock. */
    @Synchronized internal fun stopForDeletion(missionId:String):List<DjiCloudServer> {
        if(current.value?.missionId==missionId) stop()
        return draining[missionId]?.toList().orEmpty()
    }
    @Synchronized internal fun deletionDrained(missionId:String,connections:List<DjiCloudServer>) {
        draining[missionId]?.removeAll(connections.toSet())
        if(draining[missionId]?.isEmpty()==true)draining.remove(missionId)
    }
    @Synchronized fun stop() {generation=null;stopResources();current.value=current.value?.copy(active=false,status="Native connection closed; originals retained locally")}
    private fun stopResources() {
        draining.values.forEach { connections->connections.removeAll { it.isDrained } }
        draining.entries.removeAll { it.value.isEmpty() }
        server?.let { connection->
            current.value?.missionId?.let { id->draining.getOrPut(id){mutableListOf()}.add(connection) }
            connection.close()
        };server=null;wake?.let{if(it.isHeld)it.release()};wake=null
        app?.stopService(Intent(app,DjiCloudForegroundService::class.java))
        val token=workToken;workToken=null;token?.let(MappingWorkRuntime::finish)
    }
}
