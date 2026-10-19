package org.ncssar.rid2caltopo.mapping

import org.osmdroid.util.MapTileArea
import org.osmdroid.util.MapTileAreaComputer
import org.osmdroid.util.MapTileAreaZoomComputer

/** Overzoom draws native parents; retain those parents for just the visible area. */
internal class MappingNativeTileArea(private val nativeZoom:Int):MapTileAreaComputer {
    override fun computeFromSource(source:MapTileArea,reuse:MapTileArea?):MapTileArea {
        if(source.zoom<=nativeZoom)return (reuse ?: MapTileArea()).apply{reset()}
        return MapTileAreaZoomComputer(nativeZoom-source.zoom).computeFromSource(source,reuse)
    }
}
