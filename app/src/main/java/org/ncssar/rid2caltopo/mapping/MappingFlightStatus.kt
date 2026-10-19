package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A completion snapshot is a selection, not the entire growing photo inventory. */
data class MappingFlightStatus(
    val id: String, val title: String, val createdAt: String, val photoCount: Int, val storedBytes: Long,
    val pendingCount: Int, val completedDigests: List<String>, val stage: String,
    val active: Boolean = false, val progress: Double? = null, val remainingSeconds: Long? = null,
    val layerId: String? = null, val hasRoute: Boolean = false, val visible: Boolean = true,
    val storage: MappingStorageUsage? = null,
    val plannedPhotos:Int?=null, val plannedSeconds:Int?=null, val plannedHeightMeters:Double?=null,
    val resultIds: List<String> = emptyList(), val cameraLabel: String = "Camera unavailable"
) {
    val time: String get() = runCatching { Instant.parse(createdAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d HH:mm")) }.getOrDefault(createdAt)
    val inventoryLabel: String get() = "$photoCount originals · ${"%.1f".format(storedBytes / (1024.0 * 1024.0))} MiB"
    val extraCount: Int get() = if (completedDigests.isEmpty()) 0 else photoCount - completedDigests.size
}

object MappingFlightRepository {
    private fun read(file: File): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()

    fun completionDigests(root: File): List<String> {
        val array = read(File(root, "received-batch.json"))?.optJSONArray("sha256") ?: return emptyList()
        val digests = runCatching { (0 until array.length()).map { array.getString(it).also(MappingPhotoStore::checkDigest) } }.getOrNull() ?: return emptyList()
        if (digests.isEmpty() || digests.distinct().size != digests.size) return emptyList()
        if (!digests.all { digest ->
            val record = read(File(root, "inventory/$digest.json"))
            record?.optBoolean("complete") == true && record.optString("sha256") == digest &&
                File(root, "originals/$digest.jpg").length() == record.optLong("sizeBytes", -1)
        }) return emptyList()
        return digests
    }

    fun list(filesDir: File, assignmentId: String? = null, incidentId: String? = null, live: MappingProcessingState? = null, includeStorage: Boolean = true, onlyMissionId: String? = null): List<MappingFlightStatus> {
        val storage = MappingMissionStorage.index(filesDir, live)
        return File(filesDir, "mapping-missions").listFiles().orEmpty().filter { it.isDirectory && (onlyMissionId == null || it.name == onlyMissionId) }.mapNotNull { root ->
            val mission = read(File(root, "mission.json")) ?: return@mapNotNull null
            if ((assignmentId != null && mission.optString("assignmentId") != assignmentId) || (incidentId != null && mission.optString("incidentId") != incidentId)) return@mapNotNull null
            runCatching { snapshot(root, mission, live, storage, includeStorage) }.getOrNull()
        }.filter { it.photoCount + it.pendingCount > 0 || it.hasRoute || it.resultIds.isNotEmpty() || it.storage?.let { usage -> usage.originals + usage.controllerReturns + usage.maps2D + usage.models3D + usage.exports + usage.working > 0 } == true }.sortedByDescending { it.createdAt }
    }

    fun snapshot(root: File, mission: JSONObject, live: MappingProcessingState? = null, storage: MappingMissionStorage.Index? = null, includeStorage: Boolean = true): MappingFlightStatus {
        val id = mission.getString("missionId")
        require(java.util.UUID.fromString(id).toString() == id)
        val records = File(root, "inventory").listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read)
        val complete = records.filter { record ->
            val digest = record.optString("sha256")
            digest.matches(Regex("[a-f0-9]{64}")) && record.optBoolean("complete") &&
                File(root, "originals/$digest.jpg").length() == record.optLong("sizeBytes", -1)
        }
        val selected = completionDigests(root)
        val status = read(File(root, "processing-status.json"))
        val matching = live?.takeIf { it.missionId == id }
        val result = status?.optString("layerId")?.takeIf { value ->
            runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false) &&
                File(root.parentFile.parentFile, "mapping-layers/$value/layer.json").isFile
        }
        val stage = when {
            matching != null -> matching.stage
            status?.optBoolean("active") == true -> "Processing interrupted; originals retained"
            result != null -> "Map ready"
            !status?.optString("layerId").isNullOrBlank() -> "Result unavailable; originals retained"
            status != null -> status.optString("stage", "Processing stopped; originals retained")
            records.size > complete.size -> "Receiving originals"
            selected.isNotEmpty() -> "Completed photo set received"
            complete.isNotEmpty() -> "Originals received; completion not confirmed"
            else -> "Waiting for flight photos"
        }
        val summaryFile=File(root,"route-summary.json")
        val route=read(summaryFile)?.takeIf { it.optInt("estimatedPhotos") > 0 && it.optInt("estimatedFlightSeconds") > 0 && it.optDouble("heightMeters",0.0) > 0 }
            ?: runCatching { DjiMappingRoute(mission).summary().also { recovered ->
                // Recover older plans once, rather than regenerating the route on every library refresh.
                runCatching { MappingPhotoStore.writeJson(summaryFile,recovered) }
            } }.getOrNull()
        val ownedStorage = storage ?: MappingMissionStorage.index(root.parentFile.parentFile, live)
        return MappingFlightStatus(id, mission.getString("title"), mission.getString("createdAt"), complete.size,
            complete.sumOf { it.getLong("sizeBytes") }, records.size - complete.size, selected, stage,
            matching?.active == true, matching?.progress, matching?.remainingSeconds,
            matching?.layer?.id?.takeIf { File(root.parentFile.parentFile, "mapping-layers/$it/layer.json").isFile } ?: result, File(root, "mission.kmz").isFile, mission.optString("profile") == "daylight-rgb",
            if (includeStorage) ownedStorage.mission(id) else null,
            route?.optInt("estimatedPhotos")?.takeIf{it>0},route?.optInt("estimatedFlightSeconds")?.takeIf{it>0},route?.optDouble("heightMeters")?.takeIf{it.isFinite()&&it>0},
            (ownedStorage.resultRoots(id).map { it.name } + listOfNotNull(result)).distinct(),
            MappingResolution.cameraLabel(mission.optString("cameraProfileId"), mission.optString("cameraName")))
    }
}
