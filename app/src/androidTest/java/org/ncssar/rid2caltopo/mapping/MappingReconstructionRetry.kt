package org.ncssar.rid2caltopo.mapping
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
/** Explicit operator-authorized retry; never required by automated release gates. */
@RunWith(AndroidJUnit4::class)
class MappingReconstructionRetry {
    @Test fun verifySampleStorage() {
        val id=InstrumentationRegistry.getArguments().getString("retryMappingMission");assumeTrue(id!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"mapping-missions/$id")
        val plan=JSONObject(File(root,"mission.json").readText())
        val fov=plan.getJSONObject("cameraSpecifications").getDouble("diagonalFOVDegrees")
        val originals=File(root,"originals").listFiles()!!.filter{it.extension=="jpg"}.sortedBy{it.name}.take(3)
        assertEquals(3,originals.size)
        val frames=originals.map{file->val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(file.path,options)
            SurfaceFrame(file,MappingPose.read(file,fov,true),options.outWidth,options.outHeight,doubleArrayOf())}
        var directory:File?=null
        MappingSharedReconstruction.withSamples(frames,1008,756,{_,_->},{}){paths->
            directory=File(paths.first()).parentFile
            assertTrue(directory!!.canonicalPath.startsWith(originals.first().canonicalFile.parentFile!!.parentFile!!.canonicalPath+"/"))
            MappingVolume.card(context)?.let{card->assertTrue(directory!!.canonicalPath.startsWith(card.canonicalPath+"/"))}
            paths.forEach{assertEquals(1008L*756,File(it).length())}
        }
        assertFalse(directory!!.exists())
    }

    @Test fun retrySavedFlight()= runBlocking {
        val id=InstrumentationRegistry.getArguments().getString("retryMappingMission")
        assumeTrue(id!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=InstrumentationRegistry.getArguments().getString("reuseSavedSurface")
        if(source!=null){
            require(UUID.fromString(source).toString()==source)
            val root=MappingVolume.directory(context,"mapping-missions",id!!)
            val mission=JSONObject(File(root,"mission.json").readText())
            val input=JSONObject(File(root,"processing-input.json").readText()).getJSONArray("sha256")
            val digests=(0 until input.length()).map{input.getString(it)}
            val plan=MappingRegionProcessor.plan(mission,digests,root,{})
            val layer=LocalMappingTiles.directory(context,source)
            val work=MappingVolume.directory(context,"mapping-processing",UUID.nameUUIDFromBytes((id+"rtk-surfaceall").toByteArray()).toString())
            require(!File(work,"regions").exists()){ "Existing work must be resumed instead of replaced" }
            plan.regions.forEachIndexed{i,region->
                val from=File(layer,"surface/regions/$i");val meta=JSONObject(File(from,"surface.json").readText())
                val camera=JSONObject(File(from,"cameras.json").readText()).getJSONArray("originalSha256")
                val used=(0 until camera.length()).map{camera.getString(it)}
                assertEquals(region.images.map{it.digest}.toSet(),used.toSet())
                assertEquals(mission.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).toString(),meta.getJSONArray("assignmentBoundary").toString())
                assertTrue(meta.getJSONObject("cameraAdjustment").getDouble("reprojectionRmsSamplePixels")<=1.5)
                val block=File(work,"regions/$i")
                from.copyRecursively(File(block,"surface"),overwrite=false)
                MappingSurfaceCheckpoint.save(MappingRegionProcessor.regionMission(plan.mission,region),region.images.map{it.digest},block,MappingSurfaceCheckpoint.representation(meta),{})
            }
        }
        MappingProcessingManager.start(context,id!!,engine="rtk-surface")
        withTimeout(45*60*1000L){while(MappingProcessingManager.state.value?.active==true)delay(1000)}
        val state=MappingProcessingManager.state.value
        assertNotNull(state);assertNull(state!!.error);assertNotNull(state.layer)
    }
}
