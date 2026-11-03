package org.ncssar.rid2caltopo.mapping

import android.content.Context
import java.io.File
import org.ncssar.rid2caltopo.storage.OperationalStorage

/** Native-code workspace only. Durable mission data belongs to the selected Archive Dir. */
internal object MappingVolume {
    const val MAX_PHOTOS = 10000
    fun card(context: Context): File? =
        runCatching { OperationalStorage.workspace(context) }.getOrElse { error ->
            if (OperationalStorage.isUnavailable(error)) null else throw error
        }
    fun directory(context: Context, category: String, id: String, requireCard: Boolean = false): File {
        require(category in listOf("mapping-missions", "mapping-processing", "mapping-layers"))
        require(java.util.UUID.fromString(id).toString() == id)
        check(OperationalStorage.tryRequireReady(context)) {
            OperationalStorage.status.value.message.ifEmpty {
                "Choose or reconnect Archive Dir to use retained operational data."
            }
        }
        // Reconstruction waits only on this mission's inputs, not bulk mission-tree verify.
        if (category == "mapping-missions") {
            check(OperationalStorage.ensureMissionReady(context, id)) {
                OperationalStorage.status.value.message.ifEmpty {
                    "Choose or reconnect Archive Dir to use retained operational data."
                }
            }
        }
        return File(context.filesDir, "$category/$id").apply {
            require(isDirectory || mkdirs()) { "Cannot create temporary mapping workspace." }
        }
    }
    fun checkBudget(context: Context, additional: Long = 0) {
        val root = card(context) ?: error(
            OperationalStorage.status.value.message.ifEmpty {
                "Choose or reconnect Archive Dir to use retained operational data."
            }
        )
        MappingStorageBudget.requireFree(root, additional, bucket = "Mapping work space")
    }
}
