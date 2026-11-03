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
    /** Filled heights (measured where finite, estimated where filled, NaN where empty), 1-bit LSB-first estimated mask, and 6 stats:
     *  estimatedCells, known/estimated/empty fractions, elapsedMs, fillRadiusMeters. */
    class OrthoFill(val heights:FloatArray,val mask:ByteArray,val stats:DoubleArray) {
        val estimatedCells get()=finiteCount(stats.getOrElse(0){Double.NaN})
        val elapsedMs get()=finiteCount(stats.getOrElse(4){Double.NaN})
        val fillRadiusMeters get()=stats.getOrElse(5){Double.NaN}.takeIf{it.isFinite()}?:0.0
    }
    /** Int coercion that never traps on NaN/Infinity (matches iOS MappingNativeEngine.finiteCount). */
    fun finiteCount(value:Double):Int =
        if(!value.isFinite()) 0 else value.toLong().coerceIn(0L,Int.MAX_VALUE.toLong()).toInt()
    fun percentInt(fraction:Double):Int =
        if(!fraction.isFinite()) 0 else (fraction*100.0).toInt().coerceIn(0,100)
    /** surfaceCellMeters<=0 selects the native 0.2 m default; clip = west,south,east,north in the reconstruction's ENU frame (optional). */
    @JvmOverloads fun reconstructStage(cameras:DoubleArray,lasers:DoubleArray,pitch:DoubleArray,
        width:Int,height:Int,focal:Double,paths:Array<String>,adjustment:DoubleArray,
        progress:StageProgress,surfaceCellMeters:Double=0.0,clip:DoubleArray?=null):Reconstruction =
        reconstructStageWithCell(cameras,lasers,pitch,width,height,focal,paths,adjustment,progress,surfaceCellMeters,clip)
    private external fun reconstructStageWithCell(cameras:DoubleArray,lasers:DoubleArray,pitch:DoubleArray,
        width:Int,height:Int,focal:Double,paths:Array<String>,adjustment:DoubleArray,
        progress:StageProgress,surfaceCellMeters:Double,clip:DoubleArray?):Reconstruction
    /** Never modifies [measured]. enabled=false copies measured values. planeHeight non-finite means no global plane. */
    external fun buildOrthoFill(measured:FloatArray,width:Int,height:Int,cell:Double,enabled:Boolean,fillRadiusMeters:Double,planeHeight:Double):OrthoFill
    /** Same as [render], but heights come from [fill] where [measured] is NaN; measured heights alone drive occlusion. */
    external fun renderFill(measured:FloatArray,fill:FloatArray,gridWidth:Int,gridHeight:Int,gridX:Double,gridY:Double,cell:Double,
        cameras:DoubleArray,intrinsics:DoubleArray,imageWidth:Int,imageHeight:Int,rawFiles:Array<String>,
        tileX:Double,tileY:Double,step:Double,output:IntArray):Int
    external fun prepareProjectionFill(measured:FloatArray,fill:FloatArray,gridWidth:Int,gridHeight:Int,gridX:Double,gridY:Double,cell:Double,
        cameras:DoubleArray,intrinsics:DoubleArray,imageWidth:Int,imageHeight:Int,rawFiles:Array<String>,progress:Progress)
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
