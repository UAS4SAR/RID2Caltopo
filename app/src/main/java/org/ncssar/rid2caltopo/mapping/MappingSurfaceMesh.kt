package org.ncssar.rid2caltopo.mapping

import kotlin.math.ceil
import kotlin.math.max

/** Prefer the same physical spacing for every preview. Unknown padded cells cost no vertices. */
internal object MappingSurfaceMesh {
    const val TARGET_SPACING_METERS=.4
    const val MAX_VERTICES=250000
    fun stride(heights:FloatArray,width:Int,height:Int,cell:Double):Int {
        require(width>0&&height>0&&heights.size.toLong()==width.toLong()*height&&cell.isFinite()&&cell>0)
        var stride=max(1,ceil(TARGET_SPACING_METERS/cell-1e-9).toInt())
        while(true){var count=0
            for(row in 0 until height step stride)for(col in 0 until width step stride)if(heights[row*width+col].isFinite())count++
            if(count<=MAX_VERTICES)return stride
            stride++
        }
    }
}
