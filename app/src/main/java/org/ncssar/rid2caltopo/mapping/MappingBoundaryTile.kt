package org.ncssar.rid2caltopo.mapping

import kotlin.math.floor
import kotlin.math.roundToLong

/** Geographic tile identity, independent of screen size, pan and fractional zoom. */
internal data class MappingBoundaryTile(val zoom:Int,val x:Int,val y:Int) {
    companion object {
        fun projected(zoom:Double,mercatorX:Long,mercatorY:Long,tileSize:Double):MappingBoundaryTile {
            val z=floor(zoom).toInt().coerceIn(0,29)
            val count=1L shl z
            // osmdroid rounds the screen rectangle to whole pixels. Recover the nearest
            // tile origin rather than flooring that rounding error into a neighbor.
            fun coordinate(value:Long)=Math.floorMod((value/tileSize).roundToLong(),count).toInt()
            return MappingBoundaryTile(z,coordinate(mercatorX),coordinate(mercatorY))
        }
    }
}
