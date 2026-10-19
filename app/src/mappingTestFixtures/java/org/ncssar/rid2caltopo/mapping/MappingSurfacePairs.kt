package org.ncssar.rid2caltopo.mapping

// Historical Android policy for comparison fixtures only. Production uses native/mapping/Reconstruction.cpp.

import kotlin.math.*

/** Exposure geometry proposes overlaps; image matching still has to verify them.
 * Near-identical exposures can support a track, but cannot establish its depth.
 */
internal object MappingSurfacePairs {
    data class Camera(val centre:DoubleArray,val rotation:DoubleArray)
    fun select(cameras:List<Camera>,width:Int,height:Int,focal:Double):Set<Pair<Int,Int>> {
        val cx=(width-1)*.5;val cy=(height-1)*.5
        val ground=cameras.map{camera->buildList {
            for(y in 0..4)for(x in 0..4){
                val ray=doubleArrayOf((x*(width-1)/4.0-cx)/focal,(y*(height-1)/4.0-cy)/focal,1.0)
                val world=DoubleArray(3){axis->(0..2).sumOf{k->camera.rotation[k*3+axis]*ray[k]}}
                if(world[2]<-.1){val distance= -camera.centre[2]/world[2]
                    if(distance>0)add(DoubleArray(3){camera.centre[it]+distance*world[it]})}
            }
        }}
        fun overlap(i:Int,j:Int):Int=ground[i].count{point->
            val p=MappingCameraGeometry.project(cameras[j].rotation,cameras[j].centre,point,focal,cx,cy)
            p!=null&&p[0] in 0.0..(width-1.0)&&p[1] in 0.0..(height-1.0)
        }+ground[j].count{point->
            val p=MappingCameraGeometry.project(cameras[i].rotation,cameras[i].centre,point,focal,cx,cy)
            p!=null&&p[0] in 0.0..(width-1.0)&&p[1] in 0.0..(height-1.0)
        }
        val pairs=linkedSetOf<Pair<Int,Int>>()
        cameras.forEachIndexed{i,camera->
            // Keep the qualified nearby-position candidates as a terrain-height
            // fallback. Filter baselines before taking the bounded selection.
            val nearby=cameras.indices.filter{j->j!=i&&hypot(camera.centre[0]-cameras[j].centre[0],camera.centre[1]-cameras[j].centre[1]) in 2.0..90.0}
                .sortedBy{j->hypot(camera.centre[0]-cameras[j].centre[0],camera.centre[1]-cameras[j].centre[1])}.take(12)
            nearby.forEach{j->pairs.add(min(i,j) to max(i,j))}
            cameras.indices.filter{j->j!=i&&hypot(camera.centre[0]-cameras[j].centre[0],camera.centre[1]-cameras[j].centre[1])<=120}
                .map{j->j to overlap(i,j)}.filter{it.second>=8}.sortedByDescending{it.second}.take(12)
                .forEach{(j,_)->pairs.add(min(i,j) to max(i,j))}
        }
        return pairs
    }
}
