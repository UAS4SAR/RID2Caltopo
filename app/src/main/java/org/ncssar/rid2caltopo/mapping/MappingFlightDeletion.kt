package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.json.JSONObject
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

    fun delete(context: Context, missionId: String, originals: Boolean) {
        require(UUID.fromString(missionId).toString()==missionId)
        val connections=reserve(missionId)
        try {
            connections.forEach { it.closeAndWait() }
            DjiCloudManager.deletionDrained(missionId,connections)
            val volumes = context.getExternalFilesDirs(null).filterNotNull().map { File(it, "local-mapping") }
            val plan = plan(context.filesDir, volumes, missionId, originals)
            // Validate every target before changing any files, including a removable card's presence.
            val targets = plan.roots.flatMap { physicalRoots(context.filesDir, volumes, it) }
            targets.forEach(::removeTree)
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
            if (preferences.getString("selectedLocalLayer", null) in plan.layerIds) editor.remove("selectedLocalLayer")
            check(editor.commit()) { "Files were deleted, but map-list metadata could not be saved. Reopen Local maps." }
            if (!originals) {
                MappingPhotoStore.writeJson(File(context.filesDir, "mapping-missions/$missionId/processing-status.json"),
                    JSONObject().put("active", false).put("stage", "Generated results deleted; originals retained"))
            }
            MappingProcessingManager.dismiss()
        } finally {
            deleting = false
            MappingWorkspaceState.layersChanged()
        }
    }

    internal data class Plan(val roots: List<File>, val layerIds: Set<String>)
    internal fun plan(filesDir: File, volumes: List<File>, id: String, originals: Boolean): Plan {
        require(UUID.fromString(id).toString() == id)
        val mission = File(filesDir, "mapping-missions/$id")
        physicalRoots(filesDir, volumes, mission)
        require(JSONObject(File(mission, "mission.json").readText()).getString("missionId") == id)
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
