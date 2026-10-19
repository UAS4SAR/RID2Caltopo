package org.ncssar.rid2caltopo.mapping

import kotlin.math.*

/** Coverage of the entire requested assignment, including portions outside the DSM.
 * Unknown space outside the assignment is still retained, but is not its denominator.
 */
internal class MappingSurfaceCoverage(val polygon:List<Pair<Double,Double>>) {
    val west=polygon.minOf{it.first};val east=polygon.maxOf{it.first}
    val south=polygon.minOf{it.second};val north=polygon.maxOf{it.second}
    val area=abs(polygon.indices.sumOf{i->val a=polygon[i];val b=polygon[(i+1)%polygon.size];a.first*b.second-b.first*a.second})*.5
    init{require(polygon.size>=3&&polygon.all{it.first.isFinite()&&it.second.isFinite()}&&area>0)}
    fun contains(x:Double,y:Double):Boolean {
        if(x<west||x>east||y<south||y>north)return false
        var inside=false;var j=polygon.lastIndex
        for(i in polygon.indices){val a=polygon[i];val b=polygon[j]
            if((a.second>y)!=(b.second>y)&&x<(b.first-a.first)*(y-a.second)/(b.second-a.second)+a.first)inside=!inside
            j=i
        }
        return inside
    }
    /** Clip only the reporting/output domain; surrounding DSM remains available for visibility. */
    fun clipped(box:MappingRegionPlan.Bounds):MappingSurfaceCoverage? {
        var points=polygon
        fun edge(inside:(Pair<Double,Double>)->Boolean,cross:(Pair<Double,Double>,Pair<Double,Double>)->Pair<Double,Double>) {
            if(points.isEmpty())return
            val output=mutableListOf<Pair<Double,Double>>();var previous=points.last()
            points.forEach { current->
                if(inside(current)!=inside(previous))output.add(cross(previous,current))
                if(inside(current))output.add(current)
                previous=current
            };points=output
        }
        fun vertical(x:Double,a:Pair<Double,Double>,b:Pair<Double,Double>)=x to (a.second+(b.second-a.second)*(x-a.first)/(b.first-a.first))
        fun horizontal(y:Double,a:Pair<Double,Double>,b:Pair<Double,Double>)=(a.first+(b.first-a.first)*(y-a.second)/(b.second-a.second)) to y
        edge({it.first>=box.west},{a,b->vertical(box.west,a,b)})
        edge({it.first<=box.east},{a,b->vertical(box.east,a,b)})
        edge({it.second>=box.south},{a,b->horizontal(box.south,a,b)})
        edge({it.second<=box.north},{a,b->horizontal(box.north,a,b)})
        if(points.distinct().size<3)return null
        return runCatching{MappingSurfaceCoverage(points)}.getOrNull()
    }
    data class Cells(val total:Int,val known:Int){val fraction get()=known.toDouble()/total.coerceAtLeast(1)}
    fun cells(heights:FloatArray,width:Int,height:Int,x:Double,y:Double,cell:Double):Cells {
        require(width>0&&height>0&&heights.size==width*height&&cell>0&&cell.isFinite())
        val x0=floor((west-x)/cell).toInt();val x1=ceil((east-x)/cell).toInt()
        val y0=floor((south-y)/cell).toInt();val y1=ceil((north-y)/cell).toInt()
        require((x1.toLong()-x0+1)*(y1.toLong()-y0+1) in 1..3_000_000){"Assignment coverage exceeds this tablet's local job limit."}
        var total=0;var known=0
        for(row in y0..y1)for(col in x0..x1)if(contains(x+col*cell,y+row*cell)){
            total++;if(row in 0 until height&&col in 0 until width&&heights[row*width+col].isFinite())known++
        }
        return Cells(total,known)
    }
    fun imagePixels(pixels:IntArray,x:Double,y:Double,step:Double):Int {
        require(pixels.size==256*256&&step>0&&step.isFinite())
        if(x>east||x+256*step<west||y<south||y-256*step>north)return 0
        var known=0
        for(row in 0 until 256)for(col in 0 until 256)if(pixels[row*256+col]!=0&&contains(x+(col+.5)*step,y-(row+.5)*step))known++
        return known
    }
}
