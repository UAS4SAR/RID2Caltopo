package org.ncssar.rid2caltopo.mapping

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Explicit opt-in preview replacement only; does not run matching, stereo or tile projection. */
@RunWith(AndroidJUnit4::class)
class MappingSurfacePreviewTest {
    private fun sha(file:File):String {val hash=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{stream->val block=ByteArray(65536);while(true){val n=stream.read(block);if(n<0)break;hash.update(block,0,n)}};return LocalS3.hex(hash.digest())}
    @Test fun rebuildRetainedMixedPreviewAtFourTenths() {
        val args=InstrumentationRegistry.getArguments();val id=args.getString("previewLayer");assumeTrue(id!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val root=LocalMappingTiles.directory(context,id!!)
        val layerFile=File(root,"layer.json");val previous=JSONObject(layerFile.readText());val old=previous.getJSONObject("surfaceModel")
        val mission=File(context.filesDir,"mapping-missions/${previous.getString("missionId")}")
        val protected=(root.walkTopDown().filter{it.isFile&&it.extension=="png"}.toList()+listOf("surface.f32","cameras.json","sparse-points.ply").map{File(root,"surface/$it")}+
            mission.walkTopDown().filter{it.isFile&&it.parentFile?.name in listOf("originals","inventory")}.toList()+listOf(File(mission,"mission.json"),File(mission,"processing-status.json")))
        val before=protected.associate{it to sha(it)}
        val forecast=args.getString("forecastLayer")?.let{File(LocalMappingTiles.directory(context,it),"surface/surface.glb")};val forecastBefore=forecast?.let(::sha)
        val manager=context.getSystemService(PowerManager::class.java)
        val wake=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"R2C:PreviewTest").apply{acquire(15*60*1000L)}
        var cancelled=false;val lease=MappingWorkRuntime.begin({cancelled=true},true)
        val start=System.nanoTime()
        try {
            val result=MappingSurfacePreview.rebuild(context,id,{stage,_ ->android.util.Log.i("MappingPreviewTest",stage)},{check(!cancelled)})
            val model=JSONObject(result.surfaceModel)
            assertEquals(.4,model.getDouble("meshSpacingMeters"),1e-9)
            assertEquals(old.getDouble("surfaceCellMeters"),model.getDouble("surfaceCellMeters"),0.0)
            assertTrue(model.getInt("vertexCount")>old.getInt("vertexCount"));assertTrue(model.getInt("triangleCount")>old.getInt("triangleCount"))
            val current=JSONObject(layerFile.readText())
            for(key in listOf("cameraAdjustment","surfaceReconstruction","photoCount","originalSha256","processingSeconds","tileCount","bounds"))assertEquals(key,previous.get(key).toString(),current.get(key).toString())
            before.forEach{(file,digest)->assertEquals(file.path,digest,sha(file))}
            forecast?.let{assertEquals(forecastBefore,sha(it))}
            ZipFile(File(root,"share.zip")).use{zip->val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use{it.readText()})
                val layers=manifest.getJSONArray("mapping_layers");assertEquals(.4,(0 until layers.length()).map{layers.getJSONObject(it)}.single{it.getString("layerId")==id}.getJSONObject("surfaceModel").getDouble("meshSpacingMeters"),1e-9)
                val entries=manifest.getJSONArray("surface_entries");for(i in 0 until entries.length()){val entry=entries.getJSONObject(i)
                    val bytes=zip.getInputStream(zip.getEntry(entry.getString("path"))).use{it.readBytes()};assertEquals(entry.getLong("bytes"),bytes.size.toLong())
                    assertEquals(entry.getString("sha256"),LocalS3.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))}}
            android.util.Log.i("MappingPreviewTest","Preview verified: ${model}; seconds=${(System.nanoTime()-start)/1_000_000_000}")
        } finally {MappingWorkRuntime.finish(lease);if(wake.isHeld)wake.release()}
    }
}
