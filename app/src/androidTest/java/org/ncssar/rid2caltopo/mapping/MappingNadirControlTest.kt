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
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

/** Explicit, opt-in same-flight ablation. Parent originals, selections and results stay intact. */
@RunWith(AndroidJUnit4::class)
class MappingNadirControlTest {
    private fun sha(file:File):String {val hash=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val block=ByteArray(65536);while(true){val n=input.read(block);if(n<0)break;hash.update(block,0,n)}};return LocalS3.hex(hash.digest())}
    @Test fun reconstructNadirSubsetOfSavedMixedResult() {
        val args=InstrumentationRegistry.getArguments();val baselineId=args.getString("controlBaseline");assumeTrue(baselineId!=null)
        val controlId=args.getString("controlMission")?:error("An explicit new control mission ID is required.")
        require(UUID.fromString(controlId).toString()==controlId)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val baseline=LocalMappingTiles.directory(context,baselineId!!);val baselineReport=JSONObject(File(baseline,"layer.json").readText())
        val sourceId=baselineReport.getString("missionId");val source=File(context.filesDir,"mapping-missions/$sourceId")
        val sourceManifest=JSONObject(File(source,"mission.json").readText())
        require(!JSONObject(File(source,"processing-status.json").readText()).optBoolean("active"))
        val fov=sourceManifest.getJSONObject("cameraSpecifications").getDouble("diagonalFOVDegrees")
        val all=baselineReport.getJSONArray("originalSha256").let{a->(0 until a.length()).map{a.getString(it)}}
        val nadir=all.filter{digest->val pose=MappingPose.read(File(source,"originals/$digest.jpg"),fov,allowOblique=true);kotlin.math.abs(pose.pitch+90)<3}
        assertEquals(144,all.size);assertEquals(66,nadir.size)
        val supported=JSONObject(File(baseline,"surface/cameras.json").readText()).getJSONArray("originalSha256").let{a->(0 until a.length()).map{a.getString(it)}.toSet()}
        assertTrue(supported.containsAll(nadir))
        val protected=source.walkTopDown().filter{it.isFile&&it.parentFile?.name in listOf("originals","inventory")}.toList()+
            listOf("mission.json","processing-input.json","processing-status.json","received-batch.json").map{File(source,it)}+
            baseline.walkTopDown().filter{it.isFile}.toList()
        val before=protected.associate{it to sha(it)}
        val root=File(context.filesDir,"mapping-missions/$controlId")
        require(!root.exists()){"Control mission already exists; inspect its result before repeating this experiment."}
        root.mkdirs()
        val provenance=JSONObject().put("sourceMissionId",sourceId).put("sourceLayerId",baselineId).put("sourceSelectionCount",all.size)
            .put("selectedPhotoCount",nadir.size).put("selection","same-flight originalSha256 subset with camera pitch within 3 degrees of nadir")
            .put("originalSha256",JSONArray(nadir)).put("createdAt",Instant.now().toString()).put("engine","rtk-surface").put("meshSpacingMeters",.4)
        val manifest=JSONObject(sourceManifest.toString()).put("missionId",controlId).put("title",sourceManifest.getString("title")+" · Mixed-flight nadir control")
            .put("processLocally",false).put("comparison",provenance)
        manifest.getJSONObject("photoIntake").put("missionId",controlId)
        MappingPhotoStore.writeJson(File(root,"mission.json"),manifest)
        val store=MappingPhotoStore(root)
        nadir.forEachIndexed{i,digest->val record=JSONObject(File(source,"inventory/$digest.json").readText())
            store.importOriginal(record.getString("name"),File(source,"originals/$digest.jpg"),digest)
            android.util.Log.i("MappingNadirControl","Retained shared nadir original ${i+1}/${nadir.size}")}
        MappingPhotoStore.writeJson(File(root,"comparison-input.json"),provenance)
        context.startActivity(Intent(context,org.ncssar.rid2caltopo.app.R2CActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val started=System.nanoTime()
        MappingProcessingManager.start(context,controlId,expectedPhotos=nadir.size,completionSource="operator_requested_same_flight_nadir_control",engine="rtk-surface",selectedDigests=nadir)
        val deadline=System.currentTimeMillis()+30*60*1000;var last=""
        while(MappingProcessingManager.state.value?.active==true&&System.currentTimeMillis()<deadline){
            val state=MappingProcessingManager.state.value!!
            if(state.stage!=last){last=state.stage;android.util.Log.i("MappingNadirControl",state.stage)}
            Thread.sleep(1000)
        }
        val state=MappingProcessingManager.state.value!!
        assertFalse("Control reconstruction timed out",state.active);assertNull(state.error,state.error)
        val layer=state.layer!!;val product=LocalMappingTiles.directory(context,layer.id);val report=JSONObject(File(product,"layer.json").readText())
        assertEquals(nadir.size,report.getInt("selectedPhotoCount"));assertEquals(nadir.size,layer.photoCount+report.getInt("excludedPhotoCount"))
        assertEquals(.4,JSONObject(layer.surfaceModel).getDouble("meshSpacingMeters"),1e-9)
        assertEquals(sourceManifest.getJSONObject("feature").getJSONObject("geometry").toString(),manifest.getJSONObject("feature").getJSONObject("geometry").toString())
        val used=JSONObject(File(product,"surface/cameras.json").readText()).getJSONArray("originalSha256").let{a->(0 until a.length()).map{a.getString(it)}}
        assertTrue(nadir.containsAll(used));assertTrue(used.all{kotlin.math.abs(MappingPose.read(File(root,"originals/$it.jpg"),fov,allowOblique=true).pitch+90)<3})
        assertTrue(File(product,"surface/surface.glb").length()>1000);assertTrue(LocalMappingTiles.indices(product).isNotEmpty())
        ZipFile(File(product,"share.zip")).use{zip->val shared=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use{it.readText()})
            assertEquals(layer.id,shared.getJSONArray("mapping_layers").getJSONObject(0).getString("layerId"))
            assertEquals(.4,shared.getJSONArray("mapping_layers").getJSONObject(0).getJSONObject("surfaceModel").getDouble("meshSpacingMeters"),1e-9)}
        before.forEach{(file,digest)->assertEquals(file.path,digest,sha(file))}
        MappingPhotoStore.writeJson(File(root,"comparison-result.json"),JSONObject(provenance.toString()).put("controlMissionId",controlId).put("controlLayerId",layer.id)
            .put("usedPhotoCount",layer.photoCount).put("excludedPhotoCount",report.getInt("excludedPhotoCount")).put("testSeconds",(System.nanoTime()-started)/1_000_000_000))
        MappingProcessingManager.dismiss()
        android.util.Log.i("MappingNadirControl","PASS: controlMission=$controlId controlLayer=${layer.id} used=${layer.photoCount} selected=${nadir.size}; parent originals and full result unchanged")
    }
}
