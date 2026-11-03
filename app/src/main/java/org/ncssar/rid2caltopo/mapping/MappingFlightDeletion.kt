package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Only explicit, operator-confirmed deletion. Never follows links inside a deleted tree. */
internal object MappingFlightDeletion {
    private const val TAG = "MapDelete"
    @Volatile private var deleting = false
    fun requireAvailable() = check(!deleting) { "Wait for saved-flight deletion to finish." }

    private fun reserve(missionId:String):List<DjiCloudServer> {
        // Same lock order as the controller callback (controller -> processing).
        return synchronized(DjiCloudManager) { synchronized(MappingProcessingManager) { synchronized(MappingTransferManager) {
            requireAvailable()
            MappingProcessingManager.requireIdleForDeletion()
            MappingTransferManager.requireIdleForDeletion()
            val connections=DjiCloudManager.stopForDeletion(missionId)
            deleting = true
            connections
        } } }
    }

    data class Outcome(val ok: Boolean, val partial: Boolean = false, val removed: Int = 0, val roots: Int = 0, val message: String? = null)

    fun delete(context: Context, missionId: String, originals: Boolean) {
        require(UUID.fromString(missionId).toString()==missionId)
        val short = missionId.take(8)
        val connections=reserve(missionId)
        try {
            connections.forEach { it.closeAndWait() }
            DjiCloudManager.deletionDrained(missionId,connections)
            val storage = org.ncssar.rid2caltopo.storage.OperationalStorage
            if (storage.areaBusy("missions") || storage.areaBusy("map-statistics") ||
                (storage.status.value.busy && !storage.ready())
            ) {
                refuse(short, originals, 0, "Storage still preparing")
            }
            check(storage.tryRequireReady(context)) {
                storage.status.value.message.ifEmpty {
                    "Choose or reconnect Archive Dir to use retained operational data."
                }
            }
            val managed = storage.ready()
            val volumes = context.getExternalFilesDirs(null).filterNotNull().map { File(it, "local-mapping") }
            val plan = plan(context.filesDir, volumes, missionId, originals, managed)
            // Validate every target before changing any files, including a removable card's presence.
            val targets = if (managed) plan.roots else plan.roots.flatMap { physicalRoots(context.filesDir, volumes, it) }
            if (originals) {
                // Explicit confirmation required; stop before index/record removal if any original fails.
                val originalFailure = removeOriginalsFirst(context.filesDir, volumes, missionId, managed)
                if (originalFailure != null) {
                    refuse(short, originals, targets.size, originalFailure)
                }
                // Metadata first so a crash mid-folder-delete cannot resurrect the mission from a stale index.
                recordFullDeletion(context.filesDir, missionId)
            }
            var removed = 0
            var failed: String? = null
            for (target in targets) {
                val ok = runCatching {
                    if (managed) storage.remove(target) else removeTree(target)
                }.isSuccess
                if (ok) removed++ else if (failed == null) failed = "Could not delete ${target.name}"
            }
            if (failed != null && removed < targets.size) {
                CaltopoClient.CTDebug(TAG, "MapDelete id=$short originals=${if (originals) 1 else 0} roots=${targets.size} partial removed=$removed/${targets.size}")
                if (originals) {
                    // Folders partially gone; index already tombstoned. Keep a labeled refusal.
                    error("Partially deleted, originals kept")
                } else {
                    error("Partially deleted, originals kept")
                }
            }
            val preferences = context.getSharedPreferences("mapping_layers", 0)
            val editor = preferences.edit()
            preferences.all.filterKeys { !it.startsWith("hidden:") && !it.startsWith("selected") }
                .filterValues { it is String }.forEach { (key, value) ->
                    val array = runCatching { org.json.JSONArray(value as String) }.getOrNull() ?: return@forEach
                    val retained = org.json.JSONArray()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        if (item.optString("layerId") !in plan.layerIds) retained.put(item)
                    }
                    editor.putString(key, retained.toString())
                }
            if (PreferenceTypeSafety.string(preferences, "selectedLocalLayer") in plan.layerIds) editor.remove("selectedLocalLayer")
            check(editor.commit()) { "Files were deleted, but map-list metadata could not be saved. Reopen Local maps." }
            if (!originals) {
                MappingPhotoStore.writeJson(File(context.filesDir, "mapping-missions/$missionId/processing-status.json"),
                    JSONObject().put("active", false).put("stage", "Generated results deleted; originals retained"))
            }
            MappingProcessingManager.dismiss()
            CaltopoClient.CTDebug(TAG, "MapDelete id=$short originals=${if (originals) 1 else 0} roots=${targets.size} ok")
        } catch (error: Throwable) {
            val reason = error.message?.takeIf { it.isNotBlank() } ?: "failed"
            if (!reason.startsWith("Partially deleted") && !reason.startsWith("Storage still") &&
                !reason.startsWith("Wait for") && !reason.startsWith("Close the") &&
                !reason.startsWith("Choose or") && !reason.startsWith("Reinsert") &&
                !reason.startsWith("Confirm") && !reason.startsWith("Could not delete original")
            ) {
                // Already logged partial/refuse paths; keep a compact line for unexpected failures.
                if (!reason.contains("MapDelete")) {
                    CaltopoClient.CTDebug(TAG, "MapDelete id=$short originals=${if (originals) 1 else 0} roots=0 refused $reason")
                }
            }
            throw error
        } finally {
            deleting = false
            MappingWorkspaceState.layersChanged()
        }
    }

    private fun refuse(short: String, originals: Boolean, roots: Int, reason: String): Nothing {
        CaltopoClient.CTDebug(TAG, "MapDelete id=$short originals=${if (originals) 1 else 0} roots=$roots refused $reason")
        error(reason)
    }

    /** Index + stamps + tombstone for a full mission delete (working + Archive Dir via retain). */
    internal fun recordFullDeletion(filesDir: File, missionId: String) {
        synchronized(MissionDeletionTombstone.lock) {
            MappingLibraryIndex.dropMissionLocked(filesDir, missionId)
            val stamps = MissionVerifyStamps.load(filesDir).toMutableMap()
            if (stamps.remove(missionId) != null) MissionVerifyStamps.write(filesDir, stamps)
            MissionDeletionTombstone.rememberLocked(filesDir, missionId)
        }
        MappingLibraryIndex.retainStats(filesDir)
    }

    /**
     * Delete original photos first for an entire-mission delete. Returns an error message if any
     * original could not be removed (caller must not remove the mission record or other files).
     */
    internal fun removeOriginalsFirst(filesDir: File, volumes: List<File>, id: String, managed: Boolean): String? {
        val candidates = mutableListOf<File>()
        val alias = File(filesDir, "mapping-missions/$id")
        if (alias.isDirectory || Files.isSymbolicLink(alias.toPath())) {
            runCatching {
                val roots = if (managed) listOf(alias) else physicalRoots(filesDir, volumes, alias)
                for (root in roots) {
                    val originals = File(root, "originals")
                    if (originals.isDirectory) candidates += originals
                }
            }
        }
        for (volume in volumes) {
            val originals = File(volume, "mapping-missions/$id/originals")
            if (originals.isDirectory) candidates += originals
        }
        for (originals in candidates.distinctBy { it.canonicalPath }) {
            val photos = originals.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jpg", true) }
            for (photo in photos) {
                val ok = runCatching {
                    if (managed) org.ncssar.rid2caltopo.storage.OperationalStorage.remove(photo)
                    else check(photo.delete() || !photo.exists())
                }.isSuccess
                if (!ok) return "Could not delete original photos; mission was not removed."
            }
            // Inventory sidecar next; leave the empty originals dir for the mission-tree delete.
            val inventory = File(originals.parentFile, "inventory")
            if (inventory.isDirectory) {
                for (record in inventory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }) {
                    val ok = runCatching {
                        if (managed) org.ncssar.rid2caltopo.storage.OperationalStorage.remove(record)
                        else check(record.delete() || !record.exists())
                    }.isSuccess
                    if (!ok) return "Could not delete original photos; mission was not removed."
                }
            }
        }
        return null
    }

    internal data class Plan(val roots: List<File>, val layerIds: Set<String>)
    internal fun plan(filesDir: File, volumes: List<File>, id: String, originals: Boolean, managed: Boolean = false): Plan {
        require(UUID.fromString(id).toString() == id)
        val mission = File(filesDir, "mapping-missions/$id")
        if (!managed) physicalRoots(filesDir, volumes, mission)
        require(JSONObject(File(mission, "mission.json").readText()).getString("missionId") == id)
        if (originals) {
            // Entire-mission delete must be explicitly confirmed by the caller (originals=true).
        } else if (MissionProjectionGuard.hasOriginals(mission)) {
            // Results-only path keeps originals; never schedule the mission root for removal.
        }
        val index = MappingMissionStorage.index(filesDir)
        val results = index.resultRoots(id)
        // A legacy result referenced by another flight must survive this flight's deletion.
        val shared = File(filesDir, "mapping-missions").listFiles().orEmpty().filter { it.name != id }
            .mapNotNull { runCatching { JSONObject(File(it, "processing-status.json").readText()).optString("layerId") }.getOrNull() }.toSet()
        val exclusive = results.filterNot { it.name in shared }
        return Plan(exclusive + index.workRoots(id) + if (originals) listOf(mission) else emptyList(), exclusive.map { it.name }.toSet())
    }

    /** Resolve only the app's exact category/id aliases and split mission bulk-storage pointer. */
    internal fun physicalRoots(filesDir: File, volumes: List<File>, alias: File): List<File> {
        val category = alias.parentFile!!.name
        require(category in listOf("mapping-missions", "mapping-layers", "mapping-processing"))
        require(UUID.fromString(alias.name).toString() == alias.name)
        require(alias.absoluteFile == File(filesDir, "$category/${alias.name}").absoluteFile)
        require(alias.parentFile!!.canonicalFile == File(filesDir.canonicalFile, category))
        val expected = volumes.map { File(it.canonicalFile, "$category/${alias.name}") }
        fun checked(target: File): File {
            require(target.absoluteFile in expected && target.canonicalFile == target.absoluteFile) { "Unexpected mapping storage location; nothing was deleted." }
            require(target.isDirectory) { "Reinsert the mapping microSD card before deleting this flight." }
            return target
        }
        if (Files.isSymbolicLink(alias.toPath())) {
            val link = Files.readSymbolicLink(alias.toPath())
            val target = (if (link.isAbsolute) link else alias.parentFile!!.toPath().resolve(link)).normalize().toFile()
            return listOf(checked(target), alias)
        }
        require(alias.isDirectory) { "Saved mapping files are unavailable." }
        val pointer = File(alias, "mapping-volume.json")
        return if (category == "mapping-missions" && pointer.isFile) {
            listOf(checked(File(JSONObject(pointer.readText()).getString("path"))), alias)
        } else listOf(alias)
    }

    internal fun removeTree(root: File) {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
