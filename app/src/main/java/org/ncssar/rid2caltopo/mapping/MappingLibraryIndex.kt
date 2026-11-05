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
        val loadMs: Long, val refreshMs: Long, val changed: Int, val removed: Int,
        val stale: Boolean = false, val skipReason: String? = null)

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

    fun isVisible(record: MissionRecord, root: File? = null): Boolean {
        if (record.photoCount + record.pendingCount > 0 || record.hasRoute || record.resultIds.isNotEmpty()) return true
        if (root != null && MissionProjectionGuard.hasRetainedEvidence(root)) return true
        return false
    }

    fun entries(index: Index, context: Context, live: MappingProcessingState?, stale: Boolean = false): List<MappingLibraryEntry> {
        val tombstoned = MissionDeletionTombstone.load(context.filesDir)
        val owned = index.missions.values.flatMap { it.resultIds }.toSet()
        val flights = index.missions.values
            .filter { record -> record.id !in tombstoned }
            .filter { record ->
                isVisible(record) || MissionProjectionGuard.hasRetainedEvidence(File(context.filesDir, "mapping-missions/${record.id}"))
            }
            .map { record ->
                val entry = record.entry(live)
                if (stale) entry.copy(stage = MappingLibraryListing.STALE_STAGE) else entry
            }
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
        if (MappingLibraryListing.heavyBusy()) {
            CaltopoClient.CTDebug(TAG, "LibIdx skip reason=${MappingLibraryListing.REASON_HYDRATING}")
            val tombstoned = MissionDeletionTombstone.load(filesDir)
            val base = previous ?: Index(SCHEMA, emptyMap(), emptyMap())
            val index = if (tombstoned.isEmpty()) base else Index(SCHEMA,
                base.missions.filterKeys { it !in tombstoned },
                base.layers.filterValues { it.missionId !in tombstoned })
            val refreshMs = (System.nanoTime() - refreshStart) / 1_000_000
            return RefreshResult(entries(index, context, live, stale = previous != null), index, loadMs, refreshMs, 0, 0,
                stale = previous != null, skipReason = MappingLibraryListing.REASON_HYDRATING)
        }
        MappingLayerRecovery.ensureScheduled(context)
        MappingProcessingManager.ensureResume(context)
        runCatching { MappingLayerRecovery.adoptCompleteOrphans(context) }
        val volumes = runCatching { context.getExternalFilesDirs(null) }.getOrNull().orEmpty()
            .filterNotNull().map { File(it, "local-mapping") }
        val missionDirs = when (val missionListing = MappingLibraryListing.listMissions(filesDir)) {
            is MappingLibraryListing.Result.Unavailable -> {
                val reason = missionListing.reason
                CaltopoClient.CTDebug(TAG, "LibIdx skip reason=$reason")
                val tombstoned = MissionDeletionTombstone.load(filesDir)
                val base = previous ?: Index(SCHEMA, emptyMap(), emptyMap())
                val index = if (tombstoned.isEmpty()) base else Index(SCHEMA,
                    base.missions.filterKeys { it !in tombstoned },
                    base.layers.filterValues { it.missionId !in tombstoned })
                val refreshMs = (System.nanoTime() - refreshStart) / 1_000_000
                return RefreshResult(entries(index, context, live, stale = previous != null), index, loadMs, refreshMs, 0, 0,
                    stale = previous != null, skipReason = reason)
            }
            is MappingLibraryListing.Result.Available -> missionListing.dirs
        }
        val layerListing = MappingLibraryListing.listLayers(filesDir)
        val layerDirs = when (layerListing) {
            is MappingLibraryListing.Result.Available -> layerListing.dirs
            is MappingLibraryListing.Result.Unavailable -> {
                // Layers hydrating: keep previous layer records; still refresh missions.
                emptyList()
            }
        }
        val layersListed = layerListing is MappingLibraryListing.Result.Available
        val storage = MappingMissionStorage.index(filesDir, live)
        val tombstoned = MissionDeletionTombstone.load(filesDir)
        val nextMissions = linkedMapOf<String, MissionRecord>()
        val nextLayers = linkedMapOf<String, LayerRecord>()
        var changed = 0
        val seenMissionIds = mutableSetOf<String>()
        for (root in missionDirs) {
            val missionFile = File(root, "mission.json")
            val mission = jsonReader(missionFile)
            val idFromDir = root.name.takeIf { runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false) }
            if (idFromDir != null && idFromDir in tombstoned) continue
            if (mission == null) {
                if (idFromDir != null && MissionProjectionGuard.hasRetainedEvidence(root)) {
                    seenMissionIds += idFromDir
                    val cached = previous?.missions?.get(idFromDir)
                    nextMissions[idFromDir] = cached?.copy(stage = "Mission metadata unreadable; originals retained")
                        ?: syntheticUnreadable(idFromDir, root)
                    changed++
                }
                continue
            }
            val id = mission.optString("missionId").takeIf { runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: continue
            if (root.name != id) continue
            if (id in tombstoned) continue
            seenMissionIds += id
            val fingerprint = MissionFingerprint.of(root)
            val cached = previous?.missions?.get(id)
            val record = if (cached != null && cached.fingerprint == fingerprint) {
                applyLive(cached, live)
            } else {
                changed++
                rebuildMission(filesDir, root, mission, fingerprint, cached, live, storage)
            }
            val finalized = finalizeVisible(record, root)
            if (isVisible(finalized, root) || MissionProjectionGuard.hasRetainedEvidence(root)) nextMissions[id] = finalized
        }
        // Never drop a previous mission unless the parent listing succeeded and the id is absent.
        for ((id, record) in previous?.missions.orEmpty()) {
            if (id in tombstoned) continue
            if (id in nextMissions) continue
            if (id !in seenMissionIds) {
                // Confirmed gone from a successful listing — drop.
                continue
            }
            // Listed but filtered: keep if evidence remains (should already be in next).
            if (MissionProjectionGuard.hasRetainedEvidence(File(filesDir, "mapping-missions/$id"))) {
                nextMissions[id] = record
            }
        }
        if (layersListed) {
            for (root in layerDirs) {
                val fingerprint = LayerFingerprint.of(root)
                val cached = previous?.layers?.get(root.name)
                val record = if (cached != null && cached.fingerprint == fingerprint) cached else {
                    changed++
                    rebuildLayer(root, fingerprint) ?: continue
                }
                if (record.missionId in tombstoned) continue
                nextLayers[record.id] = record
            }
        } else {
            previous?.layers?.filterValues { it.missionId !in tombstoned }?.let { nextLayers.putAll(it) }
        }
        val stampPruned = selfHealStamps(filesDir, volumes, nextMissions.keys)
        val removed = (previous?.missions?.keys.orEmpty() - nextMissions.keys).size +
            (previous?.layers?.keys.orEmpty() - nextLayers.keys).size
        val index = synchronized(MissionDeletionTombstone.lock) {
            // Re-read tombstones under the lock so a concurrent delete cannot be overwritten.
            val liveTombstones = MissionDeletionTombstone.load(filesDir)
            val missions = nextMissions.filterKeys { it !in liveTombstones }
            val layers = nextLayers.filterValues { it.missionId !in liveTombstones }
            val next = Index(SCHEMA, missions, layers)
            if (previous == null || changed > 0 || removed > 0 || stampPruned > 0 ||
                previous.missions.keys != missions.keys || previous.layers.keys != layers.keys
            ) {
                write(filesDir, next)
            }
            next
        }
        retainStats(filesDir)
        val refreshMs = (System.nanoTime() - refreshStart) / 1_000_000
        CaltopoClient.CTDebug(TAG, "LibIdx load ms=$loadMs n=${previous?.missions?.size ?: 0} | refresh ms=$refreshMs changed=$changed removed=$removed")
        return RefreshResult(entries(index, context, live), index, loadMs, refreshMs, changed, removed)
    }

    /**
     * Drop stale verify-stamp entries whose mission folders are absent everywhere and that have
     * no originals anywhere. Never prune a mission that still has originals. Index entries for
     * absent folders are already omitted from [nextMissions] after a successful listing.
     */
    private fun selfHealStamps(filesDir: File, volumes: List<File>, liveMissionIds: Set<String>): Int {
        val stamps = MissionVerifyStamps.load(filesDir)
        if (stamps.isEmpty()) return 0
        val kept = stamps.filterKeys { id ->
            id in liveMissionIds ||
                missionHasOriginalsAnywhere(filesDir, volumes, id) ||
                missionPresentAnywhere(filesDir, volumes, id)
        }
        val pruned = stamps.size - kept.size
        if (pruned > 0) {
            synchronized(MissionDeletionTombstone.lock) { MissionVerifyStamps.write(filesDir, kept) }
        }
        return pruned
    }

    internal fun missionPresentAnywhere(filesDir: File, volumes: List<File>, id: String): Boolean {
        if (File(filesDir, "mapping-missions/$id").exists()) return true
        return volumes.any { File(it, "mapping-missions/$id").exists() }
    }

    internal fun missionHasOriginalsAnywhere(filesDir: File, volumes: List<File>, id: String): Boolean {
        val roots = listOf(File(filesDir, "mapping-missions/$id")) +
            volumes.map { File(it, "mapping-missions/$id") }
        return roots.any { MissionProjectionGuard.hasOriginals(it) }
    }

    /** Remove [missionId] from the working index under [MissionDeletionTombstone.lock]. */
    fun dropMissionLocked(filesDir: File, missionId: String) {
        val previous = load(filesDir) ?: return
        if (missionId !in previous.missions && previous.layers.values.none { it.missionId == missionId }) return
        write(filesDir, Index(SCHEMA,
            previous.missions - missionId,
            previous.layers.filterValues { it.missionId != missionId }))
    }

    fun retainStats(filesDir: File) {
        listOf(
            file(filesDir),
            MissionVerifyStamps.file(filesDir),
            MissionDeletionTombstone.file(filesDir),
        ).forEach { stats ->
            if (stats.isFile) runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.retain(stats) }
        }
    }

    private fun syntheticUnreadable(id: String, root: File): MissionRecord {
        val fp = MissionFingerprint.of(root)
        return MissionRecord(id, "Mission", "", "", "Mission metadata unreadable; originals retained",
            photoCount = 0, completeBytes = 0, pendingCount = 0, selectedCount = 0, hasRoute = File(root, "mission.kmz").isFile,
            plannedPhotos = null, plannedSeconds = null, plannedHeightMeters = null, resultIds = emptyList(),
            has2D = false, has3D = false, centerLat = null, centerLon = null, cameraLabel = "Camera unavailable",
            visible = true, routeUnavailableFingerprint = null, fingerprint = fp)
    }

    /** Counts parse as 0 / unreadable inventory must not hide retained originals. */
    private fun finalizeVisible(record: MissionRecord, root: File): MissionRecord {
        if (record.photoCount + record.pendingCount > 0 || record.resultIds.isNotEmpty()) return record
        if (!MissionProjectionGuard.hasRetainedEvidence(root)) return record
        val status = jsonReader(File(root, "processing-status.json"))
        val stage = when {
            status?.optBoolean("active") == true -> "Processing interrupted; originals retained"
            record.stage.contains("interrupted", ignoreCase = true) -> record.stage
            else -> "Mission metadata unreadable; originals retained"
        }
        return record.copy(stage = stage, visible = true)
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

    /** Best-effort durable write. Rename failures are logged and retried on the next refresh. */
    private fun write(filesDir: File, index: Index): Boolean = runCatching {
        val destination = file(filesDir)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(index.json().toString())
        if (temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
            true
        } else {
            CaltopoClient.CTDebug(TAG, "LibIdx write rename failed")
            false
        }
    }.getOrElse {
        CaltopoClient.CTDebug(TAG, "LibIdx write failed err=${it.message}")
        false
    }
}
