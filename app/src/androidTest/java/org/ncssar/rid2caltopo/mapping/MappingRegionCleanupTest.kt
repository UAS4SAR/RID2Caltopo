package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in cleanup of the separately labeled stress fixture, never source flights. */
@RunWith(AndroidJUnit4::class)
class MappingRegionCleanupTest {
    @Test fun removeCompletedStressFixture() {
        val id=InstrumentationRegistry.getArguments().getString("cleanupRegionMission")
        assumeTrue(id!=null);require(UUID.fromString(id).toString()==id)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"mapping-missions/$id")
        val mission=JSONObject(File(root,"mission.json").readText())
        require(mission.getBoolean("testOnly")&&mission.getString("missionId")==id)
        val result=JSONObject(File(root,"test-result.json").readText())
        require(result.getBoolean("sourceBytesUnchanged"))
        val layerId=result.getString("layerId");require(UUID.fromString(layerId).toString()==layerId)
        val layer=LocalMappingTiles.directory(context,layerId)
        require(JSONObject(File(layer,"layer.json").readText()).getString("missionId")==id)
        val preferences=context.getSharedPreferences("mapping_layers",0)
        val edit=preferences.edit()
        preferences.all.forEach { (key,value)->
            if(key=="selectedLocalLayer") {if(value==layerId)edit.remove(key)}
            else if(key.startsWith("hidden:")) {
                if(value is Set<*> && layerId in value)edit.putStringSet(key,value.filterIsInstance<String>().filterNot{it==layerId}.toSet())
            } else if(value is String) {
                val array=runCatching{JSONArray(value)}.getOrNull()
                if(array!=null){val kept=(0 until array.length()).map{array.getJSONObject(it)}.filterNot{it.optString("layerId")==layerId}
                    if(kept.size!=array.length())edit.putString(key,JSONArray(kept).toString())}
            }
        }
        require(edit.commit())
        val physicalLayer=layer.canonicalFile;layer.deleteRecursively();physicalLayer.deleteRecursively()
        File(context.filesDir,"mapping-processing").listFiles().orEmpty().forEach { work->
            val owner=runCatching{JSONObject(File(work,"work-owner.json").readText()).optString("missionId")}.getOrNull()
            if(owner==id){val physical=work.canonicalFile;work.deleteRecursively();physical.deleteRecursively()}
        }
        root.deleteRecursively()
        MappingVolume.card(context)?.let{File(it,"mapping-missions/$id").deleteRecursively()}
        require(!root.exists()&&!layer.exists())
    }
}
