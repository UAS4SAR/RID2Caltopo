package org.ncssar.rid2caltopo.mapping

// Historical Android policy for comparison fixtures only. Production uses native/mapping/Reconstruction.cpp.

import kotlin.math.*

/** Preserve within-pitch stereo neighbors as additional capture angles are introduced. */
internal object MappingStereoPairs {
    data class Camera(val centre:DoubleArray,val forward:DoubleArray,val pitch:Double)
    fun select(cameras:List<Camera>):List<Pair<Int,Int>> {
        val candidates=cameras.mapIndexed{i,a->cameras.indices.filter{j->
            j!=i && a.forward.indices.sumOf{axis->a.forward[axis]*cameras[j].forward[axis]}>=cos(Math.toRadians(55.0)) &&
                hypot(a.centre[0]-cameras[j].centre[0],a.centre[1]-cameras[j].centre[1]) in 7.0..24.0
        }.sortedWith(compareBy<Int>{j->hypot(a.centre[0]-cameras[j].centre[0],a.centre[1]-cameras[j].centre[1])}.thenBy{it})}
        val pairs=linkedSetOf<Pair<Int,Int>>()
        fun add(i:Int,j:Int){pairs.add(min(i,j) to max(i,j))}
        // Keep the previous pair set and processing order, including useful mixed-angle pairs.
        candidates.forEachIndexed{i,neighbors->neighbors.take(3).forEach{add(i,it)}}
        // A nearby oblique must not consume a slot belonging to the nadir-only network.
        // Same baseline/attitude gates apply; at most six directed neighbors per camera.
        candidates.forEachIndexed{i,neighbors->neighbors.filter{j->abs(cameras[i].pitch-cameras[j].pitch)<10.0}.take(3).forEach{add(i,it)}}
        return pairs.toList()
    }
}
