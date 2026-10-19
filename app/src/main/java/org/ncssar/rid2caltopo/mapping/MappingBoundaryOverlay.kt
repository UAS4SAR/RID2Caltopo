package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.util.TileSystem
import org.osmdroid.views.overlay.TilesOverlay

/** Mask at draw time, so cached/downloaded/approximated tiles and saved PNGs stay intact. */
internal class MappingBoundaryOverlay(provider:MapTileProviderBase,context:Context,private val boundary:MappingBoundary,
    private val onMaskReady:()->Unit):TilesOverlay(provider,context) {
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG).apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
    private val geographicPath=Path().apply {
        boundary.coordinates.forEachIndexed { index,point->
            val x=(MappingGeometry.east(point[0])-boundary.originX).toFloat()
            val y=(MappingGeometry.north(point[1])-boundary.originY).toFloat()
            if(index==0)moveTo(x,y)else lineTo(x,y)
        };close()
    }
    private val screenPath=Path()
    private val pathMatrix=Matrix()
    private fun tileAt(p:org.osmdroid.views.Projection,rect:Rect):MappingBoundaryTile {
        val mx=p.getCleanMercator(p.getMercatorXFromPixel(rect.left),true)
        return MappingBoundaryTile.projected(p.zoomLevel,mx,p.getMercatorYFromPixel(rect.top),TileSystem.getTileSize(p.zoomLevel))
    }
    internal fun prepareTile(p:org.osmdroid.views.Projection,rect:Rect)=MappingBoundaryMasks.await(boundary,tileAt(p,rect))
    override fun onTileReadyToDraw(canvas:Canvas,drawable:Drawable,rect:Rect) {
        if(rect.isEmpty)return
        val p=mProjection
        val tile=tileAt(p,rect)
        when(val mask=MappingBoundaryMasks.request(boundary,tile,onMaskReady)) {
            MappingBoundaryMasks.Mask.Opaque->super.onTileReadyToDraw(canvas,drawable,rect)
            is MappingBoundaryMasks.Mask.Feather->drawMasked(canvas,rect,mask.bitmap,paint){super.onTileReadyToDraw(canvas,drawable,rect)}
            MappingBoundaryMasks.Mask.Transparent->Unit
            null->{
                val parent=MappingBoundaryMasks.covering(boundary,tile)
                when(val mask=parent?.mask) {
                    MappingBoundaryMasks.Mask.Opaque->super.onTileReadyToDraw(canvas,drawable,rect)
                    MappingBoundaryMasks.Mask.Transparent->Unit
                    is MappingBoundaryMasks.Mask.Feather->{
                        val count=1 shl (tile.zoom-parent.tile.zoom)
                        val left=rect.left-(tile.x%count)*rect.width()
                        val top=rect.top-(tile.y%count)*rect.height()
                        val maskRect=RectF(left.toFloat(),top.toFloat(),(left+count*rect.width()).toFloat(),(top+count*rect.height()).toFloat())
                        drawMasked(canvas,rect,mask.bitmap,paint,maskRect){super.onTileReadyToDraw(canvas,drawable,rect)}
                    }
                    else->drawPendingInterior(canvas,drawable,rect)
                }
            }
        }
    }
    /** Cold start without a parent: show the exact interior instead of flashing a whole tile off. */
    internal fun drawPendingInterior(canvas:Canvas,drawable:Drawable,rect:Rect) {
        val p=mProjection
        val pixels=p.worldMapSize/MappingGeometry.WORLD
        val east=p.getMercatorXFromPixel(0)/pixels-MappingGeometry.WORLD/2
        val north=MappingGeometry.WORLD/2-p.getMercatorYFromPixel(0)/pixels
        pathMatrix.setValues(floatArrayOf(pixels.toFloat(),0f,((boundary.originX-east)*pixels).toFloat(),
            0f,-pixels.toFloat(),((north-boundary.originY)*pixels).toFloat(),0f,0f,1f))
        geographicPath.transform(pathMatrix,screenPath)
        val saved=canvas.save()
        try{canvas.clipPath(screenPath);super.onTileReadyToDraw(canvas,drawable,rect)}finally{canvas.restoreToCount(saved)}
    }
    companion object {
        internal fun drawMasked(canvas:Canvas,rect:Rect,mask:Bitmap,paint:Paint,maskRect:RectF=RectF(rect),draw:()->Unit) {
            val saved=canvas.saveLayer(RectF(rect),null)
            try{canvas.clipRect(rect);draw();canvas.drawBitmap(mask,null,maskRect,paint)}finally{canvas.restoreToCount(saved)}
        }
    }
}
