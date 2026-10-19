package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

@RunWith(AndroidJUnit4::class)
class MappingSurfaceInterpolationTest {
    @Test fun qualifiedSparseSurfaceSurvivesWhenNoDenseStereoBaselineIsEligible(){
        require(OpenCVLoader.initLocal())
        val r=MappingCameraGeometry.rotation(0.0,-90.0,0.0)
        val centres=listOf(-2.0,0.0,2.0).flatMap{x->listOf(-2.0,2.0).map{y->doubleArrayOf(x,y,60.0)}}
        val frames=centres.mapIndexed{i,c->SurfaceFrame(java.io.File("unused-$i.jpg"),
            MappingPose(39.0,-121.0,60.0,0.0,"synthetic",82.0),1008,756,c)}
        val points=(-24..24 step 2).flatMap{y->(-24..24 step 2).flatMap{x->listOf(x.toDouble(),y.toDouble(),0.0)}}.toDoubleArray()
        val model=SurfaceAlignment(frames,centres.flatMap{r.toList()+it.toList()}.toDoubleArray(),
            doubleArrayOf(600.0,503.5,377.5,0.0,0.0),points,0.0,0.0,0.0,1.0,1008,756,org.json.JSONObject())
        val surface=MappingDenseSurface.run(model,{_,_->},{})
        assertEquals(0,surface.report.getInt("stereoPairs"))
        assertEquals(0,surface.report.getInt("measuredStereoCells"))
        assertTrue(surface.report.getBoolean("limitedStereoSupport"))
        assertTrue(surface.report.getInt("sparse3dInterpolatedCells")>500)
        assertTrue(surface.heights.filter(Float::isFinite).all{kotlin.math.abs(it)<.001f})
        assertTrue(surface.heights.first().isNaN())
    }
    @Test fun interpolatesMeasuredSlopingTriangleWithoutExtrapolation(){
        require(OpenCVLoader.initLocal());val grid=FloatArray(32*32){Float.NaN}
        val points=doubleArrayOf(.25,.25,.075,2.75,.25,.325,.25,2.75,.575)
        assertTrue(MappingSurfaceInterpolation.fill(grid,32,32,0.0,0.0,.25,points,{})>0)
        assertEquals(.375f,grid[5*32+5],.0001f);assertTrue(grid[20*32+20].isNaN())
    }
    @Test fun refusesLongGapsAndAbruptHeightSteps(){
        require(OpenCVLoader.initLocal());val grid=FloatArray(40*40){Float.NaN}
        assertEquals(0,MappingSurfaceInterpolation.fill(grid,40,40,0.0,0.0,.25,doubleArrayOf(.25,.25,0.0,8.0,.25,0.0,.25,8.0,0.0),{}))
        assertEquals(0,MappingSurfaceInterpolation.fill(grid,40,40,0.0,0.0,.25,doubleArrayOf(.25,.25,0.0,2.75,.25,0.0,.25,2.75,10.0),{}))
    }
}
