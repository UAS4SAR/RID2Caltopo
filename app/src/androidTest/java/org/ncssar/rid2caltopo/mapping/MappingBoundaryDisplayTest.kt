package org.ncssar.rid2caltopo.mapping

import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Offscreen artifact rendering only; never captures the protected application window. */
@RunWith(AndroidJUnit4::class)
class MappingBoundaryDisplayTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val setProjection=TilesOverlay::class.java.getDeclaredMethod("setProjection",Projection::class.java).apply{isAccessible=true}
    private val drawTile=TilesOverlay::class.java.getDeclaredMethod("onTileReadyToDraw",Canvas::class.java,android.graphics.drawable.Drawable::class.java,Rect::class.java).apply{isAccessible=true}
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun tile(overlay:TilesOverlay,p:Projection,canvas:Canvas,bitmap:Bitmap,x:Int,y:Int) {
        val point=p.toPixelsFromMercator(x*256L,y*256L,null)
        setProjection.invoke(overlay,p)
        val rect=Rect(point.x,point.y,point.x+256,point.y+256)
        (overlay as? MappingBoundaryOverlay)?.prepareTile(p,rect)
        drawTile.invoke(overlay,canvas,BitmapDrawable(context.resources,bitmap),rect)
    }
    @Test fun fadeFollowsGeographyAtFractionalZoomAfterPanAndRotation() {
        val coordinates=listOf(listOf(-121.0,39.0),listOf(-120.9999,39.0),listOf(-120.9999,39.0001),listOf(-121.0,39.0001))
        val boundary=MappingBoundary(coordinates)
        val layer=MappingLayer(UUID.randomUUID().toString(),"test","Test","http://localhost/{Z}/{X}/{Y}.png",18,22,assignmentBoundary=coordinates)
        val (provider,overlay)=layer.overlay(context)
        try {
            for(rotation in listOf(0f,37f))for(zoom in listOf(21.0,21.4)) {
                val p=Projection(zoom,512,512,GeoPoint(39.00005,-120.99992+rotation*.0000003),rotation,true,false,0,0)
                val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
                canvas.concat(p.scaleRotateCanvasMatrix)
                val source=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
                val meters=MappingGeometry.WORLD/p.worldMapSize
                // Fractional zoom tiles have a scaled screen size, as supplied by osmdroid.
                val size=org.osmdroid.util.TileSystem.getTileSize(zoom)
                val centerX=p.getMercatorXFromPixel(256);val centerY=p.getMercatorYFromPixel(256)
                for(y in -2..2)for(x in -2..2){val left=((kotlin.math.floor(centerX/size).toLong()+x)*size).toLong()
                    val top=((kotlin.math.floor(centerY/size).toLong()+y)*size).toLong();val point=p.toPixelsFromMercator(left,top,null)
                    val end=p.toPixelsFromMercator((left+size).toLong(),(top+size).toLong(),null)
                    val rect=Rect(point.x,point.y,end.x,end.y)
                    (overlay as? MappingBoundaryOverlay)?.prepareTile(p,rect)
                    setProjection.invoke(overlay,p);drawTile.invoke(overlay,canvas,BitmapDrawable(context.resources,source),rect)}
                for(offset in listOf(-2.0,2.5,7.0)){
                    val lon=MappingGeometry.longitude(MappingGeometry.east(-120.9999)+offset/boundary.scale)
                    val point=p.toPixels(GeoPoint(39.00005,lon),null);val rotated=p.rotateAndScalePoint(point.x,point.y,null)
                    val expected=if(offset<0)255 else if(offset>5)0 else 128
                    assertEquals("rotation=$rotation zoom=$zoom offset=$offset",expected.toDouble(),Color.alpha(bitmap.getPixel(rotated.x,rotated.y)).toDouble(),12.0)
                }
            }
        } finally { provider.detach() }
    }
    @Test fun retainedTilesKeepInteriorPixelsAndOriginalFiles() {
        val id=InstrumentationRegistry.getArguments().getString("boundaryLayer");assumeTrue(id!=null)
        val root=LocalMappingTiles.directory(context,id!!);val metadata=File(root,"layer.json");val before=digest(metadata)
        val layer=MappingLayer.parse(JSONObject(metadata.readText()));val boundary=MappingBoundary.resolve(context.filesDir,layer)!!
        val (provider,overlay)=layer.overlay(context)
        try {
            val p=Projection(19.0,1024,768,GeoPoint(boundary.coordinates.map{it[1]}.average(),boundary.coordinates.map{it[0]}.average()),0f,true,false,0,0)
            val full=Bitmap.createBitmap(1024,768,Bitmap.Config.ARGB_8888);val target=Bitmap.createBitmap(1024,768,Bitmap.Config.ARGB_8888)
            val plain=TilesOverlay(provider,context)
            for(file in File(root,"19").walkTopDown().filter{it.isFile&&it.extension=="png"}){
                val x=file.parentFile.name.toInt();val y=file.nameWithoutExtension.toInt();val point=p.toPixelsFromMercator(x*256L,y*256L,null)
                if(!Rect.intersects(Rect(0,0,1024,768),Rect(point.x,point.y,point.x+256,point.y+256)))continue
                val bytes=digest(file);val bitmap=BitmapFactory.decodeFile(file.path)
                tile(plain,p,Canvas(full),bitmap,x,y);tile(overlay,p,Canvas(target),bitmap,x,y)
                assertEquals(bytes,digest(file))
            }
            var interior=0;var hidden=0;var fringe=0
            val meters=MappingGeometry.WORLD/p.worldMapSize
            for(y in 0 until 768 step 3)for(x in 0 until 1024 step 3){val a=full.getPixel(x,y);val b=target.getPixel(x,y);if(Color.alpha(a)==0)continue
                val east=(p.getMercatorXFromPixel(x)*meters-MappingGeometry.WORLD/2-boundary.originX)*boundary.scale
                val north=(MappingGeometry.WORLD/2-p.getMercatorYFromPixel(y)*meters-boundary.originY)*boundary.scale
                val alpha=boundary.alpha(east,north)
                if(alpha==1.0&&Color.alpha(a)==255&&Color.alpha(b)==255)assertEquals("interior color $x,$y",a,b)
                if(alpha>.999){assertEquals("pixel $x,$y alpha=$alpha",Color.alpha(a).toDouble(),Color.alpha(b).toDouble(),3.0);interior++}else if(alpha==0.0){assertEquals("pixel $x,$y enu=$east,$north",0.0,Color.alpha(b).toDouble(),3.0);hidden++}else if(Color.alpha(b) in 1..254)fringe++
            }
            assertTrue("interior=$interior",interior>1000);assertTrue("hidden=$hidden",hidden>1000);assertTrue("fringe=$fringe",fringe>100)
            // A preview of saved PNG artifacts on a neutral background, outside the app UI.
            val preview=Bitmap.createBitmap(2048,768,Bitmap.Config.ARGB_8888);val canvas=Canvas(preview);canvas.drawColor(Color.rgb(67,78,75))
            canvas.drawBitmap(full,0f,0f,null);canvas.drawBitmap(target,1024f,0f,null)
            File(context.cacheDir,"boundary-$id.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            android.util.Log.i("MappingBoundaryTest","$id interior=$interior hidden=$hidden fringe=$fringe")
            assertEquals(before,digest(metadata))
        } finally { provider.detach() }
    }
    @Test fun coldMaskKeepsInteriorVisibleAfterPanAndRotation() {
        val coordinates=listOf(listOf(-121.0,39.0),listOf(-120.9999,39.0),listOf(-120.9999,39.0001),listOf(-121.0,39.0001))
        val boundary=MappingBoundary(coordinates)
        val layer=MappingLayer(UUID.randomUUID().toString(),"test","Cold transition","http://localhost/{Z}/{X}/{Y}.png",18,22,assignmentBoundary=coordinates)
        val (provider,overlay)=layer.overlay(context)
        try {
            for(rotation in listOf(0f,37f))for(zoom in listOf(21.0,21.4)) {
                val p=Projection(zoom,512,512,GeoPoint(39.00005,-120.99992+rotation*.0000003),rotation,true,false,0,0)
                val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap);canvas.concat(p.scaleRotateCanvasMatrix)
                val source=BitmapDrawable(context.resources,Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)})
                setProjection.invoke(overlay,p)
                (overlay as MappingBoundaryOverlay).drawPendingInterior(canvas,source,Rect(0,0,512,512))
                for(offset in listOf(-2.0,7.0)) {
                    val lon=MappingGeometry.longitude(MappingGeometry.east(-120.9999)+offset/boundary.scale)
                    val point=p.toPixels(GeoPoint(39.00005,lon),null);val rotated=p.rotateAndScalePoint(point.x,point.y,null)
                    assertEquals("cold mask rotation=$rotation zoom=$zoom offset=$offset",if(offset<0)255 else 0,Color.alpha(bitmap.getPixel(rotated.x,rotated.y)))
                }
            }
        } finally { provider.detach() }
    }
    private fun browser(view:View):WebView?=if(view is WebView)view else if(view is ViewGroup)(0 until view.childCount).firstNotNullOfOrNull{browser(view.getChildAt(it))}else null
    @Test fun retained3DViewerCanSwitchBothWaysWithoutChangingModel() {
        val id=InstrumentationRegistry.getArguments().getString("boundaryLayer");assumeTrue(id!=null)
        val root=LocalMappingTiles.directory(context,id!!);val model=File(root,"surface/surface.glb");val before=digest(model)
        val prefs=context.getSharedPreferences("mapping_edges",0);val saved=prefs.all[id]
        MappingBoundary.setSoft(context,id,true)
        val intent=Intent(context,MappingSurfaceViewActivity::class.java).putExtra("layerId",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ActivityScenario.launch<MappingSurfaceViewActivity>(intent).use{scenario->
            fun evaluate(script:String):String {val done=CountDownLatch(1);var result=""
                scenario.onActivity{activity->assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
                    browser(activity.window.decorView)!!.evaluateJavascript(script){result=it;done.countDown()}}
                assertTrue(done.await(5,TimeUnit.SECONDS));return result
            }
            var status="";val end=System.currentTimeMillis()+30000
            while(System.currentTimeMillis()<end){status=evaluate("document.getElementById('status')?.textContent || 'Loading local surface…'");if(!status.contains("Loading")&&status!="null")break;Thread.sleep(250)}
            assertTrue(status,status.contains("target region"))
            assertEquals("false",evaluate("document.getElementById('extent').hidden"))
            status=evaluate("document.getElementById('extent').click();document.getElementById('status').textContent")
            assertTrue(status,status.contains("full captured extent"))
            status=evaluate("document.getElementById('extent').click();document.getElementById('status').textContent")
            assertTrue(status,status.contains("target region"))
            assertEquals("0",evaluate("(document.getElementById('scene').getContext('webgl2')||document.getElementById('scene').getContext('webgl')).getError()"))
        };assertEquals(before,digest(model))
        } finally {if(saved is Boolean)prefs.edit().putBoolean(id,saved).commit()else prefs.edit().remove(id).commit();MappingWorkspaceState.displayChanged()}
    }
}
