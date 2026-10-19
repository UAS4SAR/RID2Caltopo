package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.ncssar.rid2caltopo.video.mapcache.TileCacheMapProvider
import org.osmdroid.views.overlay.TilesOverlay

/** Adding or changing a layer does not discard the other layers' decoded tile caches. */
internal class MappingLayerOverlays(private val context:Context,private val onInvalidate:()->Unit) {
    private data class Entry(val layer:MappingLayer,val soft:Boolean,val overlay:Pair<TileCacheMapProvider,TilesOverlay>)
    private val entries=linkedMapOf<String,Entry>()
    private val completion=Handler(Looper.getMainLooper()){message->
        if(message.what==0)onInvalidate() // osmdroid tile success, not a missing-tile retry loop.
        true
    }

    fun update(layers:List<MappingLayer>):List<Pair<TileCacheMapProvider,TilesOverlay>> {
        val ids=layers.map{it.id}.toSet()
        entries.keys.filter{it !in ids}.forEach{id->entries.remove(id)?.overlay?.first?.detach()}
        return layers.map { layer->
            val soft=MappingBoundary.soft(context,layer.id)
            val old=entries[layer.id]
            if(old?.layer==layer&&old.soft==soft)old.overlay else {
                old?.overlay?.first?.detach()
                val overlay=layer.overlay(context,onInvalidate)
                overlay.first.tileRequestCompleteHandlers.add(completion)
                entries[layer.id]=Entry(layer,soft,overlay)
                overlay
            }
        }
    }
    fun close() { entries.values.forEach{it.overlay.first.detach()};entries.clear() }
}
