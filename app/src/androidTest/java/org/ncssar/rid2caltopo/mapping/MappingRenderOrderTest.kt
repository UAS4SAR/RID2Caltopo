package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class MappingRenderOrderTest {
    @Test fun centralViewOrderingIsPixelIdenticalWithOcclusionUnknownDepthAndTies() {
        MappingSurfaceNative.requireAvailable()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"render-order-${UUID.randomUUID()}").apply{mkdirs()}
        val poses=mutableListOf<DoubleArray>()
        // Symmetric camera scores tie exactly at world x=0, while tile-centre ordering differs.
        for(x in listOf(8.0,-8.0))poses.add(MappingCameraGeometry.rotation(0.0,-90.0,0.0)+doubleArrayOf(x,0.0,40.0))
        for(pitch in listOf(-60.0,-70.0,-90.0))for(x in listOf(-12.0,0.0,12.0))for(y in listOf(-10.0,10.0)) {
            poses.add(MappingCameraGeometry.rotation(0.0,pitch,0.0)+doubleArrayOf(x,y-40*tan(Math.toRadians(90+pitch)),40.0))
        }
        val files=poses.indices.map { i->File(directory,"$i.raw").apply{
            val b=ByteBuffer.allocate(128*96*4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(128*96){p->b.putInt(0xff000000.toInt() or (((p+i*47)%256) shl 16) or ((i*11%256) shl 8) or (p%256))};writeBytes(b.array())
        } }
        val k=doubleArrayOf(80.0,63.5,47.5,.005,-.001)
        var referenceNanos=0L;var orderedNanos=0L;var fineReference=0L;var fineOrdered=0L
        try {
            for(step in listOf(.125,.015625))for(reverse in listOf(false,true))for(scene in 0..2) {
                val indices=if(reverse)poses.indices.reversed() else poses.indices.drop(2)+poses.indices.take(2)
                val cameras=indices.flatMap{poses[it].toList()}.toDoubleArray();val paths=indices.map{files[it].path}.toTypedArray()
                val heights=FloatArray(161*161)
                if(scene>0)for(row in 65..90)for(col in 67..81)heights[row*161+col]=if(scene==1)15f else Float.NaN
                val reference=IntArray(65536);val ordered=IntArray(65536)
                var started=System.nanoTime()
                val expected=MappingSurfaceNative.renderReference(heights,161,161,-40.0,-40.0,.5,cameras,k,128,96,paths,-128.5*step,128.5*step,step,reference)
                val rt=System.nanoTime()-started;referenceNanos+=rt;if(step<.1)fineReference+=rt;started=System.nanoTime()
                val actual=MappingSurfaceNative.render(heights,161,161,-40.0,-40.0,.5,cameras,k,128,96,paths,-128.5*step,128.5*step,step,ordered)
                val ot=System.nanoTime()-started;orderedNanos+=ot;if(step<.1)fineOrdered+=ot
                assertEquals(expected,actual);assertTrue(actual>0);assertArrayEquals("scene=$scene reverse=$reverse",reference,ordered)
            }
            android.util.Log.i("MappingRenderOrder","Pixel-identical: reference ${referenceNanos/1000000} ms, ordered ${orderedNanos/1000000} ms; fine tiles reference ${fineReference/1000000} ms, ordered ${fineOrdered/1000000} ms")
            // Cropping output must retain the full surrounding DSM for oblique visibility.
            val selected=(8..13).toList()
            val cameras=selected.flatMap{poses[it].toList()}.toDoubleArray();val paths=selected.map{files[it].path}.toTypedArray()
            val surface=MappingSurface(FloatArray(161*161),161,161,-40.0,-40.0,.5,org.json.JSONObject())
            val full=MappingSurfaceExport.renderColors(surface,cameras,k,128,96,paths,{_,_->},{})
            val bounds=MappingRegionPlan.Bounds(-5.0,-5.0,5.0,5.0)
            val crop=MappingSurfaceExport.renderColors(surface,cameras,k,128,96,paths,{_,_->},{},bounds)
            assertTrue(crop.any{it!=0})
            for(row in 0..160)for(col in 0..160){val inside=-40+col*.5 in -5.0..5.0&&-40+row*.5 in -5.0..5.0
                assertEquals("Cropped cell $col,$row",if(inside)full[row*161+col] else 0,crop[row*161+col])}
        }finally{MappingSurfaceNative.releasePixels();directory.deleteRecursively()}
    }
}
