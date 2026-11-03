package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.File

/** Persisted library metadata. Fingerprints are stat-only; never open originals/tiles/glb/map.tif. */
internal object MappingLibraryIndex {
    const val SCHEMA = 1
    private const val TAG = "LibIdx"
    /** Default JSON reader. Tests may wrap inventoryReader alone to count inventory opens. */
    @Volatile var jsonReader: (File) -> JSONObject? = { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
    @Volatile var inventoryReader: (File) -> JSONObject? = { file -> jsonReader(file) }

    data class FileStamp(val modified: Long, val length: Long) {
        fun json() = JSONObject().put("m", modified).put("n", length)
        companion object {
            fun of(file: File) = FileStamp(file.lastModified(), if (file.isFile) file.length() else 0L)
            fun parse(value: JSONObject?) = value?.let { FileStamp(it.optLong("m"), it.optLong("n")) }
        }
    }

    data class MissionFingerprint(
        val dirModified: Long, val inventoryModified: Long, val inventoryCount: Int,
        val mission: FileStamp, val status: FileStamp, val batch: FileStamp, val route: FileStamp
    ) {
        fun json() = JSONObject().put("dir", dirModified).put("invM", inventoryModified).put("invN", inventoryCount)
            .put("mission", mission.json()).put("status", status.json()).put("batch", batch.json()).put("route", route.json())
        companion object {
            fun of(root: File): MissionFingerprint {
                val inventory = File(root, "inventory")
                return MissionFingerprint(root.lastModified(), inventory.lastModified(),
                    inventory.list()?.count { it.endsWith(".json") } ?: 0,
                    FileStamp.of(File(root, "mission.json")), FileStamp.of(File(root, "processing-status.json")),
                    FileStamp.of(File(root, "received-batch.json")), FileStamp.of(File(root, "route-summary.json")))
            }
            fun parse(value: JSONObject) = MissionFingerprint(value.optLong("dir"), value.optLong("invM"), value.optInt("invN"),
                FileStamp.parse(value.optJSONObject("mission")) ?: FileStamp(0, 0),
                FileStamp.parse(value.optJSONObject("status")) ?: FileStamp(0, 0),
                FileStamp.parse(value.optJSONObject("batch")) ?: FileStamp(0, 0),
                FileStamp.parse(value.optJSONObject("route")) ?: FileStamp(0, 0))
        }
    }

    data class LayerFingerprint(val dirModified: Long, val layer: FileStamp, val surfaceModified: Long) {
        fun json() = JSONObject().put("dir", dirModified).put("layer", layer.json()).put("surface", surfaceModified)
        companion object {
            fun of(root: File) = LayerFingerprint(root.lastModified(), FileStamp.of(File(root, "layer.json")), File(root, "surface").lastModified())
            fun parse(value: JSONObject) = LayerFingerprint(value.optLong("dir"),
                FileStamp.parse(value.optJSONObject("layer")) ?: FileStamp(0, 0), value.optLong("surface"))
        }
    }

    data class MissionRecord(
        val id: String, val title: String, val createdAt: String, val captureTime: String, val stage: String,
        val photoCount: Int, val completeBytes: Long, val pendingCount: Int, val selectedCount: Int,
        val hasRoute: Boolean, val plannedPhotos: Int?, val plannedSeconds: Int?, val plannedHeightMeters: Double?,
        val resultIds: List<String>, val has2D: Boolean, val has3D: Boolean,
        val centerLat: Double?, val centerLon: Double?, val cameraLabel: String, val visible: Boolean,
        val routeUnavailableFingerprint: String?, val fingerprint: MissionFingerprint
    ) {
        fun json() = JSONObject().put("id", id).put("title", title).put("createdAt", createdAt).put("captureTime", captureTime)
            .put("stage", stage).put("photoCount", photoCount).put("completeBytes", completeBytes).put("pendingCount", pendingCount)
            .put("selectedCount", selectedCount).put("hasRoute", hasRoute)
            .put("plannedPhotos", plannedPhotos ?: JSONObject.NULL).put("plannedSeconds", plannedSeconds ?: JSONObject.NULL)
            .put("plannedHeightMeters", plannedHeightMeters ?: JSONObject.NULL)
            .put("resultIds", JSONArray(resultIds)).put("has2D", has2D).put("has3D", has3D)
            .put("centerLat", centerLat ?: JSONObject.NULL).put("centerLon", centerLon ?: JSONObject.NULL)
            .put("cameraLabel", cameraLabel).put("visible", visible)
            .put("routeUnavailableFingerprint", routeUnavailableFingerprint ?: JSONObject.NULL)
            .put("fingerprint", fingerprint.json())
        fun flight(live: MappingProcessingState?): MappingFlightStatus {
            val matching = live?.takeIf { it.missionId == id }
            return MappingFlightStatus(id, title, createdAt, photoCount, completeBytes, pendingCount, emptyList(),
                matching?.stage ?: stage, matching?.active == true, matching?.progress, matching?.remainingSeconds,
                matching?.layer?.id ?: resultIds.firstOrNull(), hasRoute, visible, null,
                plannedPhotos, plannedSeconds, plannedHeightMeters, resultIds, cameraLabel)
        }
        fun entry(live: MappingProcessingState?, storage: MappingStorageUsage? = null) = MappingLibraryEntry(
            "flight:$id", title, captureTime, createdAt, hasRoute, photoCount, has2D, has3D,
            live?.takeIf { it.missionId == id }?.stage ?: stage, storage,
            if (centerLat != null && centerLon != null) centerLat to centerLon else null, flight = flight(live)
        )
        companion object {
            fun parse(value: JSONObject): MissionRecord? {
                val id = value.optString("id").takeIf { runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: return null
                val fp = value.optJSONObject("fingerprint") ?: return null
                val results = value.optJSONArray("resultIds")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                return MissionRecord(id, value.optString("title"), value.optString("createdAt"), value.optString("captureTime"),
                    value.optString("stage"), value.optInt("photoCount"), value.optLong("completeBytes"), value.optInt("pendingCount"),
                    value.optInt("selectedCount"), value.optBoolean("hasRoute"),
                    value.opt("plannedPhotos").takeUnless { it == JSONObject.NULL }?.let { value.optInt("plannedPhotos") },
                    value.opt("plannedSeconds").takeUnless { it == JSONObject.NULL }?.let { value.optInt("plannedSeconds") },
                    value.opt("plannedHeightMeters").takeUnless { it == JSONObject.NULL }?.let { value.optDouble("plannedHeightMeters") },
                    results, value.optBoolean("has2D"), value.optBoolean("has3D"),
                    value.opt("centerLat").takeUnless { it == JSONObject.NULL }?.let { value.optDouble("centerLat") },
                    value.opt("centerLon").takeUnless { it == JSONObject.NULL }?.let { value.optDouble("centerLon") },
                    value.optString("cameraLabel", "Camera unavailable"), value.optBoolean("visible", true),
                    value.opt("routeUnavailableFingerprint").takeUnless { it == JSONObject.NULL }?.toString(),
                    MissionFingerprint.parse(fp))
            }
        }
    }

    data class LayerRecord(val id: String, val title: String, val capturedAt: String, val photoCount: Int,
        val has2D: Boolean, val has3D: Boolean, val incidentId: String, val missionId: String,
        val centerLat: Double?, val centerLon: Double?, val fingerprint: LayerFingerprint) {
        fun json() = JSONObject().put("id", id).put("title", title).put("capturedAt", capturedAt).put("photoCount", photoCount)
            .put("has2D", has2D).put("has3D", has3D).put("incidentId", incidentId).put("missionId", missionId)
            .put("centerLat", centerLat ?: JSONObject.NULL).put("centerLon", centerLon ?: JSONObject.NULL)
            .put("fingerprint", fingerprint.json())
        companion object {
            fun parse(value: JSONObject): LayerRecord? {
                val id = value.optString("id").takeIf { runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: return null
                val fp = value.optJSONObject("fingerprint") ?: return null
                return LayerRecord(id, value.optString("title"), value.optString("capturedAt"), value.optInt("photoCount"),
                    value.optBoolean("has2D"), value.optBoolean("has3D"), value.optString("incidentId"), value.optString("missionId"),
                    value.opt("centerLat").takeUnless { it == JSONObject.NULL }?.let { value.optDouble("centerLat") },
                    value.opt("centerLon").takeUnless { it == JSONObject.NULL }?.let { value.optDouble("centerLon") },
                    LayerFingerprint.parse(fp))
            }
        }
    }

    data class Index(val schema: Int, val missions: Map<String, MissionRecord>, val layers: Map<String, LayerRecord>) {
        fun json() = JSONObject().put("schema", schema)
            .put("missions", JSONObject().also { out -> missions.forEach { (k, v) -> out.put(k, v.json()) } })
            .put("layers", JSONObject().also { out -> layers.forEach { (k, v) -> out.put(k, v.json()) } })
    }

    data class RefreshResult(val entries: List<MappingLibraryEntry>, val index: Index,
        val loadMs: Long, val refreshMs: Long, val changed: Int, val removed: Int)

    fun file(filesDir: File) = File(filesDir, "mapping-storage-stats/library-index.json")

    fun load(filesDir: File): Index? {
        val text = runCatching { file(filesDir).readText() }.getOrNull() ?: return null
        return parse(text)
    }

    fun parse(text: String): Index? = runCatching {
        val value = JSONObject(text)
        if (value.optInt("schema") != SCHEMA) return null
        val missions = value.optJSONObject("missions") ?: return null
        val layers = value.optJSONObject("layers") ?: return null
        Index(SCHEMA,
            missions.keys().asSequence().mapNotNull { key -> MissionRecord.parse(missions.getJSONObject(key))?.let { it.id to it } }.toMap(),
            layers.keys().asSequence().mapNotNull { key -> LayerRecord.parse(layers.getJSONObject(key))?.let { it.id to it } }.toMap())
    }.getOrNull()

    fun entries(index: Index, context: Context, live: MappingProcessingState?): List<MappingLibraryEntry> {
        val owned = index.missions.values.flatMap { it.resultIds }.toSet()
        val flights = index.missions.values
            .filter { it.photoCount + it.pendingCount > 0 || it.hasRoute || it.resultIds.isNotEmpty() }
            .map { it.entry(live) }
        val layers = MappingLayer.allLocal(context).filterNot { it.id in owned }.mapNotNull { layer ->
            val record = index.layers[layer.id]
            MappingLibraryEntry("layer:${layer.id}", layer.title, layer.capturedAt, "", false, layer.photoCount,
                record?.has2D ?: true, record?.has3D ?: hasGlb(File(context.filesDir, "mapping-layers/${layer.id}/surface")),
                "Imported map · originals not stored here", null,
                record?.let { if (it.centerLat != null && it.centerLon != null) it.centerLat to it.centerLon else null }
                    ?: layer.bounds.takeIf { it.size == 4 }?.let { (it[1] + it[3]) / 2 to (it[0] + it[2]) / 2 },
                layer = layer)
        }
        return flights + layers
    }

    fun refresh(context: Context, live: MappingProcessingState?): RefreshResult {
        val filesDir = context.filesDir
        val loadStart = System.nanoTime()
        val previous = load(filesDir)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        val refreshStart = System.nanoTime()
        MappingLayerRecovery.ensureScheduled(context)
        runCatching { MappingLayerRecovery.adoptCompleteOrphans(context) }
        val missionDirs = File(filesDir, "mapping-missions").listFiles().orEmpty().filter { it.isDirectory }
        val layerDirs = File(filesDir, "mapping-layers").listFiles().orEmpty().filter { it.isDirectory }
        val storage = MappingMissionStorage.index(filesDir, live)
        val nextMissions = linkedMapOf<String, MissionRecord>()
        val nextLayers = linkedMapOf<String, LayerRecord>()
        var changed = 0
        for (root in missionDirs) {
            val missionFile = File(root, "mission.json")
            val mission = jsonReader(missionFile) ?: continue
            val id = mission.optString("missionId").takeIf { runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: continue
            if (root.name != id) continue
            val fingerprint = MissionFingerprint.of(root)
            val cached = previous?.missions?.get(id)
            val record = if (cached != null && cached.fingerprint == fingerprint) {
                applyLive(cached, live)
            } else {
                changed++
                rebuildMission(filesDir, root, mission, fingerprint, cached, live, storage)
            }
            if (record.photoCount + record.pendingCount > 0 || record.hasRoute || record.resultIds.isNotEmpty()) nextMissions[id] = record
        }
        for (root in layerDirs) {
            val fingerprint = LayerFingerprint.of(root)
            val cached = previous?.layers?.get(root.name)
            val record = if (cached != null && cached.fingerprint == fingerprint) cached else {
                changed++
                rebuildLayer(root, fingerprint) ?: continue
            }
            nextLayers[record.id] = record
        }
        val removed = (previous?.missions?.keys.orEmpty() - nextMissions.keys).size +
            (previous?.layers?.keys.orEmpty() - nextLayers.keys).size
        val index = Index(SCHEMA, nextMissions, nextLayers)
        if (previous == null || changed > 0 || removed > 0 || previous.missions.keys != nextMissions.keys || previous.layers.keys != nextLayers.keys) {
            write(filesDir, index)
        }
        val refreshMs = (System.nanoTime() - refreshStart) / 1_000_000
        CaltopoClient.CTDebug(TAG, "LibIdx load ms=$loadMs n=${previous?.missions?.size ?: 0} | refresh ms=$refreshMs changed=$changed removed=$removed")
        return RefreshResult(entries(index, context, live), index, loadMs, refreshMs, changed, removed)
    }

    private fun applyLive(record: MissionRecord, live: MappingProcessingState?): MissionRecord {
        val matching = live?.takeIf { it.missionId == record.id } ?: return record
        return record.copy(stage = matching.stage)
    }

    private fun rebuildMission(filesDir: File, root: File, mission: JSONObject, fingerprint: MissionFingerprint,
        previous: MissionRecord?, live: MappingProcessingState?, storage: MappingMissionStorage.Index): MissionRecord {
        val id = mission.getString("missionId")
        val inventoryUnchanged = previous != null &&
            previous.fingerprint.inventoryModified == fingerprint.inventoryModified &&
            previous.fingerprint.inventoryCount == fingerprint.inventoryCount
        if (inventoryUnchanged) {
            // Reuse inventory-derived counts; refresh only status/route/result metadata (no inventory opens).
            val matching = live?.takeIf { it.missionId == id }
            val status = jsonReader(File(root, "processing-status.json"))
            val result = status?.optString("layerId")?.takeIf { value ->
                runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false) &&
                    File(filesDir, "mapping-layers/$value/layer.json").isFile
            }
            val stage = when {
                matching != null -> matching.stage
                status?.optBoolean("active") == true -> "Processing interrupted; originals retained"
                result != null -> "Map ready"
                !status?.optString("layerId").isNullOrBlank() -> "Result unavailable; originals retained"
                status != null -> status.optString("stage", previous!!.stage)
                previous!!.pendingCount > 0 -> "Receiving originals"
                previous.selectedCount > 0 -> "Completed photo set received"
                previous.photoCount > 0 -> "Originals received; completion not confirmed"
                else -> previous.stage
            }
            val route = MappingFlightRepository.resolveRoute(root, mission, previous.routeUnavailableFingerprint)
            val resultIds = (storage.resultRoots(id).map { it.name } + listOfNotNull(result)).distinct().ifEmpty { previous.resultIds }
            val has2D = resultIds.any { MappingLayer.hasTileManifest(File(filesDir, "mapping-layers/$it")) }
            val has3D = resultIds.any { hasGlb(File(filesDir, "mapping-layers/$it/surface")) }
            val centerPoint = if (previous.fingerprint.mission == fingerprint.mission) {
                if (previous.centerLat != null && previous.centerLon != null) previous.centerLat to previous.centerLon else null
            } else center(mission)
            return previous.copy(title = mission.optString("title", previous.title), createdAt = mission.optString("createdAt", previous.createdAt),
                stage = stage, hasRoute = File(root, "mission.kmz").isFile, plannedPhotos = route?.optInt("estimatedPhotos")?.takeIf { it > 0 },
                plannedSeconds = route?.optInt("estimatedFlightSeconds")?.takeIf { it > 0 },
                plannedHeightMeters = route?.optDouble("heightMeters")?.takeIf { it.isFinite() && it > 0 },
                resultIds = resultIds, has2D = has2D, has3D = has3D, centerLat = centerPoint?.first, centerLon = centerPoint?.second,
                cameraLabel = MappingResolution.cameraLabel(mission.optString("cameraProfileId"), mission.optString("cameraName")),
                visible = mission.optString("profile") == "daylight-rgb",
                routeUnavailableFingerprint = MappingFlightRepository.routeUnavailableFingerprint(root) ?: previous.routeUnavailableFingerprint,
                fingerprint = fingerprint)
        }
        val records = File(root, "inventory").listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            inventoryReader(file)?.let { record ->
                val digest = record.optString("sha256"); if (digest.isNotEmpty()) digest to record else null
            }
        }.toMap()
        val snapshot = MappingFlightRepository.snapshot(root, mission, live, storage, includeStorage = false, inventory = records,
            priorRouteUnavailable = previous?.routeUnavailableFingerprint)
        val captureTime = MappingLibraryRepository.capture(root, records).takeIf { MappingLibraryEntry.localCapture(it) != null }.orEmpty().ifEmpty {
            snapshot.resultIds.mapNotNull { layerId ->
                jsonReader(File(filesDir, "mapping-layers/$layerId/layer.json"))?.optString("captureTimeLocal")
                    ?.takeIf { MappingLibraryEntry.localCapture(it) != null }
            }.minOrNull().orEmpty()
        }
        val has2D = snapshot.resultIds.any { MappingLayer.hasTileManifest(File(filesDir, "mapping-layers/$it")) }
        val has3D = snapshot.resultIds.any { hasGlb(File(filesDir, "mapping-layers/$it/surface")) }
        val reusedCenter = previous?.takeIf { it.fingerprint.mission == fingerprint.mission }
        val center = if (reusedCenter != null) {
            if (reusedCenter.centerLat != null && reusedCenter.centerLon != null) reusedCenter.centerLat to reusedCenter.centerLon else null
        } else center(mission)
        val unavailable = MappingFlightRepository.routeUnavailableFingerprint(root) ?: previous?.routeUnavailableFingerprint
        return MissionRecord(id, snapshot.title, snapshot.createdAt, captureTime, snapshot.stage, snapshot.photoCount,
            snapshot.storedBytes, snapshot.pendingCount, snapshot.completedDigests.size, snapshot.hasRoute,
            snapshot.plannedPhotos, snapshot.plannedSeconds, snapshot.plannedHeightMeters, snapshot.resultIds,
            has2D, has3D, center?.first, center?.second, snapshot.cameraLabel, snapshot.visible, unavailable, fingerprint)
    }

    private fun rebuildLayer(root: File, fingerprint: LayerFingerprint): LayerRecord? {
        val layerFile = File(root, "layer.json")
        if (!layerFile.isFile) return null
        val layer = runCatching { MappingLayer.parse(JSONObject(layerFile.readText())) }.getOrNull() ?: return null
        val has2D = MappingLayer.hasTileManifest(root)
        val has3D = hasGlb(File(root, "surface"))
        val bounds = layer.bounds
        val center = center(layer.assignmentBoundary) ?: if (bounds.size == 4) (bounds[1] + bounds[3]) / 2 to (bounds[0] + bounds[2]) / 2 else null
        return LayerRecord(layer.id, layer.title, layer.capturedAt, layer.photoCount, has2D, has3D, layer.incidentId, layer.missionId,
            center?.first, center?.second, fingerprint)
    }

    fun hasGlb(surface: File): Boolean {
        if (File(surface, "surface.glb").isFile) return true
        surface.listFiles()?.forEach { child ->
            if (child.isFile && child.extension.equals("glb", true)) return true
            if (child.isDirectory && File(child, "surface.glb").isFile) return true
            if (child.isDirectory) child.listFiles()?.forEach { nested ->
                if (nested.isFile && nested.extension.equals("glb", true)) return true
                if (nested.isDirectory && File(nested, "surface.glb").isFile) return true
            }
        }
        return false
    }

    private fun center(mission: JSONObject): Pair<Double, Double>? = runCatching {
        val ring = mission.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        val points = (0 until ring.length()).map { index -> ring.getJSONArray(index).let { listOf(it.getDouble(0), it.getDouble(1)) } }
        center(points)
    }.getOrNull()

    private fun center(points: List<List<Double>>): Pair<Double, Double>? {
        val good = points.filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() && it[0] in -180.0..180.0 && it[1] in -90.0..90.0 }
        if (good.isEmpty()) return null
        return (good.minOf { it[1] } + good.maxOf { it[1] }) / 2 to (good.minOf { it[0] } + good.maxOf { it[0] }) / 2
    }

    private fun write(filesDir: File, index: Index) {
        val destination = file(filesDir)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(index.json().toString())
        check(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
            "Could not save library index."
        }
    }
}
