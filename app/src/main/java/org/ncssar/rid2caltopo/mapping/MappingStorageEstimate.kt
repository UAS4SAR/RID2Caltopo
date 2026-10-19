package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import kotlin.math.*

/** Planning allowance, not a promise about JPEG compression or scene complexity. */
internal data class MappingStorageEstimate(val originals:Long,val temporary:Long,val products:Long) {
    val peakAdditionalBytes get()=originals+temporary+products
    fun projectedUsedFraction(totalBytes:Long,freeBytes:Long):Double =
        if(totalBytes<=0)Double.NaN else (totalBytes-freeBytes+peakAdditionalBytes).toDouble()/totalBytes
    fun warn(totalBytes:Long,freeBytes:Long)=projectedUsedFraction(totalBytes,freeBytes)>=.8
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
            val model=ceil(area/(.2*.2)*64).toLong()
            val regional=MappingRegionProcessor.needsRegions(mission,photos)
            val originals=photos*12L*1024*1024*2 // JPEG allowance plus controller intake copy on SD (no hard links).
            val raw=min(photos,MappingRegionPlan.LIMIT)*width.toLong()*height*4
            val products=raster*2+model // Final tiles, portable ZIP and regional geometry.
            val temporary=raw+if(regional)(raster+model)*3 else model
            return MappingStorageEstimate(originals,temporary,products)
        }
    }
}
