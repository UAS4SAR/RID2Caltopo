package org.ncssar.rid2caltopo.mapping

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.File
import java.nio.ByteBuffer

/** OS adapters decode originals; all sampling and reconstruction run in the common native engine. */
internal object MappingSharedReconstruction {
    fun callback(progress:(String,Double)->Unit,check:()->Unit,connected:(Int,IntArray,IntArray,IntArray)->Unit={_,_,_,_->})=object:MappingSurfaceNative.StageProgress {
        override fun connectivity(round:Int,groups:IntArray,support:IntArray,exclusions:IntArray) {check();connected(round,groups,support,exclusions)}
        override fun stage(label:String,fraction:Double):Boolean {check();progress(label,fraction);return true}
    }
    fun <T> withSamples(frames:List<SurfaceFrame>,w:Int,h:Int,progress:(String,Double)->Unit,check:()->Unit,fraction:Double=.04,run:(Array<String>)->T):T {
        val directory=File.createTempFile("shared-samples-","",frames.first().file.canonicalFile.parentFile!!.parentFile).apply{delete();require(mkdir())}
        try {
            val paths=frames.mapIndexed{i,frame ->
                check();progress("Preparing full-frame image ${i+1}/${frames.size}",fraction)
                val bitmap=BitmapFactory.decodeFile(frame.file.path,BitmapFactory.Options().apply {
                    inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)
                })?:error("Cannot decode original image.")
                val gray=try {
                    require(bitmap.width==frame.width&&bitmap.height==frame.height&&bitmap.rowBytes==bitmap.width*4)
                    val factor=bitmap.width/w
                    require(bitmap.width%w==0&&bitmap.height/factor==h&&bitmap.height%factor==0)
                    val rgba=ByteArray(bitmap.byteCount);bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
                    MappingSurfaceNative.sampleRGBA(rgba,bitmap.width,bitmap.height,factor)
                }finally{bitmap.recycle()}
                check();File(directory,"$i.gray").apply{writeBytes(gray)}.absolutePath
            }.toTypedArray()
            return run(paths)
        }finally{directory.deleteRecursively()}
    }
}
