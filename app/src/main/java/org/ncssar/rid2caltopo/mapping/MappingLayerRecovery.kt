package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Adopt complete unpublished mapping-layers dirs into incident prefs. IO worker only. */
internal object MappingLayerRecovery {
    private val scheduled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + org.ncssar.rid2caltopo.app.StartupCoroutineSafety.handler)

    fun ensureScheduled(context: Context) {
        if (!scheduled.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            // Run once when ready becomes true — not on every status message rewrite.
            org.ncssar.rid2caltopo.storage.OperationalStorage.status
                .map { it.ready }
                .distinctUntilChanged()
                .collect { ready ->
                    if (ready) runCatching { adoptCompleteOrphans(app) }
                        .onFailure { CaltopoClient.CTWarn("MappingProcessing", "orphan adopt failed err=${it.message}") }
                }
        }
    }

    /** Idempotent. Scans mapping-layers; registers complete orphans; removes empty unprotected dirs. */
    fun adoptCompleteOrphans(context: Context): Int {
        val main = runCatching { Looper.getMainLooper() }.getOrNull()
        if (main != null) check(Looper.myLooper() != main) {
            "Mapping layer orphan adoption belongs on an IO worker."
        }
        val root = File(context.filesDir, "mapping-layers")
        if (!root.isDirectory) return 0
        val protected = MappingProcessingManager.protectedMappingIds()
        val remembered = OrphanEmptyRegistry.load(context.filesDir)
        var adopted = 0
        for (dir in root.listFiles().orEmpty().filter { it.isDirectory }) {
            val id = dir.name
            if (runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false).not()) continue
            require(!dir.path.contains("mapping-missions")) { "orphan cleanup refuses mapping-missions" }
            val children = dir.listFiles()
            if (children == null) continue
            if (children.isEmpty()) {
                if (id !in protected) {
                    removeEmptyOrphan(context, dir, id, remembered)
                }
                continue
            }
            // Never delete a directory that still holds original photos.
            if (MissionProjectionGuard.hasOriginals(dir)) continue
            if (!MappingLayer.hasTileManifest(dir)) continue
            val layer = runCatching {
                MappingLayer.parse(JSONObject(File(dir, "layer.json").readText()))
            }.getOrNull() ?: continue
            val existing = MappingLayer.load(context, layer.incidentId)
            if (existing.none { it.id == layer.id }) {
                MappingLayer.visibility(context, layer.incidentId, layer.id, true)
                MappingLayer.save(context, layer.incidentId, existing.filterNot { it.id == layer.id } + layer)
                adopted++
                CaltopoClient.CTDebug("MappingProcessing", "orphan adopted id=${layer.id.take(8)} incident=${layer.incidentId.take(8)}")
            }
            linkMission(context, layer)
        }
        return adopted
    }

    private fun removeEmptyOrphan(context: Context, dir: File, id: String, remembered: Set<String>) {
        require(!dir.path.contains("mapping-missions")) { "orphan cleanup refuses mapping-missions" }
        require(!MissionProjectionGuard.hasOriginals(dir)) { "orphan cleanup refuses dirs with originals" }
        // Prefer Archive Dir deletion so deferred layers hydrate cannot resurrect the empty dir.
        val removed = runCatching {
            org.ncssar.rid2caltopo.storage.OperationalStorage.remove(dir)
            !dir.exists()
        }.getOrDefault(false) || runCatching {
            dir.deleteRecursively()
            !dir.exists()
        }.getOrDefault(false) || !dir.exists()
        if (!removed && dir.exists()) {
            // Still present (busy projection). Remember so we do not spam the same id.
            if (id !in remembered) {
                OrphanEmptyRegistry.remember(context.filesDir, id)
                CaltopoClient.CTDebug("MappingProcessing", "orphan empty pending id=${id.take(8)}")
            }
            return
        }
        val first = OrphanEmptyRegistry.remember(context.filesDir, id)
        if (first) CaltopoClient.CTDebug("MappingProcessing", "orphan empty removed id=${id.take(8)}")
    }

    private fun linkMission(context: Context, layer: MappingLayer) {
        val missionId = layer.missionId
        if (missionId.isBlank()) return
        val statusFile = File(context.filesDir, "mapping-missions/$missionId/processing-status.json")
        if (!statusFile.isFile) return
        val status = runCatching { JSONObject(statusFile.readText()) }.getOrNull() ?: return
        if (status.optString("layerId").isNotBlank()) return
        status.put("layerId", layer.id).put("stage", status.optString("stage").ifBlank { "Map ready" })
            .put("active", false).put("progress", 1.0)
        runCatching { MappingPhotoStore.writeJson(statusFile, status) }
            .onFailure { CaltopoClient.CTWarn("MappingProcessing", "orphan mission link failed id=${layer.id.take(8)}") }
    }
}
