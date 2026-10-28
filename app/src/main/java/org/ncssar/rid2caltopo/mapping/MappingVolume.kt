package org.ncssar.rid2caltopo.mapping

import android.content.Context
import java.io.File
import org.ncssar.rid2caltopo.storage.OperationalStorage

/** Native-code workspace only. Durable mission data belongs to the selected Archive Dir. */
internal object MappingVolume {
    const val MAX_PHOTOS = 10000
    fun card(context: Context): File? = OperationalStorage.workspace(context)
    fun directory(context: Context, category: String, id: String, requireCard: Boolean = false): File {
        require(category in listOf("mapping-missions", "mapping-processing", "mapping-layers"))
        require(java.util.UUID.fromString(id).toString() == id)
        OperationalStorage.requireReady(context)
        return File(context.filesDir, "$category/$id").apply {
            require(isDirectory || mkdirs()) { "Cannot create temporary mapping workspace." }
        }
    }
    fun checkBudget(context: Context, additional: Long = 0) {
        val root = OperationalStorage.workspace(context)
        require(root.usableSpace - additional > MappingPhotoStore.FREE_SPACE_RESERVE) {
            "Not enough temporary working space for this map. Retained data remains in Archive Dir."
        }
    }
}
