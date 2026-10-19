package org.ncssar.rid2caltopo.mapping

import kotlin.math.*

/** Spatial subdivision keeps each original in a core and shares nearby images across cores. */
internal object MappingRegionPlan {
    const val CORE = 128
    const val LIMIT = 192
    const val ACRE = 4046.8564224
    const val TARGET_ACRES = 1.5
    const val MAX_CORE_ACRES = 2.0
    const val OUTPUT_OVERLAP_METERS = 5.0
    data class Bounds(val west:Double,val south:Double,val east:Double,val north:Double) {
        init { require(listOf(west,south,east,north).all(Double::isFinite)&&east>west&&north>south) }
        fun area(scale:Double)=(east-west)*(north-south)*scale*scale
        fun expanded(distance:Double)=Bounds(west-distance,south-distance,east+distance,north+distance)
    }
    data class Photo(val digest:String,val x:Double,val y:Double)
    data class Region(val core:List<Photo>,val images:List<Photo>,val bounds:Bounds?=null) {
        val x=bounds?.let{(it.west+it.east)/2} ?: core.map { it.x }.average()
        val y=bounds?.let{(it.south+it.north)/2} ?: core.map { it.y }.average()
    }
    fun geometrySource(regions:List<Region>,index:Int):Int? = (0 until index).firstOrNull { previous->
        regions[previous].images.map{it.digest}==regions[index].images.map{it.digest}
    }
    fun build(photos:List<Photo>,bounds:Bounds?=null,groundScale:Double=1.0):List<Region> {
        require(photos.size in 3..10000 && photos.map{it.digest}.distinct().size==photos.size)
        require(photos.all { it.x.isFinite()&&it.y.isFinite() })
        require(groundScale.isFinite()&&groundScale>0)
        if(photos.size<=LIMIT&&(bounds==null||bounds.area(groundScale)<=MAX_CORE_ACRES*ACRE))return listOf(Region(photos,photos,bounds))
        val cores=mutableListOf<Pair<List<Photo>,Bounds?>>()
        fun split(items:List<Photo>,box:Bounds?) {
            require(cores.size<200){"This assignment needs more than 200 processing regions."}
            val oversized=box!=null&&box.area(groundScale)>MAX_CORE_ACRES*ACRE
            if(items.size<=CORE&&!oversized){cores.add(items to box);return}
            val byX=if(items.size>CORE)items.maxOf{it.x}-items.minOf{it.x} >= items.maxOf{it.y}-items.minOf{it.y}
                else box!!.east-box.west>=box.north-box.south
            val sorted=items.sortedWith(compareBy<Photo>{if(byX)it.x else it.y}.thenBy{it.digest})
            val median=sorted.size/2
            val low=box?.let{if(byX)it.west else it.south};val high=box?.let{if(byX)it.east else it.north}
            val middle=if(items.size>CORE)median else sorted.count{(if(byX)it.x else it.y)<(low!!+high!!)/2}
            val cut=if(box==null)null else if(items.size>CORE) {
                val a=sorted[median-1];val b=sorted[median]
                ((if(byX)a.x+b.x else a.y+b.y)/2).coerceIn(low!!+(high!!-low)*.1,high-(high-low)*.1)
            } else (low!!+high!!)/2
            val left=box?.let{if(byX)it.copy(east=cut!!) else it.copy(north=cut!!)}
            val right=box?.let{if(byX)it.copy(west=cut!!) else it.copy(south=cut!!)}
            split(sorted.take(middle),left);split(sorted.drop(middle),right)
        }
        split(photos,bounds)
        return cores.map { (core,box) ->
            val ids=core.map{it.digest}.toSet()
            val halo=photos.asSequence().filter{it.digest !in ids}.map{p->p to if(core.isNotEmpty())core.minOf { c->(c.x-p.x).pow(2)+(c.y-p.y).pow(2) }
                else (p.x-p.x.coerceIn(box!!.west,box.east)).pow(2)+(p.y-p.y.coerceIn(box.south,box.north)).pow(2)}
                .sortedWith(compareBy<Pair<Photo,Double>>{it.second}.thenBy{it.first.digest}).take(LIMIT-core.size).map{it.first}.toList()
            Region(core,(core+halo).sortedBy{it.digest},box)
        }
    }
}
