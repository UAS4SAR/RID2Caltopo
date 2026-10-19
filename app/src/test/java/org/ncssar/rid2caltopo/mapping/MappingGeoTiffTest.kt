package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.InflaterInputStream
import java.io.ByteArrayInputStream

class MappingGeoTiffTest {
    @Test fun tagsPixelsGapAndGeoreference() {
        val root=Files.createTempDirectory("geotiff").toFile()
        try {
            listOf(6300,6302).forEach{File(root,"14/2600/$it.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}}
            val plan=MappingGeoTiff.plan(root,14,14)
            assertEquals(3,plan.rows)
            val file=File(root,"map.tif")
            MappingGeoTiff.write(file,plan,{_,y->if(y==6301)null else ByteArray(262144){when(it%4){0->255.toByte();3->128.toByte();else->0}}})
            val data=file.readBytes();val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val tags=(0 until b.getShort(8).toInt()).associate { val i=10+it*12;(b.getShort(i).toInt() and 65535) to i }
            fun payload(id:Int):Int{val i=tags.getValue(id);val type=b.getShort(i+2).toInt();val size=when(type){3->2;12->8;else->4};return if(b.getInt(i+4)*size<=4)i+8 else b.getInt(i+8)}
            assertEquals(256,b.getInt(payload(256)));assertEquals(768,b.getInt(payload(257)))
            assertEquals(3857,b.getShort(payload(34735)+30).toInt());assertEquals(2,b.getShort(payload(338)).toInt())
            assertEquals(plan.pixelSize,b.getDouble(payload(33550)),1e-10)
            assertEquals(-Math.PI*6378137+2600*256*plan.pixelSize,b.getDouble(payload(33922)+24),1e-8)
            assertEquals(Math.PI*6378137-6300*256*plan.pixelSize,b.getDouble(payload(33922)+32),1e-8)
            assertEquals(8,b.getShort(payload(259)).toInt())
            fun tile(i:Int):ByteArray {val offset=b.getInt(payload(324)+i*4);val count=b.getInt(payload(325)+i*4);return InflaterInputStream(ByteArrayInputStream(data,offset,count)).use{it.readBytes()}}
            val first=tile(0);val middle=tile(1)
            assertArrayEquals(byteArrayOf(-1,0,0,-128),first.copyOfRange(0,4))
            assertArrayEquals(ByteArray(262144),middle)
            assertTrue(file.length()<262144L*plan.count)
        } finally {root.deleteRecursively()}
    }
    @Test fun nativeResolutionAndCleanup() {
        val root=Files.createTempDirectory("geotiff").toFile()
        try {
            listOf("15/5200/12600.png","15/5400/12800.png","14/2600/6300.png").forEach { File(root,it).apply{parentFile.mkdirs();writeBytes(byteArrayOf())} }
            val plan=MappingGeoTiff.plan(root,14,15);assertEquals(15,plan.zoom)
            val file=File(root,"map.tif")
            try {MappingGeoTiff.write(file,plan,{_,_->error("cancelled")});fail("Must fail")}catch(_:IllegalStateException){}
            assertFalse(file.exists());assertFalse(root.listFiles()!!.any{it.name.endsWith("partial")})
        } finally {root.deleteRecursively()}
    }
    @Test fun fullResolutionCanExceedFortyMB() {
        val root=Files.createTempDirectory("geotiff-full").toFile()
        try {
            val pixels=ByteArray(262144);java.util.Random(42).nextBytes(pixels)
            val plan=MappingGeoTiff.Plan(23,2600,6300,153,1)
            val file=File(root,"full.tif")
            MappingGeoTiff.write(file,plan,{_,_->pixels})
            assertTrue(file.length()>40_000_000)
            File(root,"23/2600/6300.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}
            File(root,"23/2752/6300.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}
            assertEquals(153L,MappingGeoTiff.plan(root,14,23).count)
            File(root,"22/1300/3150.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}
            val limited=MappingGeoTiff.writeBest(root,file,14,23,MappingGeoTiff.CALTOPO_MAX_BYTES,
                read={plan,_,_->if(plan.zoom==23)pixels else ByteArray(262144)})
            assertEquals(23,limited.fullZoom);assertEquals(22,limited.plan.zoom)
            assertTrue(file.length()<=MappingGeoTiff.CALTOPO_MAX_BYTES)
            File(root,"23/0/0.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}
            File(root,"23/8388607/8388607.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}
            try {MappingGeoTiff.plan(root,14,23);fail("Invalid classic TIFF extent must fail")}catch(_:IllegalArgumentException){}
        } finally {root.deleteRecursively()}
    }
    @Test fun optionalLimitUsesCompressedSizeAndPreservesPreviousExportOnFailure() {
        val root=Files.createTempDirectory("geotiff-optional-limit").toFile()
        try {
            for(z in listOf(16,14)) File(root,"$z/2600/6300.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}
            val destination=File(root,"map.tif")
            val empty=ByteArray(262144);val noisy=ByteArray(262144).also{java.util.Random(42).nextBytes(it)}
            val full=MappingGeoTiff.writeBest(root,destination,14,16,1500,read={_,_,_->empty})
            assertEquals(16,full.plan.zoom) // Full detail is retained when compression alone fits.
            val limited=MappingGeoTiff.writeBest(root,destination,14,16,1500,read={plan,_,_->if(plan.zoom==16)noisy else empty})
            assertEquals(16,limited.fullZoom);assertEquals(14,limited.plan.zoom)
            val unlimited=MappingGeoTiff.writeBest(root,destination,14,16,read={_,_,_->noisy})
            assertEquals(16,unlimited.plan.zoom);assertTrue(destination.length()>1500)
            val previous=destination.readBytes()
            try {MappingGeoTiff.writeBest(root,destination,14,16,500,read={_,_,_->empty});fail("Must report no fitting saved zoom")}catch(_:IllegalStateException){}
            assertArrayEquals(previous,destination.readBytes())
            var reads=0
            try {MappingGeoTiff.writeBest(root,destination,14,16,1500,read={_,_,_->reads++;throw java.io.IOException("bad tile")});fail("Decode failure must propagate")}
            catch(failure:java.io.IOException){assertEquals("bad tile",failure.message)}
            assertEquals(1,reads)
            assertFalse(root.listFiles()!!.any{it.name.endsWith("partial")})
        } finally {root.deleteRecursively()}
    }

}
