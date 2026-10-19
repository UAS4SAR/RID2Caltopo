package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File

/** Opt-in actual-flight geometry check: all computation executes in R2C's process. */
@RunWith(AndroidJUnit4::class)
class MappingSurfaceFlightTest {
    @Test fun qualifyRtkCameraAdjustment(){
        val args=InstrumentationRegistry.getArguments();val id=args.getString("surfaceMission");assumeTrue(id!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(OpenCVLoader.initLocal());Core.setNumThreads(2)
        val root=File(context.filesDir,"mapping-missions/$id");val input=JSONObject(File(root,"processing-input.json").readText()).getJSONArray("sha256")
        val digests=if(args.getString("surfaceSelection")=="completed") MappingFlightRepository.completionDigests(root)
            else (0 until input.length()).map{input.getString(it)}
        assertEquals(args.getString("surfacePhotoCount")?.toInt() ?: 66,digests.size)
        val started=System.nanoTime();var previous=""
        val model=MappingSurfaceAlignment.run(JSONObject(File(root,"mission.json").readText()),digests,root,{stage,_ ->
            if(stage!=previous){previous=stage;android.util.Log.i("MappingSurfaceTest",stage)}},{})
        model.report.put("alignmentSeconds",(System.nanoTime()-started)/1_000_000_000)
        assertTrue(model.report.getDouble("rtkCentreMaxShiftMeters")<=.5)
        assertTrue(model.report.getDouble("reprojectionRmsSamplePixels")<=1.5)
        File(root,"surface-alignment-report.json").writeText(model.report.toString(2))
        val data=JSONObject().put("cameras",org.json.JSONArray(model.cameras.toList())).put("intrinsics",org.json.JSONArray(model.intrinsic.toList()))
            .put("originX",model.originX).put("originY",model.originY).put("originAltitude",model.originAltitude).put("mercatorScale",model.mercatorScale)
        val points=org.json.JSONArray();model.points.toList().chunked(3).filter{it.all(Double::isFinite)}.forEach{points.put(org.json.JSONArray(it))};data.put("points",points)
        File(root,"surface-alignment-geometry.json").writeText(data.toString())
        android.util.Log.i("MappingSurfaceTest","RTK adjustment verified: "+model.report.toString())
    }
}
