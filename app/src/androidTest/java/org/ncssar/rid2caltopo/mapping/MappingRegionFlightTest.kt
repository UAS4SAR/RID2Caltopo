package org.ncssar.rid2caltopo.mapping

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Opt-in stress test using distinct retained exposures; never overwrites the source flights. */
@RunWith(AndroidJUnit4::class)
class MappingRegionFlightTest {
    @Test fun reconstructOver200OriginalsOnMicroSd() {
        val args=InstrumentationRegistry.getArguments();val sources=args.getString("regionSources")?.split(',');assumeTrue(sources!=null)
        val id=args.getString("regionMission")?:error("Provide a new test mission UUID")
        require(UUID.fromString(id).toString()==id)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val roots=sources!!.map{File(context.filesDir,"mapping-missions/$it")}
        val originals=roots.flatMap{root->File(root,"originals").listFiles().orEmpty().filter{it.extension=="jpg"}.map{root to it}}.distinctBy{it.second.name}
        require(originals.size>200)
        val before=originals.associate{it.second to MappingRegionProcessor.sha(it.second)}
        val root=MappingVolume.directory(context,"mapping-missions",id,true)
        val mission=JSONObject(File(roots.first(),"mission.json").readText()).put("missionId",id).put("title","Regional processing test · multiple retained flights").put("processLocally",false)
            .put("testSourceMissions",JSONArray(sources)).put("testOnly",true)
        MappingPhotoStore.writeJson(File(root,"mission.json"),mission)
        val store=MappingPhotoStore(root)
        originals.forEach{(source,file)->val record=JSONObject(File(source,"inventory/${file.nameWithoutExtension}.json").readText());store.importOriginal(record.getString("name"),file,file.nameWithoutExtension)}
        if(args.getString("adoptRetainedGeometry")=="true") {
            // Bring this test fixture onto the new internal-metadata / SD-bulk layout.
            if(java.nio.file.Files.isSymbolicLink(root.toPath())) {
                require(mission.optBoolean("testOnly"))
                val physical=root.canonicalFile
                require(physical.path==File(MappingVolume.card(context),"mapping-missions/$id").canonicalPath)
                val stage=File(root.parentFile,".metadata-migration-$id").apply{mkdirs()}
                physical.listFiles().orEmpty().filter{it.name !in listOf("originals","pending","cloud")}.forEach { file->
                    file.copyRecursively(File(stage,file.name),overwrite=true)
                }
                listOf("originals","pending","cloud/storage").forEach { name->
                    val target=File(physical,name).apply{mkdirs()};val link=File(stage,name).apply{parentFile!!.mkdirs()}
                    android.system.Os.symlink(target.path,link.path)
                }
                MappingPhotoStore.writeJson(File(stage,"mapping-volume.json"),JSONObject().put("path",physical.path))
                require(root.delete());if(!stage.renameTo(root)){android.system.Os.symlink(physical.path,root.path);error("Cannot migrate test metadata")}
            }
            // Explicit migration of this isolated, interrupted test fixture only.
            val digests=originals.map{it.second.nameWithoutExtension}
            val key=id+"rtk-surface"+digests.sorted().joinToString()
            val work=File(context.filesDir,"mapping-processing/${UUID.nameUUIDFromBytes(key.toByteArray())}")
            val plan=MappingRegionProcessor.plan(mission,digests,root,{})
            plan.regions.forEachIndexed { i,region->
                val block=File(work,"regions/$i");val metadata=File(block,"surface/surface.json")
                if(metadata.isFile&&!File(block,"checkpoint.json").isFile&&!File(block,"geometry-checkpoint.json").isFile) {
                    val meta=JSONObject(metadata.readText());val adjustment=meta.getJSONObject("cameraAdjustment")
                    assertTrue(adjustment.getDouble("reprojectionRmsSamplePixels")<=1.5)
                    assertTrue(adjustment.getDouble("rtkCentreMaxShiftMeters")<=.5)
                    assertTrue(adjustment.getInt("laserHeightAnchors")>=6)
                    val used=JSONObject(File(block,"surface/cameras.json").readText()).getJSONArray("originalSha256")
                    val selected=(0 until used.length()).map{used.getString(it)}+adjustment.getJSONArray("excludedPhotos").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("sha256")}}
                    assertEquals(region.images.map{it.digest}.toSet(),selected.toSet())
                    val glb=File(block,"surface/surface.glb")
                    glb.inputStream().use { input->val header=ByteArray(12);assertEquals(12,input.read(header));val b=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        assertEquals(0x46546c67,b.int);assertEquals(2,b.int);assertEquals(glb.length(),b.int.toLong()) }
                    MappingSurfaceCheckpoint.save(plan.mission,region.images.map{it.digest},block,MappingSurfaceCheckpoint.representation(meta),{})
                    android.util.Log.i("MappingRegionFlight","Retained qualified geometry checkpoint for region ${i+1}")
                }
            }
        }
        if(args.getString("seedStaleAssembly")=="true") {
            require(mission.getBoolean("testOnly"))
            val key=id+"rtk-surface"+originals.map{it.second.nameWithoutExtension}.sorted().joinToString()
            val work=File(context.filesDir,"mapping-processing/${UUID.nameUUIDFromBytes(key.toByteArray())}")
            require(File(work,"regions/0/checkpoint.json").isFile)
            File(work,"surface").mkdirs()
            File(work,"surface/surface.glb").writeText("Obsolete isolated test assembly: must not be published")
        }
        context.startActivity(Intent(context,org.ncssar.rid2caltopo.app.R2CActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        MappingProcessingManager.start(context,id,originals.size,"microSD_region_stress_test","rtk-surface",originals.map{it.second.nameWithoutExtension})
        val deadline=System.currentTimeMillis()+90*60*1000;var previous=""
        while(MappingProcessingManager.state.value?.active==true&&System.currentTimeMillis()<deadline) {
            val state=MappingProcessingManager.state.value!!
            if(state.stage!=previous){previous=state.stage;android.util.Log.i("MappingRegionFlight",previous)}
            Thread.sleep(1000)
        }
        val state=MappingProcessingManager.state.value!!
        assertFalse(state.active);assertNull(state.error,state.error)
        val layer=state.layer!!;val product=LocalMappingTiles.directory(context,layer.id)
        assertTrue(product.canonicalPath.startsWith(MappingVolume.card(context)!!.canonicalPath+"/"))
        val report=JSONObject(File(product,"layer.json").readText())
        assertEquals(originals.size,report.getInt("selectedPhotoCount"))
        if(args.getString("expectResume")=="true")assertTrue("Completed region was not reused",report.getInt("resumedRegionCount")>0)
        val collection=JSONObject(File(product,"surface/collection.json").readText()).getJSONArray("regions")
        assertTrue(collection.length()>1)
        assertFalse("Obsolete single model survived regional assembly",File(product,"surface/surface.glb").exists())
        (0 until collection.length()).forEach { i->
            assertTrue(File(product,"surface/regions/$i/surface.glb").length()>1000)
            assertTrue(collection.getJSONObject(i).getDouble("coreAcres")<=MappingRegionPlan.MAX_CORE_ACRES+1e-6)
            val meta=JSONObject(File(product,"surface/regions/$i/surface.json").readText())
            assertEquals(.4,meta.getDouble("meshSpacingMeters"),1e-9)
            val bounds=meta.getJSONObject("cameraAdjustment").getJSONArray("processingRegionBoundsEnu")
            File(product,"surface/regions/$i/surface.glb").inputStream().use { input->
                val header=ByteArray(20);java.io.DataInputStream(input).readFully(header)
                val length=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)
                require(length in 1..10_000_000);val bytes=ByteArray(length);java.io.DataInputStream(input).readFully(bytes)
                val positions=JSONObject(String(bytes)).getJSONArray("accessors").getJSONObject(0)
                val min=positions.getJSONArray("min");val max=positions.getJSONArray("max")
                assertTrue(min.getDouble(0)>=bounds.getDouble(0)-.01&&max.getDouble(0)<=bounds.getDouble(2)+.01)
                assertTrue(-max.getDouble(2)>=bounds.getDouble(1)-.01&&-min.getDouble(2)<=bounds.getDouble(3)+.01)
            }
        }
        assertTrue(LocalMappingTiles.indices(product).isNotEmpty())
        ZipFile(File(product,"share.zip")).use{zip->assertNotNull(zip.getEntry("mapping-layers/${layer.id}/surface/collection.json"))}
        before.forEach{(file,sha)->assertEquals(file.path,sha,MappingRegionProcessor.sha(file))}
        File(root,"test-result.json").writeText(JSONObject().put("layerId",layer.id).put("originalCount",originals.size).put("regions",collection.length()).put("sourceBytesUnchanged",true).toString(2))
        android.util.Log.i("MappingRegionFlight","PASS layer=${layer.id}, originals=${originals.size}, regions=${collection.length()}")
        MappingProcessingManager.dismiss()
    }
}
