package org.ncssar.rid2caltopo.mapping

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.TilesOverlay
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToLong

/** Controls rendering with no activity gestures, flight, inventory writes or reconstruction. */
@RunWith(AndroidJUnit4::class)
class MappingLayerRenderingTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val projection=TilesOverlay::class.java.getDeclaredMethod("setProjection",Projection::class.java).apply{isAccessible=true}
    private val draw=TilesOverlay::class.java.getDeclaredMethod("onTileReadyToDraw",Canvas::class.java,android.graphics.drawable.Drawable::class.java,Rect::class.java).apply{isAccessible=true}
    private val coordinates=listOf(listOf(-121.0,39.0),listOf(-120.999,39.0),listOf(-120.999,39.001),listOf(-121.0,39.001))
    private fun layer()=MappingLayer(UUID.randomUUID().toString(),"render-control","Rendering control","http://127.0.0.1:8765/local-layer/{Z}/{X}/{Y}.png",14,23,assignmentBoundary=coordinates)
    private fun rects(p:Projection):List<Rect> {
        val size=TileSystem.getTileSize(p.zoomLevel)
        val x=floor(p.getMercatorXFromPixel(450)/size).toLong()
        val y=floor(p.getMercatorYFromPixel(350)/size).toLong()
        return (-2..2).flatMap { dy->(-2..2).map { dx->
            val start=p.toPixelsFromMercator(((x+dx)*size).roundToLong(),((y+dy)*size).roundToLong(),null)
            val end=p.toPixelsFromMercator(((x+dx+1)*size).roundToLong(),((y+dy+1)*size).roundToLong(),null)
            Rect(start.x,start.y,end.x,end.y)
        } }
    }
    @Test fun unchangedLayersKeepProvidersAndLocalSourcesStayOffline() {
        val store=MappingLayerOverlays(context,{})
        val a=layer();val b=layer();val c=layer()
        try {
            val first=store.update(listOf(a,b))
            val next=store.update(listOf(a,b,c))
            assertSame(first[0],next[0]);assertSame(first[1],next[1])
            next.forEach{it.first.setUseDataConnection(true);assertFalse(it.first.useDataConnection())}
            MappingBoundary.setSoft(context,a.id,false)
            val changed=store.update(listOf(a,b,c))
            assertNotSame(next[0],changed[0]);assertSame(next[1],changed[1]);assertSame(next[2],changed[2])
            val removed=store.update(listOf(b,c));assertSame(changed[1],removed[0]);assertSame(changed[2],removed[1])
        } finally {
            store.close();context.getSharedPreferences("mapping_edges",0).edit().remove(a.id).apply()
        }
    }
    @Test fun sixLayersReuseMasksThroughFractionalZoomPanAndRotation() {
        val boundary=MappingBoundary(coordinates)
        val layers=List(6){layer().overlay(context)}
        // The same geographic edge is shared by these six different result IDs.
        val frames=(0..7).map { i->Projection(21.1+i*.1,900,700,GeoPoint(39.0005,-120.999+if(i%2==0)0.0 else .00001),if(i%2==0)0f else 37f,true,false,0,0) }
        val bitmaps=frames.map{Bitmap.createBitmap(900,700,Bitmap.Config.ARGB_8888)}
        val source=BitmapDrawable(context.resources,Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)})
        try {
            frames.forEach { p->rects(p).forEach{(layers.first().second as MappingBoundaryOverlay).prepareTile(p,it)} }
            val warmed=MappingBoundaryMasks.generated.get()
            val legacy=layers.map{LegacyBoundaryOverlay(it.first,boundary)}
            val oldTimes=mutableListOf<Double>();val newTimes=mutableListOf<Double>()
            fun render(overlays:List<TilesOverlay>,times:MutableList<Double>) {
                frames.forEachIndexed { index,p->
                    val rectangles=rects(p)
                    instrumentation.runOnMainSync {
                        val bitmap=bitmaps[index];bitmap.eraseColor(Color.TRANSPARENT)
                        val canvas=Canvas(bitmap);canvas.concat(p.scaleRotateCanvasMatrix)
                        val start=System.nanoTime()
                        overlays.forEach { overlay->projection.invoke(overlay,p);rectangles.forEach{draw.invoke(overlay,canvas,source,it)} }
                        times+=(System.nanoTime()-start)/1e6
                    }
                }
            }
            render(legacy,oldTimes);render(layers.map{it.second},newTimes)
            assertEquals("No mask generation during warm zoom/pan/rotation draws",warmed,MappingBoundaryMasks.generated.get())
            android.util.Log.i("MappingRenderTest","six layers, 8 frames: legacy medianMs=${oldTimes.sorted()[4]} maxMs=${oldTimes.max()}; reusable medianMs=${newTimes.sorted()[4]} maxMs=${newTimes.max()}; maskBuildsDuringDraw=0")
            assertTrue("Expected cached rendering to avoid repeated geometry work: old=$oldTimes new=$newTimes",newTimes.sum()<oldTimes.sum())
        } finally { layers.forEach{it.first.detach()};bitmaps.forEach{it.recycle()};source.bitmap.recycle() }
    }
    /** Original implementation retained only here for a controlled same-device comparison. */
    private inner class LegacyBoundaryOverlay(provider:org.osmdroid.tileprovider.MapTileProviderBase,private val boundary:MappingBoundary):TilesOverlay(provider,context) {
        private val masks=linkedMapOf<List<Any>,Bitmap>()
        private val paint=Paint(Paint.FILTER_BITMAP_FLAG).apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
        override fun onTileReadyToDraw(canvas:Canvas,drawable:android.graphics.drawable.Drawable,rect:Rect) {
            val p=mProjection;val mx=p.getCleanMercator(p.getMercatorXFromPixel(rect.left),true);val my=p.getMercatorYFromPixel(rect.top)
            val key=listOf(p.zoomLevel,mx,my,rect.width(),rect.height())
            val mask=masks.getOrPut(key){
                val meters=MappingGeometry.WORLD/p.worldMapSize
                val x=(mx*meters-MappingGeometry.WORLD/2-boundary.originX)*boundary.scale
                val y=(MappingGeometry.WORLD/2-my*meters-boundary.originY)*boundary.scale
                val alpha=boundary.mask(256,256,x,y,rect.width()*meters*boundary.scale/256,-rect.height()*meters*boundary.scale/256)
                Bitmap.createBitmap(IntArray(alpha.size){(alpha[it].toInt()and 255)shl 24},256,256,Bitmap.Config.ARGB_8888)
            }
            if(masks.size>32)masks.remove(masks.keys.first())
            MappingBoundaryOverlay.drawMasked(canvas,rect,mask,paint){super.onTileReadyToDraw(canvas,drawable,rect)}
        }
    }
}
