package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Planning allowance, not a promise about JPEG compression or scene complexity. */
internal data class MappingStorageEstimate(val originals:Long,val temporary:Long,val products:Long) {
    val peakAdditionalBytes get()=originals+temporary+products
    fun projectedUsedFraction(totalBytes:Long,freeBytes:Long):Double =
        if(totalBytes<=0)Double.NaN else (totalBytes-freeBytes+peakAdditionalBytes).toDouble()/totalBytes
    fun warn(totalBytes:Long,freeBytes:Long)=projectedUsedFraction(totalBytes,freeBytes)>=.8

    data class BucketNeed(val bucket:String,val needBytes:Long,val pathHint:String)

    data class BucketAssessment(
        val bucket:String,
        val needBytes:Long,
        val freeBytes:Long,
        val reserveBytes:Long,
        val path:File,
        val label:String,
    ) {
        val short:Boolean get() = freeBytes - needBytes < reserveBytes
        fun message():String = MappingStorageBudget.message(bucket,label,needBytes,freeBytes,reserveBytes)
        fun json():JSONObject = JSONObject()
            .put("bucket",bucket).put("needBytes",needBytes).put("freeBytes",freeBytes)
            .put("reserveBytes",reserveBytes).put("label",label).put("short",short)
            .put("pathHint",path.name)
    }

    data class Assessment(val buckets:List<BucketAssessment>) {
        val shortages:List<BucketAssessment> get() = buckets.filter { it.short }
        fun summary():String = if (shortages.isEmpty()) buckets.joinToString("; ") { it.message() }
            else shortages.joinToString("; ") { it.message() }
        fun json():JSONObject = JSONObject()
            .put("buckets",JSONArray(buckets.map { it.json() }))
            .put("shortages",JSONArray(shortages.map { it.json() }))
            .put("summary",summary())
    }

    fun needs(originalsRoot:File,scratchRoot:File,layersRoot:File):List<BucketNeed> = listOf(
        BucketNeed("Original photos",originals,originalsRoot.path),
        BucketNeed("Mapping work space",temporary,scratchRoot.path),
        // Final tiles + portable share.zip live under the products / layers volume.
        BucketNeed("Map products",products,layersRoot.path),
    )

    companion object {
        fun plan(mission:JSONObject,photos:Int):MappingStorageEstimate {
            require(photos in 1..10000)
            val camera=mission.getJSONObject("cameraSpecifications")
            val width=camera.getInt("widthPixels");val height=camera.getInt("heightPixels")
            val gsd=mission.getJSONObject("parameters").getDouble("desiredGroundResolutionCmPerPixel")/100
            val ring=mission.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
            val latitude=(0 until ring.length()).map{ring.getJSONArray(it).getDouble(1)}.average()
            val scale=cos(Math.toRadians(latitude))
            val xs=(0 until ring.length()).map{MappingGeometry.east(ring.getJSONArray(it).getDouble(0))*scale}
            val ys=(0 until ring.length()).map{MappingGeometry.north(ring.getJSONArray(it).getDouble(1))*scale}
            // Include an image footprint beyond the boundary and the actual XYZ raster resolution.
            val pixel=MappingGeometry.WORLD*scale/(256.0*(1 shl MappingGeometry.zoom(gsd,latitude)))
            val area=(xs.max()-xs.min()+width*gsd*2)*(ys.max()-ys.min()+height*gsd*2)
            val raster=ceil(area/(pixel*pixel)*4*4/3).toLong()
            // Surface memory/model term scales with 1/cell²: Fine (0.1 m) is ~4x Standard (0.2 m); invalid values read as Standard.
            val cell=MappingMissionDefaults.cell(mission.getJSONObject("parameters"))
            val model=ceil(area/(cell*cell)*64).toLong()
            val regional=MappingRegionProcessor.needsRegions(mission,photos)
            val originals=photos*12L*1024*1024*2 // JPEG allowance plus controller intake copy on SD (no hard links).
            val raw=min(photos,MappingRegionPlan.LIMIT)*width.toLong()*height*4
            val products=raster*2+model // Final tiles, portable ZIP and regional geometry.
            val temporary=raw+if(regional)(raster+model)*3 else model
            return MappingStorageEstimate(originals,temporary,products)
        }

        fun assess(
            context:Context,
            estimate:MappingStorageEstimate,
            originalsRoot:File,
            scratchRoot:File,
            layersRoot:File,
            reserveBytes:Long=MappingPhotoStore.FREE_SPACE_RESERVE,
        ):Assessment {
            val needs=estimate.needs(originalsRoot,scratchRoot,layersRoot)
            val paths=listOf(originalsRoot,scratchRoot,layersRoot)
            return Assessment(needs.mapIndexed { index, need ->
                val path=paths[index]
                val free=MappingStorageBudget.freeBytes(path)
                val label=MappingStorageBudget.label(context,path)
                BucketAssessment(need.bucket,need.needBytes,free,reserveBytes,path,label)
            })
        }
    }
}
