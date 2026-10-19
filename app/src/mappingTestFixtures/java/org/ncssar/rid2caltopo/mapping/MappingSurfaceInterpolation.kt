package org.ncssar.rid2caltopo.mapping

// Historical Android policy for comparison fixtures only. Production uses native/mapping/Reconstruction.cpp.

import org.opencv.core.*
import org.opencv.imgproc.Subdiv2D
import kotlin.math.*

/** Fill only triangles supported by nearby, successfully adjusted 3D landmarks. */
internal object MappingSurfaceInterpolation {
    fun fill(heights:FloatArray,width:Int,height:Int,x:Double,y:Double,cell:Double,points:DoubleArray,check:()->Unit):Int {
        val subdivision=Subdiv2D(Rect(0,0,width,height));val elevations=mutableMapOf<Long,Double>();val bins=mutableSetOf<Pair<Int,Int>>()
        fun key(x:Float,y:Float)=(x.toBits().toLong() shl 32) or (y.toBits().toLong() and 0xffffffffL)
        for(i in points.indices step 3){val px=points[i];val py=points[i+1];val z=points[i+2];if(!px.isFinite()||!py.isFinite()||!z.isFinite())continue
            val xx=((px-x)/cell).toFloat();val yy=((py-y)/cell).toFloat();if(xx !in 1f..(width-2).toFloat()||yy !in 1f..(height-2).toFloat())continue
            // Avoid almost-identical subdivision vertices without spreading a point over a cell.
            if(!bins.add((xx*2).roundToInt() to (yy*2).roundToInt()))continue
            subdivision.insert(Point(xx.toDouble(),yy.toDouble()));elevations[key(xx,yy)]=z
        }
        val triangles=MatOfFloat6();var filled=0
        try{subdivision.getTriangleList(triangles);val coordinates=triangles.toArray()
            for(i in coordinates.indices step 6){if(i%600==0)check();val ax=coordinates[i];val ay=coordinates[i+1];val bx=coordinates[i+2];val by=coordinates[i+3];val cx=coordinates[i+4];val cy=coordinates[i+5]
                val za=elevations[key(ax,ay)]?:continue;val zb=elevations[key(bx,by)]?:continue;val zc=elevations[key(cx,cy)]?:continue
                val maxEdge=maxOf(hypot((ax-bx).toDouble(),(ay-by).toDouble()),hypot((ax-cx).toDouble(),(ay-cy).toDouble()),hypot((bx-cx).toDouble(),(by-cy).toDouble()))*cell
                if(maxEdge>6||maxOf(za,zb,zc)-minOf(za,zb,zc)>5)continue
                val denominator=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy);if(abs(denominator)<.25f)continue
                val x0=floor(minOf(ax,bx,cx)).toInt().coerceAtLeast(0);val x1=ceil(maxOf(ax,bx,cx)).toInt().coerceAtMost(width-1)
                val y0=floor(minOf(ay,by,cy)).toInt().coerceAtLeast(0);val y1=ceil(maxOf(ay,by,cy)).toInt().coerceAtMost(height-1)
                for(yy in y0..y1)for(xx in x0..x1){val index=yy*width+xx;if(heights[index].isFinite())continue
                    val a=((by-cy)*(xx-cx)+(cx-bx)*(yy-cy))/denominator;val b=((cy-ay)*(xx-cx)+(ax-cx)*(yy-cy))/denominator;val c=1-a-b
                    if(a>=0&&b>=0&&c>=0){heights[index]=(a*za+b*zb+c*zc).toFloat();filled++}
                }
            }
        }finally{triangles.release()}
        return filled
    }
}
