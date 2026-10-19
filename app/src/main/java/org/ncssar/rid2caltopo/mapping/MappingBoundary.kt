package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.*

/** A display mask from the captured assignment snapshot, never the live incident polygon. */
internal class MappingBoundary(val coordinates:List<List<Double>>) {
    val originX=coordinates.map{MappingGeometry.east(it[0])}.average()
    val originY=coordinates.map{MappingGeometry.north(it[1])}.average()
    val scale=cos(Math.toRadians(coordinates.map{it[1]}.average()))
    val polygon=coordinates.map{(MappingGeometry.east(it[0])-originX)*scale to (MappingGeometry.north(it[1])-originY)*scale}
    private val region=MappingSurfaceCoverage(polygon)
    fun alpha(x:Double,y:Double,feather:Double=FEATHER_METERS):Double {
        require(feather.isFinite()&&feather>0)
        if(region.contains(x,y))return 1.0
        if(x<region.west-feather||x>region.east+feather||y<region.south-feather||y>region.north+feather)return 0.0
        var distance=Double.POSITIVE_INFINITY
        for(i in polygon.indices){val a=polygon[i];val b=polygon[(i+1)%polygon.size]
            val dx=b.first-a.first;val dy=b.second-a.second;val length=dx*dx+dy*dy
            val t=if(length>0)((x-a.first)*dx+(y-a.second)*dy)/length else 0.0
            val f=t.coerceIn(0.0,1.0);distance=min(distance,hypot(x-a.first-f*dx,y-a.second-f*dy))
        }
        val t=(distance/feather).coerceIn(0.0,1.0)
        return 1-t*t*(3-2*t)
    }
    fun mask(width:Int,height:Int,x:Double,y:Double,stepX:Double,stepY:Double):ByteArray = ByteArray(width*height){index->
        (alpha(x+(index%width+.5)*stepX,y+(index/width+.5)*stepY)*255).roundToInt().toByte()}
    companion object {
        const val FEATHER_METERS=5.0
        fun parse(array:JSONArray):List<List<Double>> {
            require(array.length() in 3..2000){"Invalid assignment boundary."}
            return (0 until array.length()).map{i->val p=array.getJSONArray(i);require(p.length()==2)
                listOf(p.getDouble(0),p.getDouble(1)).also{require(it.all(Double::isFinite)&&it[0] in -180.0..180.0&&it[1] in -85.0..85.0)}}
                .also{MappingBoundary(it)}
        }
        fun resolve(filesDir:File,layer:MappingLayer):MappingBoundary? {
            if(layer.assignmentBoundary.isNotEmpty())return MappingBoundary(layer.assignmentBoundary)
            fun json(file:File)=runCatching{JSONObject(file.readText())}.getOrNull()
            val product=File(filesDir,"mapping-layers/${layer.id}")
            val report=json(File(product,"layer.json"))
            (report?.optJSONArray("assignmentBoundary")?:json(File(product,"surface/surface.json"))?.optJSONArray("assignmentBoundary"))
                ?.let{return runCatching{MappingBoundary(parse(it))}.getOrNull()}
            val explicit=report?.optString("missionId")?.takeIf{runCatching{UUID.fromString(it).toString()==it}.getOrDefault(false)}
            val missions=File(filesDir,"mapping-missions").listFiles().orEmpty().filter{it.isDirectory&&runCatching{UUID.fromString(it.name).toString()==it.name}.getOrDefault(false)}
            val roots=if(explicit!=null)listOf(File(filesDir,"mapping-missions/$explicit")) else {
                val references=missions.filter{json(File(it,"processing-status.json"))?.optString("layerId")==layer.id}
                val hashes=(report?.optJSONArray("originalSha256")?:json(File(product,"surface/cameras.json"))?.optJSONArray("originalSha256"))
                    ?.let{array->runCatching{(0 until array.length()).map{array.getString(it)}.toSet()}.getOrNull()}
                if(references.size==1)references else if(!hashes.isNullOrEmpty())missions.filter{root->
                    val available=File(root,"inventory").listFiles().orEmpty().map{it.nameWithoutExtension}.toSet();available.containsAll(hashes)} else emptyList()
            }
            if(roots.size!=1)return null // Ambiguous old/imported products keep their full extent.
            val mission=json(File(roots.single(),"mission.json"))?:return null
            if(mission.optString("missionId")!=roots.single().name)return null
            return runCatching{val geometry=mission.getJSONObject("feature").getJSONObject("geometry")
                require(geometry.getString("type")=="Polygon");val rings=geometry.getJSONArray("coordinates");require(rings.length()==1)
                val ring=rings.getJSONArray(0);val coordinates=JSONArray((0 until ring.length()).map{i->val p=ring.getJSONArray(i);listOf(p.getDouble(0),p.getDouble(1))})
                MappingBoundary(parse(coordinates))}.getOrNull()
        }
        fun soft(context:Context,id:String)=context.getSharedPreferences("mapping_edges",0).getBoolean(id,true)
        fun setSoft(context:Context,id:String,value:Boolean){context.getSharedPreferences("mapping_edges",0).edit().putBoolean(id,value).apply();MappingWorkspaceState.displayChanged()}
    }
}
