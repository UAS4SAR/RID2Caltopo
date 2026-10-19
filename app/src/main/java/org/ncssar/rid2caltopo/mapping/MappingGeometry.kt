package org.ncssar.rid2caltopo.mapping

import java.io.DataInputStream
import java.io.File
import kotlin.math.*

/** A local flat-plane prior; matching later refines placement, never the original JPEG. */
internal data class MappingPose(val latitude:Double,val longitude:Double,val height:Double,val yaw:Double,
    val capturedAt:String,val fov:Double,val absoluteAltitude:Double?=null,val rtkFlag:Int?=null,
    val rtkSigmaEast:Double?=null,val rtkSigmaNorth:Double?=null,val rtkSigmaHeight:Double?=null,val laserTarget:MappingLaserTarget?=null,
    val pitch:Double=-90.0,val roll:Double=0.0) {
    companion object {
        fun read(file:File,fov:Double,allowOblique:Boolean=false):MappingPose {
            val attributes=mutableMapOf<String,String>()
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readUnsignedShort()==0xffd8){"A selected original is not JPEG."}
                var inspected=2
                val attribute=Regex("(?:drone-dji|exif|tiff):([A-Za-z0-9]+)\\s*=\\s*['\"]([^'\"]*)['\"]")
                while(inspected<2*1024*1024){
                    require(input.readUnsignedByte()==0xff);var marker=input.readUnsignedByte()
                    while(marker==0xff)marker=input.readUnsignedByte()
                    if(marker==0xda||marker==0xd9)break
                    if(marker==0xd8||marker in 0xd0..0xd7||marker==1)continue
                    val length=input.readUnsignedShort()-2;require(length in 0..65533)
                    val bytes=ByteArray(length);input.readFully(bytes);inspected+=length+4
                    if(marker==0xe1)attribute.findAll(bytes.toString(Charsets.UTF_8)).forEach{attributes[it.groupValues[1]]=it.groupValues[2]}
                }
            }
            fun number(key:String)=attributes[key]?.toDoubleOrNull()?.takeIf{it.isFinite()}
                ?: throw IllegalArgumentException("A photo lacks DJI $key metadata.")
            val pitch=number("GimbalPitchDegree")
            require(pitch in -93.0..-42.0){"Camera pitch is outside the controlled nadir/oblique geometry range."}
            require(allowOblique||abs(pitch+90)<3){"Local flat 2D stitching needs straight-down photos; use the RTK surface engine for obliques."}
            // At nadir, DJI can represent the same attitude as yaw+180 / roll+180.
            // Include camera roll; flight yaw is not an additional camera rotation.
            val lat=number("GpsLatitude");val lon=number("GpsLongitude");val height=number("RelativeAltitude")
            require(lat in -85.0..85.0&&lon in -180.0..180.0&&height in 5.0..500.0)
            val roll=if(abs(pitch+90)>=3)number("GimbalRollDegree") else attributes["GimbalRollDegree"]?.toDoubleOrNull()?.takeIf{it.isFinite()}?:0.0
            fun optional(key:String)=attributes[key]?.toDoubleOrNull()?.takeIf{it.isFinite()}
            val laser=if(attributes["LRFStatus"]=="Normal")runCatching{MappingLaserTarget(number("LRFTargetLat"),number("LRFTargetLon"),number("LRFTargetAbsAlt"),number("LRFTargetDistance"))}.getOrNull() else null
            return MappingPose(lat,lon,height,number("GimbalYawDegree")%360,
                attributes["DateTimeOriginal"]?:attributes["DateTime"]?:runCatching{android.media.ExifInterface(file.path).getAttribute("DateTimeOriginal")}.getOrNull()?:"Capture time unavailable",fov,
                optional("AbsoluteAltitude"),attributes["RtkFlag"]?.toIntOrNull(),optional("RtkStdLon"),optional("RtkStdLat"),optional("RtkStdHgt"),laser,pitch,roll)
        }
    }
}
internal data class MappingLaserTarget(val latitude:Double,val longitude:Double,val altitude:Double,val distance:Double)

internal object MappingGeometry {
    const val R=6378137.0
    const val WORLD=2*Math.PI*R
    fun east(lon:Double)=R*Math.toRadians(lon)
    fun north(lat:Double)=R*ln(tan(Math.PI/4+Math.toRadians(lat)/2))
    fun longitude(x:Double)=Math.toDegrees(x/R)
    fun latitude(y:Double)=Math.toDegrees(atan(sinh(y/R)))
    fun gsd(pose:MappingPose,width:Int,height:Int)=2*pose.height*tan(Math.toRadians(pose.fov/2))/hypot(width.toDouble(),height.toDouble())
    fun zoom(gsd:Double,latitude:Double)=ceil(ln(cos(Math.toRadians(latitude))*WORLD/(256*gsd))/ln(2.0)).toInt().coerceIn(17,23)
    /** Image right/down to projected east/north about the optical centre. */
    fun basis(pose:MappingPose,width:Int,height:Int):DoubleArray {
        require(abs(pose.pitch+90)<3){"A flat affine footprint cannot represent oblique imagery."}
        val scale=gsd(pose,width,height)/cos(Math.toRadians(pose.latitude));val angle=Math.toRadians(pose.yaw+pose.roll)
        return doubleArrayOf(cos(angle)*scale,-sin(angle)*scale,-sin(angle)*scale,-cos(angle)*scale)
    }
}
