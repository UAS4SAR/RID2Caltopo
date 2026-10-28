package org.ncssar.rid2caltopo.mapping

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class MappingSurfaceStartup(val root: File, val paths: List<String>, val hasLayer: Boolean) {
    companion object {
        /** The root factory can touch providers/filesDir, so it runs inside IO too. */
        suspend fun load(root: () -> File): MappingSurfaceStartup = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val folder = root()
            val collection = File(folder, "surface/collection.json")
            val paths = if (collection.isFile) {
                require(collection.length() in 1..32L * 1024 * 1024) { "Invalid surface collection size" }
                val regions = JSONObject(collection.readText()).getJSONArray("regions")
                require(regions.length() in 1..200) { "Invalid surface region count" }
                (0 until regions.length()).map { i ->
                    currentCoroutineContext().ensureActive()
                    regions.getJSONObject(i).getString("path").also {
                        require(it == "regions/$i/surface.glb") { "Invalid surface region path" }
                    }
                }
            } else emptyList()
            currentCoroutineContext().ensureActive()
            MappingSurfaceStartup(folder, paths, File(folder, "layer.json").isFile)
        }
    }
}
