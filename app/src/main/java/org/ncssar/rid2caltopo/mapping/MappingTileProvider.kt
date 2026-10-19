package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.ncssar.rid2caltopo.video.mapcache.TileCacheMapProvider
import org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter
import org.osmdroid.tileprovider.ReusableBitmapDrawable
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex

/** Keep cached coarser imagery visible while a new zoom's exact tile loads asynchronously. */
internal class MappingTileProvider(context:Context,source:ITileSource,networkAllowed:Boolean,bounds:List<Double> = emptyList()):
    TileCacheMapProvider(context,source,TileDiskCacheWriter(context),networkAllowed) {
    private val nativeMaximumZoom = source.maximumZoomLevel
    init { tileCache.protectedTileComputers.add(MappingNativeTileArea(nativeMaximumZoom)) }
    override fun getMaximumZoomLevel() = maxOf(nativeMaximumZoom,25)
    private val extents=if(bounds.size==4)Array(30){zoom->
        val count=1L shl zoom
        fun col(lon:Double)=kotlin.math.floor((lon+180)/360*count).toLong().coerceIn(0,count-1).toInt()
        fun row(lat:Double)=kotlin.math.floor((MappingGeometry.WORLD/2-MappingGeometry.north(lat.coerceIn(-85.05112878,85.05112878)))/MappingGeometry.WORLD*count).toLong().coerceIn(0,count-1).toInt()
        intArrayOf(col(bounds[0]),row(bounds[3]),col(bounds[2]),row(bounds[1]))
    } else null
    override fun getMapTile(index:Long):Drawable? {
        val z=MapTileIndex.getZoom(index);val x=MapTileIndex.getX(index);val y=MapTileIndex.getY(index)
        extents?.getOrNull(z)?.let{if(x !in it[0]..it[2]||y !in it[1]..it[3])return null}
        // Fetch the native parent even after a cold start at a magnified zoom.
        // Do not request nonexistent files or depend on a warm parent cache.
        if(z>nativeMaximumZoom){
            val delta=z-nativeMaximumZoom
            if(delta>8)return null
            val parent=super.getMapTile(MapTileIndex.getTileIndex(nativeMaximumZoom,x shr delta,y shr delta)) as? BitmapDrawable ?: return null
            if(parent is ReusableBitmapDrawable&&!parent.isBitmapValid||parent.bitmap.isRecycled)return null
            val count=1 shl delta;val bitmap=parent.bitmap
            val source=Rect((x%count)*bitmap.width/count,(y%count)*bitmap.height/count,
                ((x%count)+1)*bitmap.width/count,((y%count)+1)*bitmap.height/count)
            return if(source.isEmpty)null else CachedTiles(listOf(Part(parent,source,0,0,1)))
        }
        super.getMapTile(index)?.let{return it}
        for(delta in 1..minOf(8,z)) {
            val parent=tileCache.getMapTile(MapTileIndex.getTileIndex(z-delta,x shr delta,y shr delta)) as? BitmapDrawable?:continue
            if(parent is ReusableBitmapDrawable&&!parent.isBitmapValid)continue
            val count=1 shl delta
            val bitmap=parent.bitmap
            if(bitmap.isRecycled)continue
            val col=x%count;val row=y%count
            val source=Rect(col*bitmap.width/count,row*bitmap.height/count,(col+1)*bitmap.width/count,(row+1)*bitmap.height/count)
            if(!source.isEmpty)return CachedTiles(listOf(Part(parent,source,0,0,1)))
        }
        // Zooming out needs the opposite handoff: keep the visible finer tiles.
        val parts=mutableListOf<Part>()
        for(delta in 1..minOf(2,29-z)) {
            val count=1 shl delta
            for(row in 0 until count)for(col in 0 until count) {
                if(parts.any{it.count<count&&col/(count/it.count)==it.col&&row/(count/it.count)==it.row})continue
                val child=tileCache.getMapTile(MapTileIndex.getTileIndex(z+delta,x*count+col,y*count+row)) as? BitmapDrawable?:continue
                if(child.bitmap.isRecycled||child is ReusableBitmapDrawable&&!child.isBitmapValid)continue
                parts+=Part(child,null,col,row,count)
            }
            if(parts.sumOf{1.0/(it.count*it.count)}>=1.0)return CachedTiles(parts)
        }
        return if(parts.isEmpty())null else CachedTiles(parts)
    }
    private data class Part(val image:BitmapDrawable,val source:Rect?,val col:Int,val row:Int,val count:Int)
    private class CachedTiles(private val parts:List<Part>):Drawable() {
        private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        private val destination=Rect()
        override fun draw(canvas:Canvas) {
            parts.forEach { part->
                val reusable=part.image as? ReusableBitmapDrawable
                reusable?.beginUsingDrawable()
                try {
                    if(reusable?.isBitmapValid!=false&&!part.image.bitmap.isRecycled) {
                        destination.set(bounds.left+part.col*bounds.width()/part.count,bounds.top+part.row*bounds.height()/part.count,
                            bounds.left+(part.col+1)*bounds.width()/part.count,bounds.top+(part.row+1)*bounds.height()/part.count)
                        canvas.drawBitmap(part.image.bitmap,part.source,destination,paint)
                    }
                } finally { reusable?.finishUsingDrawable() }
            }
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
        @Deprecated("Drawable compatibility") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
