package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.video.mapcache.TileCacheMapProvider
import org.osmdroid.views.overlay.TilesOverlay

/** Adding or changing a layer does not discard the other layers' decoded tile caches. */
internal class MappingLayerOverlays(private val context:Context,private val onInvalidate:()->Unit) {
    private data class Entry(val layer:MappingLayer,val soft:Boolean,val overlay:Pair<TileCacheMapProvider,TilesOverlay>)
    private val entries=linkedMapOf<String,Entry>()
    private val pendingRetirement=mutableListOf<Entry>()
    private val updates=Mutex()
    private val current=MutableStateFlow<List<Pair<TileCacheMapProvider,TilesOverlay>>>(emptyList())
    val overlays=current.asStateFlow()
    private var closed=false
    private val completion=Handler(Looper.getMainLooper()){message->
        if(message.what==0)onInvalidate() // osmdroid tile success, not a missing-tile retry loop.
        true
    }

    /** Called from Main; only prepared providers/render data are published back to it. */
    suspend fun update(layers:List<MappingLayer>) = updates.withLock {
        if(closed)return@withLock
        val previous=entries.toMap()
        val created=mutableListOf<Entry>()
        var committed=false
        try {
            val ready=withContext(Dispatchers.IO) {
                layers.map { layer->
                    currentCoroutineContext().ensureActive()
                    val soft=MappingBoundary.soft(context,layer.id)
                    val old=previous[layer.id]
                    if(old?.layer==layer&&old.soft==soft)old else {
                        // Includes boundary discovery/geometry, provider constructors and their
                        // lazy setup. None of these may grow with disk contents on Main.
                        val overlay=layer.overlay(context,onInvalidate)
                        Entry(layer,soft,overlay).also {
                            created.add(it)
                            overlay.first.tileRequestCompleteHandlers.add(completion)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if(closed)return@withLock
            entries.clear()
            ready.forEach{entries[it.layer.id]=it}
            current.value=ready.map{it.overlay}
            committed=true
            val retained=ready.map{it.overlay.first}.toSet()
            pendingRetirement.addAll(previous.values.filter{it.overlay.first !in retained})
        } catch(cancelled:kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch(error:Exception) {
            org.ncssar.rid2caltopo.data.CaltopoClient.CTError("MappingLayer", "Cannot prepare mapping overlays; keeping previous display",error)
        } finally {
            // withContext can cancel after worker construction but before delivering its result.
            // Such providers never become visible, and still need their resources closed.
            if(!committed)withContext(NonCancellable+Dispatchers.IO) {
                created.forEach{it.overlay.first.detach()}
            }
        }
    }
    /** Main acknowledges removal before a worker can acquire teardown locks on old providers. */
    fun applied(visible:Set<TileCacheMapProvider>) {
        val retained=visible+entries.values.map{it.overlay.first}
        val obsolete=pendingRetirement.filter{it.overlay.first !in retained}
        pendingRetirement.removeAll{it.overlay.first !in retained}
        retire(obsolete)
    }
    fun close(removeFromMap:(TilesOverlay)->Unit) {
        closed=true
        val obsolete=(entries.values+pendingRetirement).distinctBy{it.overlay.first}
        obsolete.forEach{removeFromMap(it.overlay.second)}
        retire(obsolete)
        entries.clear();pendingRetirement.clear();current.value=emptyList()
    }

    private fun retire(obsolete:Collection<Entry>) {
        if(obsolete.isNotEmpty())cleanup.execute { obsolete.forEach{runCatching{it.overlay.first.detach()}} }
    }
    private companion object {
        // Teardown can wait on provider/cache work; serialise it without blocking Main.
        val cleanup=java.util.concurrent.Executors.newSingleThreadExecutor { work ->
            Thread(work,"Mapping overlay cleanup").apply{isDaemon=true}
        }
    }
}
