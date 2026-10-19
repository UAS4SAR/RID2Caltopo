package org.ncssar.rid2caltopo.mapping

import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object MappingGeoTiffExport {
    data class Result(val file: File, val zoom: Int, val byteCount: Long, val reducedDetail: Boolean)
    suspend fun export(root: File, minZoom: Int, maxZoom: Int, limitForCalTopo: Boolean = false): Result = withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()
        job.ensureActive()
        val destination=File(root,"map.tif")
        val selection=MappingGeoTiff.writeBest(root,destination,minZoom,maxZoom,
            maxBytes=if(limitForCalTopo)MappingGeoTiff.CALTOPO_MAX_BYTES else null,read={plan,x,y ->
            val file=File(root,"${plan.zoom}/$x/$y.png")
            if(!file.exists()) null else {
                // Bounds first: malformed imports must not trigger unbounded bitmap allocation.
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,bounds)
                require(bounds.outWidth==256 && bounds.outHeight==256){"Invalid map tile dimensions."}
                val bitmap=requireNotNull(BitmapFactory.decodeFile(file.path)){"Unreadable map tile."}
                try {
                    val pixels=IntArray(65536);bitmap.getPixels(pixels,0,256,0,0,256,256)
                    ByteArray(262144).also { bytes -> pixels.forEachIndexed { i,p ->
                        bytes[i*4]=(p ushr 16).toByte();bytes[i*4+1]=(p ushr 8).toByte();bytes[i*4+2]=p.toByte();bytes[i*4+3]=(p ushr 24).toByte()
                    } }
                } finally { bitmap.recycle() }
            }
        },check={job.ensureActive()})
        Result(destination,selection.plan.zoom,destination.length(),selection.plan.zoom<selection.fullZoom)
    }
}
