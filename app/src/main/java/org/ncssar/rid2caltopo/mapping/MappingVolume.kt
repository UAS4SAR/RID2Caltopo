package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.os.Environment
import android.system.Os
import java.io.File

/** Private removable storage, with stable internal aliases for existing mission readers. */
internal object MappingVolume {
    const val MAX_PHOTOS = 10000
    fun card(context:Context):File? = context.getExternalFilesDirs(null).filterNotNull()
        .firstOrNull { Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it)==Environment.MEDIA_MOUNTED }
        ?.let { File(it,"local-mapping").apply { require(isDirectory || mkdirs()) { "Cannot create mapping storage on microSD." } } }
    @Synchronized fun directory(context:Context,category:String,id:String,requireCard:Boolean=false):File {
        require(category in listOf("mapping-missions","mapping-processing","mapping-layers"))
        require(java.util.UUID.fromString(id).toString()==id)
        val alias=File(context.filesDir,"$category/$id")
        // A missing card must never be mistaken for a new, empty mission.
        val link=runCatching { Os.readlink(alias.path) }.getOrNull()
        if(link!=null) { require(alias.isDirectory) { "Reinsert the mapping microSD card to continue." };return alias }
        if(alias.exists()) {
            val pointer=File(alias,"mapping-volume.json")
            if(pointer.isFile)require(File(org.json.JSONObject(pointer.readText()).getString("path")).isDirectory) { "Reinsert the mapping microSD card to continue." }
            return alias
        }
        val volume=card(context)
        require(!requireCard || volume!=null) { "Insert a writable microSD card for flights over 200 photos." }
        if(volume==null)return alias.apply { require(isDirectory || mkdirs()) }
        require(volume.usableSpace>MappingPhotoStore.FREE_SPACE_RESERVE) { "Mapping microSD card is full." }
        val target=File(volume,"$category/$id").apply { require(isDirectory || mkdirs()) }
        alias.parentFile!!.mkdirs()
        if(category=="mapping-missions") {
            // Frequent inventory/checkpoint writes stay on internal flash; bulk imagery uses SD.
            // This avoids a full microSD allocation and sync for every tiny metadata record.
            require(alias.mkdirs())
            listOf("originals","pending","cloud/storage").forEach { name->
                val physical=File(target,name).apply{require(isDirectory||mkdirs())}
                val reference=File(alias,name);reference.parentFile!!.mkdirs()
                Os.symlink(physical.absolutePath,reference.absolutePath)
            }
            MappingPhotoStore.writeJson(File(alias,"mapping-volume.json"),org.json.JSONObject().put("path",target.absolutePath))
        } else Os.symlink(target.absolutePath,alias.absolutePath)
        return alias
    }
    fun checkBudget(context:Context,additional:Long=0) {
        val root=card(context)?:context.filesDir
        require(root.usableSpace-additional>MappingPhotoStore.FREE_SPACE_RESERVE) {
            "Not enough mapping storage for the estimated working files. Free space in Local maps; originals are retained."
        }
    }
}
