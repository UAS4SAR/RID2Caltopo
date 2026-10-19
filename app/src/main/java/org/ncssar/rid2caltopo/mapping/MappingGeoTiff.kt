package org.ncssar.rid2caltopo.mapping

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.math.PI
import kotlin.math.pow

/** Worker-only, bounded-memory export. Leaves source tiles untouched. */
object MappingGeoTiff {
    const val CALTOPO_MAX_BYTES = 40_000_000L
    class SizeLimitExceeded : Exception("Compressed GeoTIFF exceeds the selected size limit.")
    private class NoTiles : IllegalStateException("No saved map tiles are available.")
    data class Selection(val plan: Plan, val fullZoom: Int)
    private const val TIFF_LIMIT = 0xffffffffL
    private const val TILE_BYTES = 256 * 256 * 4
    data class Plan(val zoom: Int, val west: Int, val north: Int, val columns: Int, val rows: Int) {
        val count get() = columns.toLong() * rows
        val pixelSize get() = 2 * PI * 6378137.0 / (256 * 2.0.pow(zoom))
    }
    fun plan(root: File, minZoom: Int, maxZoom: Int, check: ()->Unit = {}): Plan {
        require(minZoom in 0..23 && maxZoom in minZoom..23)
        for (z in maxZoom downTo minZoom) {
            check()
            var west=Int.MAX_VALUE; var east=-1; var north=Int.MAX_VALUE; var south=-1
            File(root,"$z").listFiles()?.forEach { column ->
                check()
                val x=column.name.toIntOrNull() ?: return@forEach
                if(x !in 0 until (1 shl z) || !column.isDirectory) return@forEach
                column.listFiles()?.forEach { tile ->
                    check()
                    val y=tile.name.removeSuffix(".png").toIntOrNull()
                    if(tile.extension=="png" && y!=null && y in 0 until (1 shl z) && tile.isFile) {
                        west=minOf(west,x);east=maxOf(east,x);north=minOf(north,y);south=maxOf(south,y)
                    }
                }
            }
            if(east<0) continue
            val count=(east-west+1L)*(south-north+1L)
            require(count <= (TIFF_LIMIT-4096)/8) { "Map extent exceeds classic TIFF’s tile index limit." }
            return Plan(z,west,north,east-west+1,south-north+1)
        }
        throw NoTiles()
    }
    /** Tries actual compressed output, preserving the highest saved zoom that fits. */
    fun writeBest(root: File, destination: File, minZoom: Int, maxZoom: Int, maxBytes: Long? = null,
                  read: (Plan,Int,Int)->ByteArray?, check: ()->Unit = {}): Selection {
        var ceiling=maxZoom
        var fullZoom: Int?=null
        while(ceiling>=minZoom) {
            check()
            val candidate=try {plan(root,minZoom,ceiling,check)} catch(missing:NoTiles) {
                if(fullZoom==null)throw missing else break
            }
            if(fullZoom==null)fullZoom=candidate.zoom
            try {
                write(destination,candidate,{x,y->read(candidate,x,y)},maxBytes,check)
                return Selection(candidate,fullZoom)
            } catch(limit:SizeLimitExceeded) {ceiling=candidate.zoom-1}
        }
        error("No saved resolution fits the 40 MB CalTopo limit. Export with the limit unchecked.")
    }
    /** Reader returns straight (unassociated) RGBA, top row first; null means a missing tile. */
    fun write(destination: File, plan: Plan, read: (Int,Int)->ByteArray?, maxBytes: Long? = null, check: ()->Unit = {}) {
        require(plan.columns>0 && plan.rows>0 && plan.count <= (TIFF_LIMIT-4096)/8)
        val entries=sortedMapOf<Int,Pair<Int,ByteArray>>()
        fun shorts(vararg v:Int)=ByteBuffer.allocate(v.size*2).order(ByteOrder.LITTLE_ENDIAN).apply{v.forEach{putShort(it.toShort())}}.array()
        fun longs(vararg v:Int)=ByteBuffer.allocate(v.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{v.forEach{putInt(it)}}.array()
        fun doubles(vararg v:Double)=ByteBuffer.allocate(v.size*8).order(ByteOrder.LITTLE_ENDIAN).apply{v.forEach{putDouble(it)}}.array()
        fun tag(id:Int,type:Int,data:ByteArray){entries[id]=type to data}
        tag(256,4,longs((plan.columns.toLong()*256).toInt()));tag(257,4,longs((plan.rows.toLong()*256).toInt()));tag(258,3,shorts(8,8,8,8))
        tag(259,3,shorts(8));tag(262,3,shorts(2));tag(274,3,shorts(1));tag(277,3,shorts(4));tag(284,3,shorts(1))
        tag(322,4,longs(256));tag(323,4,longs(256));tag(324,4,longs(0))
        tag(325,4,longs(0));tag(338,3,shorts(2))
        tag(33550,12,doubles(plan.pixelSize,plan.pixelSize,0.0))
        val half=PI*6378137.0
        tag(33922,12,doubles(0.0,0.0,0.0,-half+plan.west*256*plan.pixelSize,half-plan.north*256*plan.pixelSize,0.0))
        tag(34735,3,shorts(1,1,0,3,1024,0,1,1,1025,0,1,1,3072,0,1,3857))
        val base=8+2+entries.size*12+4
        val fixedEnd=base+entries.values.sumOf{if(it.second.size>4)it.second.size else 0}
        val dataStart=fixedEnd.toLong()+(if(plan.count>1)plan.count*8 else 0)
        if(maxBytes!=null && dataStart>maxBytes)throw SizeLimitExceeded()
        val header=ByteBuffer.allocate(fixedEnd).order(ByteOrder.LITTLE_ENDIAN)
        header.put(73.toByte()).put(73.toByte()).putShort(42.toShort()).putInt(8).putShort(entries.size.toShort())
        var extra=base
        var offsets=0L;var counts=0L
        for((id,pair) in entries){val(type,data)=pair
            header.putShort(id.toShort()).putShort(type.toShort())
            if(id==324 || id==325) {
                header.putInt(plan.count.toInt())
                val location=if(plan.count==1L) header.position().toLong() else fixedEnd.toLong()+(if(id==325)plan.count*4 else 0)
                if(id==324)offsets=location else counts=location
                header.putInt(if(plan.count==1L)0 else location.toInt())
            } else {
                header.putInt(data.size/when(type){3->2;12->8;else->4})
                if(data.size<=4){header.put(data);repeat(4-data.size){header.put(0.toByte())}}
                else{header.putInt(extra);System.arraycopy(data,0,header.array(),extra,data.size);extra+=data.size}
            }
        }
        header.putInt(0)
        val partial=File(destination.parentFile,"${destination.name}.${java.util.UUID.randomUUID()}.partial")
        try {
            RandomAccessFile(partial,"rw").use { out ->
                out.write(header.array());out.setLength(dataStart);out.seek(dataStart)
                val empty=ByteArray(TILE_BYTES);var index=0L
                for(y in 0 until plan.rows)for(x in 0 until plan.columns) {
                    check();val pixels=read(plan.west+x,plan.north+y)?:empty;require(pixels.size==TILE_BYTES)
                    val buffer=ByteArrayOutputStream(TILE_BYTES)
                    DeflaterOutputStream(buffer).use{it.write(pixels)}
                    val compressed=buffer.toByteArray();if(out.filePointer%2!=0L)out.write(0);val position=out.filePointer
                    if(maxBytes!=null && position+compressed.size>maxBytes)throw SizeLimitExceeded()
                    require(position+compressed.size<=TIFF_LIMIT){"Compressed map exceeds classic TIFF’s 4 GB limit. Export a smaller region."}
                    out.write(compressed);val end=out.filePointer
                    out.seek(offsets+index*4);out.writeInt(Integer.reverseBytes(position.toInt()))
                    out.seek(counts+index*4);out.writeInt(Integer.reverseBytes(compressed.size))
                    out.seek(end);index++
                }
                check();out.fd.sync()
            }
            check()
            kotlin.check(partial.renameTo(destination)){"Cannot save GeoTIFF."}
        } finally {partial.delete()}
    }
}
