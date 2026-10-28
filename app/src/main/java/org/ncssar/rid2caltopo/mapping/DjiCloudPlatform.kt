package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Exact Pilot-to-cloud API paths. Workspace and storage access are scoped to one R2C flight. */
class DjiCloudPlatform(val mission: JSONObject, val route: DjiMappingRoute, val directory: File,
    private val credentials: DjiCloudCredentials, val s3: LocalS3,
    private val notify: (String) -> Unit = {}) {
    val workspaceId = mission.getString("missionId")
    val photos = MappingPhotoStore(directory)
    private val flags = ConcurrentHashMap<String,Boolean>()
    private val receipts = File(directory,"cloud/receipts").apply { mkdirs() }
    private val transferFile = File(directory,"cloud/transfer-status.json")
    @Volatile private var transfer = runCatching { JSONObject(transferFile.readText()) }.getOrDefault(JSONObject())
    private val routeFile = File(directory,"mission.kmz").apply { if(!exists()) writeBytes(route.kmz()) else {
        val old=readBytes();val corrected=route.correctingCameraSelection(old)
        if(!old.contentEquals(corrected)) {
            val temporary=File(directory,"mission.kmz.camera-update")
            temporary.writeBytes(corrected)
            check(temporary.renameTo(this)) { "Cannot update the saved route camera selection." }
        }
    }; org.ncssar.rid2caltopo.storage.OperationalStorage.retain(this) }
    @Volatile private var topology = JSONArray()
    @Volatile private var lastStatus = "Waiting for Pilot 2"
    @Volatile private var favorited = false
    var baseUrl = ""
    var token = ""
    var mqttUsername = ""
    var mqttPassword = ""
    var expiresAt = 0L
    init { recoverRetainedOriginals() }

    /** Camera-mismatch receipts already passed the original and mission-tag checks. */
    private fun recoverRetainedOriginals() {
        for(record in receipts.listFiles().orEmpty()) {
            val receipt=runCatching { JSONObject(record.readText()) }.getOrNull() ?: continue
            if(receipt.optBoolean("assigned") || !receipt.optBoolean("isOriginal") || receipt.optString("assignmentReason")!="camera_mismatch")continue
            val file=s3.objectFile(receipt.getString("objectKey"))
            if(!file.isFile || !DjiOriginalCamera.matches(file,mission))continue
            // importOriginal verifies the bytes against the recorded SHA-256 before acceptance.
            photos.importOriginal(receipt.getString("name"),file,receipt.getString("sha256"))
            MappingPhotoStore.writeJson(record,receipt.put("assigned",true).put("assignmentReason","matched"))
        }
        val group=File(directory,"cloud/group-upload.json")
        if(group.isFile)reconcileGroup(JSONObject(group.readText()), notifyStatus=false)
    }
    fun status(message:String) { lastStatus=message; notify(message) }
    fun flag(name:String,value:Boolean) { flags[name]=value; notify(lastStatus) }
    fun snapshot() = photos.inventory().put("flags",JSONObject(flags as Map<*,*>)).put("status",lastStatus)
        .put("route",route.summary()).put("transfer",JSONObject(transfer.toString()))
        .put("storedUploadCount",File(directory,"cloud/storage/objects").listFiles().orEmpty().count{it.extension=="jpg"})
        .put("unmatchedCount",receipts.listFiles().orEmpty().count { runCatching { !JSONObject(it.readText()).optBoolean("assigned") }.getOrDefault(false) })

    /** Persist only bounded operation counters/statuses; never URLs, headers, tokens or payloads. */
    @Synchronized fun recordResponse(request:CloudHttpRequest,response:CloudHttpResponse) {
        val operation=when {
            request.path=="/media/api/v1/workspaces/$workspaceId/upload-callback" -> "callback"
            request.path=="/storage/api/v1/workspaces/$workspaceId/sts" -> "storageCredentials"
            request.path.startsWith("/${s3.bucket}/") && request.method in listOf("PUT","POST") -> "storageWrite"
            else -> return
        }
        val failed=response.status>=400 || response.type=="application/json" && runCatching{JSONObject(response.bytes.toString(Charsets.UTF_8)).optInt("code")!=0}.getOrDefault(false)
        val next=JSONObject(transfer.toString())
        next.put(operation+"Responses",next.optInt(operation+"Responses")+1)
        if(failed)next.put(operation+"Failures",next.optInt(operation+"Failures")+1)
        next.put("lastOperation",operation).put("lastHttpStatus",response.status).put("lastFailed",failed)
        transfer=next
        runCatching{MappingPhotoStore.writeJson(transferFile,next)}
        notify(lastStatus)
    }

    fun bootstrap() = JSONObject().put("license",credentials.bootstrap()).put("workspaceId",workspaceId)
        .put("workspaceName",mission.optString("mapName").ifBlank { mission.getString("title") }).put("assignmentName",mission.getString("title"))
        .put("api",JSONObject().put("host",baseUrl).put("token",token))
        .put("ws",JSONObject().put("host",websocketBase()+"/cloud/ws").put("token",token).put("connectCallback","r2cWsConnected"))
        .put("thing",JSONObject().put("host",websocketBase()+"/cloud/mqtt?x-auth-token=$token").put("username",mqttUsername).put("password",mqttPassword).put("connectCallback","r2cMqttConnected"))
        .put("route",route.summary()).put("expiresAt",expiresAt)
    private fun websocketBase()=baseUrl.replaceFirst("https:","wss:").replaceFirst("http:","ws:")

    fun api(request: CloudHttpRequest): CloudHttpResponse {
        val path=request.path
        fun body() = JSONObject(request.readBody(256*1024).toString(Charsets.UTF_8))
        if(path in setOf("/cloud/bootstrap","/cloud/report") && request.headers["x-r2c-page-revision"]!="2")
            return failure(409,"This Pilot 2 page does not match the R2C tablet version. Close the Open Platforms page, reopen the R2C tablet address, and enter the current access code.")
        if(path=="/cloud/bootstrap" && request.method=="GET") return success(bootstrap())
        if(path=="/cloud/status" && request.method=="GET") return success(snapshot())
        if(path=="/cloud/report" && request.method=="POST") {
            val value=body()
            val supported=setOf("licenseVerified","apiLoaded","mediaLoaded","missionLoaded","automaticOriginals","downloadOwner")
            require(value.keys().asSequence().all { it in supported && value.get(it) is Boolean }) { "Unsupported connection report." }
            value.keys().forEach { flags[it]=value.getBoolean(it) }
            if(flags["missionLoaded"]==true && flags["mediaLoaded"]==true) status(if(flags["automaticOriginals"]==true && flags["downloadOwner"]==true) "Pilot 2 mission library and automatic original-photo return ready" else "Pilot 2 photo return needs attention; reconnect before starting another mission")
            else notify(lastStatus)
            return success(JSONObject())
        }
        val parts=path.split('/').filter{it.isNotEmpty()}
        if(parts.size<6 || parts[2]!="v1" || parts[3]!="workspaces" || parts[4]!=workspaceId) return failure(404,"Unknown platform workspace.")
        val action=parts.drop(5).joinToString("/")
        when(parts[0]) {
            "storage" -> if(action=="sts" && request.method=="POST") return success(s3.credentials(baseUrl))
            "wayline" -> when {
                action=="waylines" && request.method=="GET" -> {
                    val page=request.query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val pageSize=request.query["page_size"]?.toIntOrNull()?.coerceIn(1,100) ?: 20
                    val first=route.coordinate(route.legs.first().start)
                    val entry=JSONObject().put("id",workspaceId).put("name",mission.getString("title")+" · "+if(mission.getString("profile")=="thermal") "IR" else "Visible")
                        .put("drone_model_key",route.aircraft.droneModelKey).put("payload_model_keys",JSONArray(listOf(route.aircraft.payloadModelKey))).put("template_types",JSONArray(listOf(0)))
                        .put("favorited",favorited).put("action_type",0).put("update_time",routeFile.lastModified()).put("user_name","R2C")
                        .put("start_wayline_point",JSONObject().put("start_latitude",first.second).put("start_lontitude",first.first))
                    val include=(request.query["favorited"]!="true" || favorited) && request.query["drone_model_keys"]?.let{it.contains(route.aircraft.droneModelKey)}!=false
                    return success(JSONObject().put("list",JSONArray(if(page==1&&include) listOf(entry) else emptyList<JSONObject>()))
                        .put("pagination",JSONObject().put("page",page).put("page_size",pageSize).put("total",if(include)1 else 0)))
                }
                action=="waylines/$workspaceId/url" && request.method=="GET" -> {
                    status("Pilot 2 requested the mapping route")
                    return CloudHttpResponse(302,headers=mapOf("Location" to "$baseUrl/cloud/route.kmz?ticket=$token"))
                }
                action=="favorites" && request.method in listOf("POST","DELETE") -> {favorited=request.method=="POST";return success(JSONObject())}
                action=="waylines/duplicate-names" -> return success(JSONArray(if(request.query["name"]==mission.getString("title"))listOf(mission.getString("title")) else emptyList<String>()))
            }
            "manage" -> if(action=="devices/topologies" && request.method=="GET") return success(JSONObject().put("list",topology))
            "media" -> when(action) {
                "files/tiny-fingerprints" -> {
                    val input=body().optJSONArray("tiny_fingerprints") ?: JSONArray()
                    val existing=verifiedReceipts().map{it.optString("tinyFingerprint")}.filter{it.isNotBlank()}.toSet()
                    return success(JSONObject().put("tiny_fingerprints",JSONArray((0 until input.length()).map{input.getString(it)}.filter{it in existing})))
                }
                "fast-upload" -> {
                    val fingerprint=body().optString("fingerprint")
                    val found=verifiedReceipts().any { it.optString("fingerprint")==fingerprint && fingerprint.isNotBlank() }
                    return if(found) success(JSONObject()) else apiError("The original needs upload.")
                }
                "upload-callback" -> return uploaded(body())
                "group-upload-callback" -> {
                    val value=body(); val count=value.getInt("file_count"); val uploaded=value.getInt("file_uploaded_count")
                    require(count in 0..10000 && uploaded in 0..count)
                    val group=value.optString("file_group_id"); require(group.length<=256)
                    reconcileGroup(JSONObject().put("fileGroupId",group).put("expectedCount",count).put("reportedUploaded",uploaded))
                    return success(JSONObject())
                }
            }
        }
        return failure(404,"Platform operation is not supported in this mapping session.")
    }
    fun downloadRoute() = CloudHttpResponse(type="application/vnd.google-earth.kmz",file=routeFile,headers=mapOf("Content-Disposition" to "attachment; filename=\"R2C-${workspaceId.take(8)}.kmz\""))
    @Synchronized private fun uploaded(value:JSONObject): CloudHttpResponse {
        // DJI's HTTP SDK success callback omits result; older documented clients send result=0.
        // Both forms still require the actual local object, its checksum and the original/tag checks.
        if(value.has("result") && value.getInt("result")!=0) { status("Pilot 2 reported an original-photo upload failure; retry is available in Pilot 2"); return apiError("Original upload was not completed.") }
        val key=value.getString("object_key"); val file=s3.objectFile(key); require(file.isFile) { "Original is not stored yet." }
        val digest=s3.objectDigest(key); val ext=value.optJSONObject("ext") ?: JSONObject(); val name=value.getString("name")
        require(name.length in 1..180 && name.none{it=='/' || it=='\\' || it.code<32}) { "Invalid original filename." }
        val original=ext.opt("is_original") == true
        val path=value.optString("path")
        val tagged=name.contains(route.photoTag,true) || path.contains(route.photoTag,true)
        val sensor=DjiOriginalCamera.sensor(file)
        val expected=if(mission.getString("profile")=="thermal")"thermal" else if(route.cameraProfile.focalLength35mm != null)"visible-zoom" else "visible-wide"
        val sensorMatches=if(expected=="visible-zoom")DjiOriginalCamera.matches(file,mission) else sensor=="unknown" || DjiOriginalCamera.matches(file,mission)
        val assigned=original && tagged && sensorMatches
        val reason=when{!original->"not_original";!tagged->"mission_tag_missing";!sensorMatches->"camera_mismatch";else->"matched"}
        val group=ext.optString("file_group_id").take(256)
        val receipt=JSONObject().put("name",name).put("objectKey",key).put("sha256",digest).put("assigned",assigned).put("isOriginal",original)
            .put("groupId",group).put("fingerprint",value.optString("fingerprint").take(256)).put("tinyFingerprint",ext.optString("tinny_fingerprint").take(256))
            .put("receivedAt",Instant.now().toString()).put("sensor",sensor).put("assignmentReason",reason).put("callbackHadResult",value.has("result"))
        if(assigned) photos.importOriginal(name,file,digest)
        MappingPhotoStore.writeJson(File(receipts,LocalS3.sha256(key.toByteArray())+".json"),receipt)
        status(if(assigned) "Original photo returned and verified by Pilot 2" else if(reason=="camera_mismatch")if(sensor=="thermal" && expected=="visible-wide")"Extra IR photo retained separately; visible mapping originals remain available" else "Returned photo retained separately; its camera differs from this mission’s selected camera" else "Returned photo retained separately; its original flag/mission tag did not match")
        val completedGroup=File(directory,"cloud/group-upload.json")
        if((assigned || reason=="camera_mismatch") && completedGroup.isFile) {
            val report=JSONObject(completedGroup.readText())
            if(report.getString("fileGroupId")==group)reconcileGroup(report)
        }
        return success(key) // Matches the reference HTTP SDK's HttpResultResponse<String>.
    }

    private fun verifiedReceipts() = receipts.listFiles().orEmpty().mapNotNull { file -> runCatching {
        JSONObject(file.readText()).takeIf { value ->
            if(value.optBoolean("assigned")) photos.progress(value.getString("sha256")).getBoolean("complete")
            else value.optBoolean("isOriginal") && value.optString("assignmentReason")=="camera_mismatch" &&
                s3.hasRetainedObject(value.getString("objectKey"),value.getString("sha256")) }
    }.getOrNull() }

    @Synchronized private fun reconcileGroup(value:JSONObject, notifyStatus:Boolean=true) {
        val group=value.getString("fileGroupId");val count=value.getInt("expectedCount");val uploaded=value.getInt("reportedUploaded")
        val returned=receipts.listFiles().orEmpty().mapNotNull{file->runCatching{
            JSONObject(file.readText()).takeIf{receipt->receipt.optString("groupId")==group && receipt.optBoolean("isOriginal") &&
                if(receipt.optBoolean("assigned"))photos.progress(receipt.getString("sha256")).getBoolean("complete")
                else receipt.optString("assignmentReason")=="camera_mismatch" && s3.objectFile(receipt.getString("objectKey")).isFile && s3.objectDigest(receipt.getString("objectKey"))==receipt.getString("sha256")}
        }.getOrNull()}.distinctBy{it.getString("objectKey")}
        val matched=returned.filter{it.optBoolean("assigned")}.map{it.getString("sha256")}.distinct()
        val ready=count>0 && uploaded==count && returned.size==count && matched.isNotEmpty()
        MappingPhotoStore.writeJson(File(directory,"cloud/group-upload.json"),value.put("verifiedCount",matched.size).put("returnedOriginalCount",returned.size).put("ready",ready))
        if(ready) photos.finish(matched)
        val message=if(ready) "All $count returned originals stored; ${matched.size} selected-camera mission originals verified locally" else "$uploaded/$count reported uploads; ${matched.size} selected-camera mission originals verified"
        if(notifyStatus)status(message) else lastStatus=message
    }

    fun mqtt(topic:String,bytes:ByteArray,websocketNotify:(JSONObject)->Unit): List<Pair<String,ByteArray>> {
        val value=runCatching{JSONObject(bytes.toString(Charsets.UTF_8))}.getOrNull() ?: return emptyList()
        val method=value.optString("method"); val data=value.optJSONObject("data") ?: JSONObject()
        val gateway=topic.split('/').getOrNull(2) ?: return emptyList()
        if(method=="update_topo" && topic.endsWith("/status")) {
            val aircraft=data.optJSONArray("sub_devices") ?: JSONArray(); val modelType=data.optInt("type",if(route.aircraft.droneType==67)119 else 174); val subtype=data.optInt("sub_type")
            fun device(sn:String,domain:Int,type:Int,sub:Int)=JSONObject().put("sn",sn).put("online_status",true)
                .put("device_model",JSONObject().put("key","$domain-$type-$sub").put("domain",domain.toString()).put("type",type.toString()).put("sub_type",sub.toString()))
                .put("device_callsign",if(domain==0) "Mapping aircraft" else "Mapping controller").put("user_id",workspaceId).put("user_callsign","R2C")
            val hosts=JSONArray((0 until aircraft.length()).map { val a=aircraft.getJSONObject(it); device(a.optString("sn").take(128),0,a.optInt("type",route.aircraft.droneType),a.optInt("sub_type",route.aircraft.droneSubtype)) })
            topology=JSONArray(listOf(JSONObject().put("hosts",hosts).put("parents",JSONArray(listOf(device(gateway,2,modelType,subtype))))))
            status("Pilot 2 controller registered on the local tablet")
            websocketNotify(JSONObject().put("biz_code","device_update_topo").put("version","1.0").put("timestamp",System.currentTimeMillis()).put("data",JSONObject().put("sn",gateway)))
            return listOf("sys/product/$gateway/status_reply" to reply(value,JSONObject().put("result",0)))
        }
        if(topic.endsWith("/requests")) {
            val result=if(method=="storage_config_get") JSONObject().put("result",0).put("output",s3.credentials(baseUrl)) else JSONObject().put("result",100)
            return listOf(topic+"_reply" to reply(value,result))
        }
        if(topic.endsWith("/events") && value.optInt("need_reply")==1) return listOf(topic+"_reply" to reply(value,JSONObject().put("result",0)))
        return emptyList()
    }
    private fun reply(value:JSONObject,data:JSONObject)=JSONObject().put("bid",value.optString("bid")).put("tid",value.optString("tid"))
        .put("timestamp",System.currentTimeMillis()).put("method",value.optString("method")).put("data",data).toString().toByteArray()
    companion object {
        fun success(data:Any)=CloudHttpResponse(bytes=JSONObject().put("code",0).put("message","success").put("data",data).toString().toByteArray())
        fun failure(status:Int,message:String)=CloudHttpResponse(status,bytes=JSONObject().put("code",1).put("message",message).put("data",JSONObject()).toString().toByteArray())
        private fun apiError(message:String)=CloudHttpResponse(bytes=JSONObject().put("code",1).put("message",message).put("data",JSONObject()).toString().toByteArray())
    }
}
