package org.ncssar.rid2caltopo.mapping

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Shared display-only masks. Original PNGs and exports never pass through this cache. */
internal object MappingBoundaryMasks {
    sealed interface Mask {
        data object Opaque:Mask
        data object Transparent:Mask
        class Feather(val bitmap:Bitmap):Mask
    }
    private data class Key(val coordinates:List<List<Double>>,val tile:MappingBoundaryTile)
    private class Pending {
        val result=CompletableFuture<Mask>()
        val listeners=linkedSetOf<()->Unit>()
    }
    private val cache=LinkedHashMap<Key,Mask>(64,.75f,true)
    private val pending=mutableMapOf<Key,Pending>()
    private var bytes=0L
    private const val MAX_BYTES=16L*1024*1024
    private const val MAX_ENTRIES=4096
    private val main=Handler(Looper.getMainLooper())
    private val workers=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue(64),
        { task->Thread(task,"mapping-boundary-mask").apply{isDaemon=true} })
    internal val generated=AtomicInteger()

    private fun cost(mask:Mask)=if(mask is Mask.Feather)mask.bitmap.byteCount.toLong() else 64L
    @Synchronized private fun ready(key:Key)=cache[key]

    data class Covering(val mask:Mask,val tile:MappingBoundaryTile)
    /** Reuse the visible coarser boundary during a zoom transition; never wait in draw. */
    @Synchronized fun covering(boundary:MappingBoundary,tile:MappingBoundaryTile):Covering? {
        for(delta in 1..minOf(8,tile.zoom)) {
            val parent=MappingBoundaryTile(tile.zoom-delta,tile.x shr delta,tile.y shr delta)
            cache[Key(boundary.coordinates,parent)]?.let{return Covering(it,parent)}
        }
        return null
    }

    fun request(boundary:MappingBoundary,tile:MappingBoundaryTile,onReady:()->Unit):Mask? {
        val key=Key(boundary.coordinates,tile)
        synchronized(this) {
            cache[key]?.let{return it}
            pending[key]?.let{it.listeners.add(onReady);return null}
            val work=Pending().also{it.listeners.add(onReady)}
            pending[key]=work
            try {
                workers.execute {
                    try {
                        val mask=build(boundary,tile)
                        val listeners=synchronized(this) {
                            cache.put(key,mask)?.let{bytes-=cost(it)}
                            bytes+=cost(mask)
                            val entries=cache.entries.iterator()
                            while((bytes>MAX_BYTES||cache.size>MAX_ENTRIES)&&entries.hasNext()) {
                                val entry=entries.next();bytes-=cost(entry.value);entries.remove()
                            }
                            pending.remove(key)
                            work.result.complete(mask)
                            work.listeners.toList()
                        }
                        // Multiple tile completions are coalesced by postInvalidateOnAnimation.
                        main.post { listeners.forEach{it()} }
                    } catch(error:Exception) {
                        synchronized(this){pending.remove(key);work.result.completeExceptionally(error)}
                        android.util.Log.w("MappingBoundaryMasks","Unable to prepare boundary mask",error)
                    }
                }
            } catch(_:RejectedExecutionException) {
                pending.remove(key) // A bounded queue; the next redraw retries visible tiles.
            }
        }
        return null
    }

    /** Instrumentation can warm an offscreen tile without doing work in its draw callback. */
    internal fun await(boundary:MappingBoundary,tile:MappingBoundaryTile):Mask {
        check(Looper.myLooper()!=Looper.getMainLooper()){"Boundary masks must be prepared off the UI thread."}
        val key=Key(boundary.coordinates,tile)
        request(boundary,tile,{})?.let{return it}
        val work=synchronized(this){pending[key]}
        return ready(key)?:requireNotNull(work){"Boundary queue is full"}.result.get(15,TimeUnit.SECONDS)
    }

    private fun build(boundary:MappingBoundary,tile:MappingBoundaryTile):Mask {
        check(Looper.myLooper()!=Looper.getMainLooper())
        generated.incrementAndGet()
        val span=MappingGeometry.WORLD/(1L shl tile.zoom)
        val x=(tile.x*span-MappingGeometry.WORLD/2-boundary.originX)*boundary.scale
        val y=(MappingGeometry.WORLD/2-tile.y*span-boundary.originY)*boundary.scale
        val step=span*boundary.scale/256
        val polygon=boundary.polygon
        val feather=MappingBoundary.FEATHER_METERS
        if(x>polygon.maxOf{it.first}+feather||x+256*step<polygon.minOf{it.first}-feather||
            y<polygon.minOf{it.second}-feather||y-256*step>polygon.maxOf{it.second}+feather)return Mask.Transparent
        val alpha=boundary.mask(256,256,x,y,step,-step)
        if(alpha.all{it==0.toByte()})return Mask.Transparent
        if(alpha.all{it==255.toByte()})return Mask.Opaque
        // ARGB image alpha is required by Android's software DST_IN implementation.
        return Mask.Feather(Bitmap.createBitmap(IntArray(alpha.size){(alpha[it].toInt()and 255)shl 24},256,256,Bitmap.Config.ARGB_8888))
    }
}
