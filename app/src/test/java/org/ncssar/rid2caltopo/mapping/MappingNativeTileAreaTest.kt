package org.ncssar.rid2caltopo.mapping

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import org.junit.Assert.*
import org.junit.Test
import org.osmdroid.tileprovider.MapTileCache
import org.osmdroid.util.MapTileIndex

class MappingNativeTileAreaTest {
    private class Tile:Drawable() {
        override fun draw(canvas:Canvas){}
        override fun setAlpha(alpha:Int){}
        override fun setColorFilter(filter:ColorFilter?){}
        override fun getOpacity()=0
    }
    @Test fun nativeParentSurvivesOverzoomCollectionButIsReleasedAfterPan() {
        val cache=MapTileCache(1).apply{setStressedMemory(true);setAutoEnsureCapacity(false)}
        val parent=MapTileIndex.getTileIndex(22,100,200)
        cache.mapTileArea.set(24,400,800,403,803)
        cache.putTile(parent,Tile())
        cache.garbageCollection()
        assertNull("Unprotected native parent is evicted at display zoom",cache.getMapTile(parent))
        cache.protectedTileComputers.add(MappingNativeTileArea(22))
        cache.putTile(parent,Tile())
        repeat(3){cache.garbageCollection();assertNotNull(cache.getMapTile(parent))}
        cache.mapTileArea.set(24,800,800,803,803)
        cache.garbageCollection()
        assertNull(cache.getMapTile(parent))
    }
}
