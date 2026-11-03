package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.File

/** 2D-only ortho fill. Measured heights are never modified; the 3D surface and GLB stay measured-only. */
internal object MappingSurfaceFill {
    const val MASK_NAME="estimated-mask.bin"
    private val reportKeys=listOf("estimatedFillCells","estimatedFillAssignmentCells","estimatedFillFraction","fillRadiusMeters")

    class Result(val heights:FloatArray,val mask:ByteArray,val estimatedCells:Int,val radiusMeters:Double)

    /** Median of finite heights, or NaN when there are none. Used as the far-field plane. Primitive sort: no boxing. */
    fun planeHeight(heights:FloatArray):Double {
        val finite=FloatArray(heights.count(Float::isFinite));var n=0
        for(z in heights)if(z.isFinite())finite[n++]=z
        if(finite.isEmpty())return Double.NaN
        finite.sort();return finite[finite.size/2].toDouble()
    }

    /** radiusMeters<=0 selects the native 25 m default. */
    fun build(surface:MappingSurface,radiusMeters:Double=0.0):Result {
        val native=MappingSurfaceNative.buildOrthoFill(surface.heights,surface.width,surface.height,surface.cell,true,radiusMeters,planeHeight(surface.heights))
        return Result(native.heights,native.mask,native.estimatedCells,native.fillRadiusMeters)
    }

    /** Raw LSB-first bits, one per surface cell in surface.f32 order. Dimensions are in surface.json. */
    fun writeMask(directory:File,mask:ByteArray) {
        directory.mkdirs()
        val target=File(directory,MASK_NAME);val partial=File(directory,"$MASK_NAME.partial")
        try {
            partial.outputStream().buffered().use{it.write(mask)}
            require(!target.exists()||target.delete()){"Cannot replace the estimated-area mask."}
            require(partial.renameTo(target)){"Cannot save the estimated-area mask."}
        } finally {partial.delete()}
    }
    fun removeMask(directory:File){File(directory,MASK_NAME).delete();File(directory,"$MASK_NAME.partial").delete()}

    /** Fraction is estimated cells inside the assignment over assignment cells; absent keys mean fill was off. */
    fun putReport(target:JSONObject,fill:Result?,assignmentEstimated:Int,assignmentCells:Int) {
        reportKeys.forEach(target::remove)
        target.put("fillUnknown2DAreas",fill!=null)
        if(fill==null)return
        target.put("estimatedFillCells",fill.estimatedCells).put("estimatedFillAssignmentCells",assignmentEstimated)
            .put("estimatedFillFraction",assignmentEstimated.toDouble()/assignmentCells.coerceAtLeast(1)).put("fillRadiusMeters",fill.radiusMeters)
    }

    fun percentLabel(fraction:Double)=if(fraction>0&&fraction<.01)"<1%" else "${Math.round(fraction*100)}%"
}
