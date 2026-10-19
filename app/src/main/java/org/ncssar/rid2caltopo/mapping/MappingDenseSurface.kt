package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject

internal data class MappingSurface(val heights:FloatArray,val width:Int,val height:Int,val x:Double,val y:Double,val cell:Double,val report:JSONObject)

/** Dense matching, depth fusion and interpolation are shared with iOS in Reconstruction.cpp. */
internal object MappingDenseSurface {
    fun run(model:SurfaceAlignment,progress:(String,Double)->Unit,check:()->Unit):MappingSurface {
        MappingSurfaceNative.requireAvailable()
        val n=model.frames.size
        val solution=DoubleArray(13+n*12+model.points.size)
        solution[0]=n.toDouble();model.intrinsic.copyInto(solution,2);model.cameras.copyInto(solution,12);model.points.copyInto(solution,12+n*12)
        var approximate=model.report.optString("reconstructionMode")=="approximate-plane"
        if(approximate){solution[7]=model.report.getDouble("approximatePlaneHeightMeters");solution[solution.lastIndex]=model.report.getInt("projectionReferenceCode").toDouble()}
        val cameras=DoubleArray(n*15)
        model.frames.forEachIndexed{i,f -> f.centre.copyInto(cameras,i*15)
            MappingCameraGeometry.rotation(f.pose.yaw,f.pose.pitch,f.pose.roll).copyInto(cameras,i*15+3)
            for(j in 12..14)cameras[i*15+j]=1.0
        }
        val result=MappingSharedReconstruction.withSamples(model.frames,model.sampleWidth,model.sampleHeight,progress,check,.46){paths ->
            MappingSurfaceNative.reconstructStage(cameras,DoubleArray(n*3){Double.NaN},model.frames.map{it.pose.pitch}.toDoubleArray(),
                model.sampleWidth,model.sampleHeight,model.intrinsic[0],paths,solution,MappingSharedReconstruction.callback(progress,check))
        }
        if(result.adjustment.lastOrNull()?.let{it<0}==true){
            approximate=true
            val a=result.adjustment
            model.report.put("reconstructionMode","approximate-plane").put("approximatePlaneHeightMeters",a[7]).put("projectionReferenceCode",a.last().toInt())
                .put("reprojectionRmsSamplePixels",JSONObject.NULL).put("rtkCentreRmsMeters",JSONObject.NULL).put("rtkCentreMaxShiftMeters",JSONObject.NULL)
            a.copyInto(model.intrinsic,0,2,7);a.copyInto(model.cameras,0,12,12+n*12)
        }
        val z=result.heights;val g=result.grid
        val report=JSONObject().put("heightFusion","supported-upper-v3").put("acceptedStereoPairs",result.statistics[4])
            .put("measuredStereoCells",result.statistics[5]).put("surfaceCellMeters",g[4]).put("surfaceCells",z.size)
            .put("knownSurfaceCells",if(approximate)0 else z.count(Float::isFinite)).put("knownProjectionCells",z.count(Float::isFinite)).put("maximumInterpolationEdgeMeters",6)
            .put("minSurfaceHeightMeters",z.filter(Float::isFinite).min()).put("maxSurfaceHeightMeters",z.filter(Float::isFinite).max())
            .put("reconstructionMode",if(approximate)"approximate-plane" else "measured-surface")
            .put("surfaceType",if(approximate)"Estimated reference plane for image projection; no measured depth" else "visible surface including canopy; unknown cells retained; not a bare-earth terrain model")
        return MappingSurface(z,g[0].toInt(),g[1].toInt(),g[2],g[3],g[4],report)
    }
}
