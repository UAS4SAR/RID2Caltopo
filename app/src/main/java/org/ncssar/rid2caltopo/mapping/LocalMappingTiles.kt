package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.util.UUID

/** Durable mapping imagery is not subject to the basemap cache's eviction policy. */
object LocalMappingTiles {
    fun directory(context:Context,id:String):File {
        require(UUID.fromString(id).toString()==id)
        return File(context.filesDir,"mapping-layers/$id")
    }
    private fun id(source:ITileSource)=source.name().takeIf{it.startsWith("R2C-Mapping-")}?.removePrefix("R2C-Mapping-")
        ?.takeIf{runCatching{UUID.fromString(it).toString()==it}.getOrDefault(false)}
    fun file(context:Context,source:ITileSource,index:Long):File?=id(source)?.let{
        val z=MapTileIndex.getZoom(index);val x=MapTileIndex.getX(index);val y=MapTileIndex.getY(index)
        require(z in 0..23&&x in 0 until (1 shl z)&&y in 0 until (1 shl z))
        File(directory(context,it),"$z/$x/$y.png")
    }
    fun indices(context:Context,source:ITileSource):List<Long> = id(source)?.let { indices(directory(context,it)) }.orEmpty()
    fun indices(root:File):List<Long> = root.walkTopDown().filter{it.isFile&&it.extension=="png"}.mapNotNull{
        val p=it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".png").split('/').map{part->part.toIntOrNull()}
        if(p.size!=3||p.any{part->part==null})null else {
            val z=p[0]!!;val x=p[1]!!;val y=p[2]!!
            if(z !in 0..23||x !in 0 until (1 shl z)||y !in 0 until (1 shl z))null else MapTileIndex.getTileIndex(z,x,y)
        }
    }.toList()
}
