package org.ncssar.rid2caltopo.mapping

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MappingRegionViewerTest {
    private fun layer():MappingLayer? {
        val args=InstrumentationRegistry.getArguments()
        val direct=args.getString("regionLayer")
        val mission=args.getString("regionMission")
        if(direct==null&&mission==null)return null
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=direct ?: JSONObject(File(context.filesDir,"mapping-missions/$mission/test-result.json").readText()).getString("layerId")
        return MappingLayer.parse(JSONObject(File(LocalMappingTiles.directory(context,id),"layer.json").readText()))
    }
    @Test fun regionalCollectionRoundTripsEverySurfaceFile() {
        val layer=layer();assumeTrue(layer!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=MappingSurfaceFiles.export(context,layer!!);assertTrue(source.size>5)
        val id=UUID.randomUUID().toString();val root=MappingVolume.directory(context,"mapping-layers",id,true);val physical=root.canonicalFile
        try {
            val copy=layer.copy(id=id)
            val entries=JSONArray(source.map{(record,_)->JSONObject(record.toString()).put("layerId",id).put("path",record.getString("path").replace(layer.id,id))})
            val files=source.associate{it.first.getString("path").replace(layer.id,id) to it.second}
            assertEquals(1,MappingSurfaceFiles.import(context,entries,listOf(copy)){files[it]?.readBytes()})
            val restored=MappingSurfaceFiles.files(File(root,"surface"))
            assertEquals(source.size,restored.size)
            source.forEach { (record,file)->val relative=record.getString("path").substringAfter("/surface/")
                assertEquals(MappingRegionProcessor.sha(file),MappingRegionProcessor.sha(File(root,"surface/$relative"))) }
        }finally{root.deleteRecursively();physical.deleteRecursively()}
    }
    @Test fun selectedMissionDetailsLoadWithoutStorageWalk() {
        val layer=layer();assumeTrue(layer!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val report=JSONObject(File(LocalMappingTiles.directory(context,layer!!.id),"layer.json").readText())
        val mission=report.getString("missionId");val started=System.nanoTime()
        val rows=MappingFlightRepository.list(context.filesDir,includeStorage=false,onlyMissionId=mission)
        val elapsed=(System.nanoTime()-started)/1_000_000
        assertEquals(1,rows.size);assertEquals(mission,rows.single().id);assertNull(rows.single().storage)
        assertTrue(rows.single().resultIds.contains(layer.id))
        println("Selected mission details loaded in $elapsed ms before storage measurement")
    }
    @Test fun viewerLoadsCombinedAndIndividualRegions() {
        val layer=layer();assumeTrue(layer!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Wake and unlock the tablet before the opt-in viewer check.",
            context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        fun descendants(view:View):Sequence<View> = sequence {yield(view);if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))}
        ActivityScenario.launch<MappingSurfaceViewActivity>(Intent(context,MappingSurfaceViewActivity::class.java).putExtra("layerId",layer!!.id)).use { scenario->
            var count=0
            scenario.onActivity { activity->count=descendants(activity.window.decorView).filterIsInstance<Spinner>().single().count }
            assertTrue(count>1)
            for(region in 0 until count){
                val triangles=if(region==0)(0 until count-1).sumOf { index->JSONObject(File(LocalMappingTiles.directory(context,layer.id),"surface/regions/$index/surface.json").readText()).getInt("triangleCount") } else JSONObject(File(LocalMappingTiles.directory(context,layer.id),"surface/regions/${region-1}/surface.json").readText()).getInt("triangleCount")
                scenario.onActivity { activity->descendants(activity.window.decorView).filterIsInstance<Spinner>().single().setSelection(region) }
                var status="";val deadline=System.currentTimeMillis()+30000
                do {
                    Thread.sleep(500);val done=CountDownLatch(1)
                    scenario.onActivity { activity->descendants(activity.window.decorView).filterIsInstance<WebView>().single()
                        .evaluateJavascript("(()=>{const b=document.getElementById('extent');if(b?.textContent==='Show full extent')b.click();return document.getElementById('status')?.textContent||'';})()"){status=it;done.countDown()} }
                    assertTrue(done.await(5,TimeUnit.SECONDS))
                }while((!status.contains("full captured extent")||status.substringBefore(" triangles").filter(Char::isDigit).toIntOrNull()!=triangles)&&System.currentTimeMillis()<deadline)
                val scaleDone=CountDownLatch(1);var scaleValid=""
                scenario.onActivity { activity->descendants(activity.window.decorView).filterIsInstance<WebView>().single()
                    .evaluateJavascript("(()=>{const s=document.getElementById('scale'),b=document.getElementById('scale-bar');const before=b.style.width;document.getElementById('scene').dispatchEvent(new WheelEvent('wheel',{deltaY:-300,cancelable:true}));const valid=!s.hidden&&Number(s.dataset.meters)>0&&b.getBoundingClientRect().width>0&&b.style.width!==before&&document.getElementById('scale-value').textContent.includes('ft');document.getElementById('reset').click();return valid;})()"){scaleValid=it;scaleDone.countDown()} }
                assertTrue(scaleDone.await(5,TimeUnit.SECONDS));assertEquals("Live 3D scale must respond to zoom", "true",scaleValid)
                assertTrue("Region ${region+1}: $status",status.contains("full captured extent"))
                if(region==0)assertTrue(status,status.contains("${count-1} regions together"))
                assertEquals("Region ${region+1}: $status",triangles,status.substringBefore(" triangles").filter(Char::isDigit).toInt())
            }
        }
    }
}
