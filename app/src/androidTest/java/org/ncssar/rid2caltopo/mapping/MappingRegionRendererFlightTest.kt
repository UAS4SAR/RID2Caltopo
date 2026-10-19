package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class MappingRegionRendererFlightTest {
    @Test fun retainedFlightTilesRemainPixelIdentical() {
        val args=InstrumentationRegistry.getArguments();val work=args.getString("regionWork");assumeTrue(work!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"mapping-processing/$work/regions/0")
        val meta=JSONObject(File(root,"surface/surface.json").readText());val calibration=JSONObject(File(root,"surface/cameras.json").readText())
        val width=meta.getInt("width");val height=meta.getInt("height");val cell=meta.getDouble("cellMeters")
        val gridX=meta.getDouble("gridX");val gridY=meta.getDouble("gridY")
        val heights=FloatArray(width*height);ByteBuffer.wrap(File(root,"surface/surface.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(heights)
        val cameras=calibration.getJSONArray("camerasEnu").let{a->DoubleArray(a.length()){a.getDouble(it)}}
        val k=calibration.getJSONArray("intrinsicsPixels").let{a->DoubleArray(a.length()){a.getDouble(it)}}
        val iw=calibration.getInt("width");val ih=calibration.getInt("height")
        val paths=(0 until cameras.size/12).map{i->File(root,"working-surface-$i.raw").also{assertEquals(iw.toLong()*ih*4,it.length())}.path}.toTypedArray()
        val known=heights.indices.filter{heights[it].isFinite()};require(known.size>1000)
        var referenceNanos=0L;var orderedNanos=0L;var visible=0
        try {
            for(index in listOf(known[known.size/4],known[known.size/2],known[known.size*3/4])) {
                val tx=gridX+(index%width)*cell-2;val ty=gridY+(index/width)*cell+2
                val reference=IntArray(65536);val ordered=IntArray(65536)
                MappingSurfaceNative.render(heights,width,height,gridX,gridY,cell,cameras,k,iw,ih,paths,tx,ty,4.0/256,ordered)
                var started=System.nanoTime()
                val expected=MappingSurfaceNative.renderReference(heights,width,height,gridX,gridY,cell,cameras,k,iw,ih,paths,tx,ty,4.0/256,reference)
                referenceNanos+=System.nanoTime()-started;started=System.nanoTime()
                val actual=MappingSurfaceNative.render(heights,width,height,gridX,gridY,cell,cameras,k,iw,ih,paths,tx,ty,4.0/256,ordered)
                orderedNanos+=System.nanoTime()-started;visible+=actual
                assertEquals(expected,actual);assertArrayEquals(reference,ordered)
            }
            assertTrue(visible>1000)
            android.util.Log.i("MappingRenderOrder","Retained flight pixel-identical: $visible visible pixels; reference ${referenceNanos/1000000} ms, ordered ${orderedNanos/1000000} ms")
        } finally {MappingSurfaceNative.releasePixels()}
    }
}
