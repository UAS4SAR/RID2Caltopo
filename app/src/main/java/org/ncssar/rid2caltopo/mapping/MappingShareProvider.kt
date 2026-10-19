package org.ncssar.rid2caltopo.mapping

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Explicit URI grants expose only published map packages/models, including on removable media. */
class MappingShareProvider:ContentProvider() {
    override fun onCreate()=true
    private fun file(uri:Uri):File {
        val parts=uri.pathSegments
        if (parts.size == 3 && parts[0] == "originals") {
            require(parts[2].endsWith(".jpg"))
            return MappingOriginalFiles.photo(requireNotNull(context).filesDir, parts[1], parts[2].removeSuffix(".jpg")).file
        }
        require(parts.size >= 2 && UUID.fromString(parts[0]).toString() == parts[0])
        val relative = parts.drop(1).joinToString("/")
        require(relative in listOf("share.zip", "map.tif", "surface.glb") || relative.matches(Regex("regions/[0-9]{1,3}/surface\\.glb")))
        val root=LocalMappingTiles.directory(requireNotNull(context),parts[0])
        require(File(root,"layer.json").isFile)
        return File(root, if (relative in listOf("share.zip", "map.tif")) relative else "surface/$relative").also{require(it.isFile)}
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {require(mode=="r");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)}
    override fun getType(uri:Uri)=if(uri.pathSegments.firstOrNull()=="originals")"image/jpeg" else if(uri.lastPathSegment=="map.tif")"image/tiff" else if(uri.lastPathSegment=="surface.glb")"model/gltf-binary" else "application/zip"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val f=file(uri)
        val parts=uri.pathSegments
        val displayName=if(parts.firstOrNull()=="originals") MappingOriginalFiles.photo(requireNotNull(context).filesDir,parts[1],parts[2].removeSuffix(".jpg")).name else if (f.name in listOf("share.zip", "map.tif")) {
            val manifest=org.json.JSONObject(File(f.parentFile,"layer.json").readText())
            val assignment=manifest.optString("assignmentName").ifBlank { manifest.optString("title").substringBefore(" · Visible").substringBefore(" · IR") }
            if(f.name == "map.tif") MappingArchiveName.geoTiffName(assignment) else MappingArchiveName.fileName(assignment)
        } else f.name
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){OpenableColumns.DISPLAY_NAME->displayName;OpenableColumns.SIZE->f.length();else->null}})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
