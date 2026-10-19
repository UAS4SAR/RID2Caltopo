package org.ncssar.rid2caltopo.mapping

import java.io.File
import org.json.JSONArray

/** The same completed selection is attempted once, matching iPad's persisted marker. */
internal object MappingAutomaticReconstruction {
    private fun marker(root:File)=File(root,"cloud/automatic-reconstruction-selection.json")
    fun isNewSelection(root:File,selection:List<String>):Boolean {
        val old=runCatching{JSONArray(marker(root).readText()).let{a->(0 until a.length()).map{a.getString(it)}.toSet()}}.getOrNull()
        return old!=selection.toSet()
    }
    fun record(root:File,selection:List<String>) {
        val file=marker(root);file.parentFile!!.mkdirs()
        val pending=File(file.parentFile,file.name+".tmp")
        pending.writeText(JSONArray(selection.sorted()).toString())
        check(pending.renameTo(file)){"Could not record automatic reconstruction selection."}
    }
}
