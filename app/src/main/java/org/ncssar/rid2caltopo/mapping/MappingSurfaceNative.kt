package org.ncssar.rid2caltopo.mapping

/** Bundled CPU bundle adjustment. No subprocess, socket, cloud or photo mutation. */
internal object MappingSurfaceNative {
    private val available=runCatching{System.loadLibrary("r2c_mapping_surface")}.isSuccess
    fun requireAvailable(){require(available){"This build does not contain the local surface engine."}}
    interface Progress {
        fun iteration(iteration:Int,cost:Double):Boolean
        fun connectivity(round:Int,groupByPhoto:IntArray,observationsByPhoto:IntArray,exclusionByPhoto:IntArray) {}
    }
    interface StageProgress {
        fun stage(label:String,fraction:Double):Boolean
        fun connectivity(round:Int,groups:IntArray,support:IntArray,exclusions:IntArray) {}
    }
    class Reconstruction(val adjustment:DoubleArray,val heights:FloatArray,val grid:DoubleArray,val statistics:IntArray)
    external fun reconstructStage(cameras:DoubleArray,lasers:DoubleArray,pitch:DoubleArray,
        width:Int,height:Int,focal:Double,paths:Array<String>,adjustment:DoubleArray,
        progress:StageProgress):Reconstruction
    external fun sampleRGBA(rgba:ByteArray,width:Int,height:Int,factor:Int):ByteArray
    // cameras: ENU centre xyz, row-major world-to-camera rotation (9), RTK sigma xyz.
    // A new JNI symbol prevents accidentally using the previous yaw-only runtime ABI.
    external fun adjustAttitude(cameras:DoubleArray,observations:DoubleArray,pointCount:Int,width:Int,height:Int,
        focal:Double,laserTargets:DoubleArray,progress:Progress):DoubleArray
    // Pixel-centre orthographic projection through the reconstructed DSM and calibrated cameras.
    external fun render(surface:FloatArray,gridWidth:Int,gridHeight:Int,gridX:Double,gridY:Double,cell:Double,
        cameras:DoubleArray,intrinsics:DoubleArray,imageWidth:Int,imageHeight:Int,rawFiles:Array<String>,
        tileX:Double,tileY:Double,step:Double,output:IntArray):Int
    /** Original traversal retained for exact on-device renderer regression checks. */
    external fun renderReference(surface:FloatArray,gridWidth:Int,gridHeight:Int,gridX:Double,gridY:Double,cell:Double,
        cameras:DoubleArray,intrinsics:DoubleArray,imageWidth:Int,imageHeight:Int,rawFiles:Array<String>,
        tileX:Double,tileY:Double,step:Double,output:IntArray):Int
    external fun prepareProjection(surface:FloatArray,gridWidth:Int,gridHeight:Int,gridX:Double,gridY:Double,cell:Double,
        cameras:DoubleArray,intrinsics:DoubleArray,imageWidth:Int,imageHeight:Int,rawFiles:Array<String>,progress:Progress)
    external fun refine(surface:FloatArray,width:Int,height:Int,cell:Double)
    external fun faces(surface:FloatArray,width:Int,height:Int,stride:Int,ids:IntArray):IntArray
    external fun releasePixels()
}
