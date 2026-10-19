package org.ncssar.rid2caltopo.mapping

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.MapTileIndex
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MappingTileTransitionTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun cachedParentCoversNewZoomUntilExactTileArrives() {
        val layer=MappingLayer(UUID.randomUUID().toString(),"transition-control","Transition","http://127.0.0.1:8765/{Z}/{X}/{Y}.png",14,23)
        val provider=MappingTileProvider(context,layer.tileSource(),false)
        try {
            val parent=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
            Canvas(parent).drawRect(128f,0f,256f,128f,Paint().apply{color=Color.GREEN})
            val z=20;val x=171460;val y=400160
            provider.tileCache.putTile(MapTileIndex.getTileIndex(z,x,y),BitmapDrawable(context.resources,parent))
            val child=MapTileIndex.getTileIndex(z+1,x*2+1,y*2)
            val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
            val fallback=provider.getMapTile(child)
            assertNotNull("A zoom transition must not drop cached imagery",fallback)
            fallback!!.bounds=Rect(0,0,32,32);fallback.draw(Canvas(image))
            assertEquals(Color.GREEN,image.getPixel(16,16))
            val exact=BitmapDrawable(context.resources,Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)})
            provider.tileCache.putTile(child,exact)
            val upgraded=provider.getMapTile(child)
            assertSame(exact,upgraded)
            upgraded!!.bounds=Rect(0,0,32,32);upgraded.draw(Canvas(image))
            assertEquals(Color.BLUE,image.getPixel(16,16))
            assertFalse(provider.useDataConnection())
        } finally { provider.detach() }
    }
    @Test fun magnifiedZoomUsesNativeTileWithoutInventingFinerTiles() {
        val layer=MappingLayer(UUID.randomUUID().toString(),"zoom-control","Magnification","http://127.0.0.1:8765/{Z}/{X}/{Y}.png",14,22)
        val provider=MappingTileProvider(context,layer.tileSource(),false)
        try {
            assertEquals(25,provider.maximumZoomLevel)
            val parent=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
            Canvas(parent).drawRect(128f,0f,160f,32f,Paint().apply{color=Color.GREEN})
            val x=685840;val y=1600680
            provider.tileCache.putTile(MapTileIndex.getTileIndex(22,x,y),BitmapDrawable(context.resources,parent))
            val tile=provider.getMapTile(MapTileIndex.getTileIndex(25,x*8+4,y*8))
            assertNotNull(tile)
            val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
            tile!!.bounds=Rect(0,0,32,32);tile.draw(Canvas(image))
            assertEquals(Color.GREEN,image.getPixel(16,16))
            assertFalse(provider.useDataConnection())
        } finally {provider.detach()}
    }
    @Test fun cachedBoundaryCanCoverItsChildBeforeTheChildMaskExists() {
        val boundary=MappingBoundary(listOf(listOf(-121.0,39.0),listOf(-120.999,39.0),listOf(-120.999,39.001),listOf(-121.0,39.001)))
        val zoom=20;val count=1 shl zoom
        val x=kotlin.math.floor((MappingGeometry.east(-120.999)+MappingGeometry.WORLD/2)/MappingGeometry.WORLD*count).toInt()
        val y=kotlin.math.floor((MappingGeometry.WORLD/2-MappingGeometry.north(39.0005))/MappingGeometry.WORLD*count).toInt()
        val parent=MappingBoundaryTile(zoom,x,y)
        val mask=MappingBoundaryMasks.await(boundary,parent)
        assertTrue("This parent spans the feather band",mask is MappingBoundaryMasks.Mask.Feather)
        val covering=MappingBoundaryMasks.covering(boundary,MappingBoundaryTile(zoom+1,parent.x*2+1,parent.y*2))
        assertNotNull(covering);assertSame(mask,covering!!.mask);assertEquals(parent,covering.tile)
    }
    @Test fun cachedChildrenRemainVisibleWhenZoomingOut() {
        val layer=MappingLayer(UUID.randomUUID().toString(),"transition-control","Zoom out","http://127.0.0.1:8765/{Z}/{X}/{Y}.png",14,23)
        val provider=MappingTileProvider(context,layer.tileSource(),false)
        try {
            val z=20;val x=171460;val y=400160
            val colors=listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
            for(row in 0..1)for(col in 0..1) {
                val image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply{eraseColor(colors[row*2+col])}
                provider.tileCache.putTile(MapTileIndex.getTileIndex(z+1,x*2+col,y*2+row),BitmapDrawable(context.resources,image))
            }
            val fallback=provider.getMapTile(MapTileIndex.getTileIndex(z,x,y))
            assertNotNull("Zooming out must retain the visible finer imagery",fallback)
            val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
            fallback!!.bounds=Rect(0,0,32,32);fallback.draw(Canvas(image))
            for(row in 0..1)for(col in 0..1)assertEquals(colors[row*2+col],image.getPixel(col*16+8,row*16+8))
        } finally { provider.detach() }
    }
}
