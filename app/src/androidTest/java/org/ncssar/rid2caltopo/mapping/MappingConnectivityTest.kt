package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged solver with separate, individually triangulatable blocks. */
@RunWith(AndroidJUnit4::class)
class MappingConnectivityTest {
    private fun solve(sizes:List<Int>,progress:MappingSurfaceNative.Progress):DoubleArray {
        val rotation=MappingCameraGeometry.rotation(0.0,-90.0,0.0)
        val cameras=mutableListOf<Double>();val lasers=mutableListOf<Double>();val observations=mutableListOf<Double>()
        var cameraOffset=0;var pointCount=0
        sizes.forEachIndexed { block,size ->
            val offset=block*150.0
            val centres=(0 until size).map{i->doubleArrayOf(offset-8+(i/6)*4.0,-10+(i%6)*4.0,60.0)}
            centres.forEach{c->cameras.addAll(c.toList()+rotation.toList()+listOf(.02,.02,.03));lasers.addAll(listOf(c[0],c[1],0.0))}
            for(y in -24..24 step 2)for(x in -24..24 step 2){
                val point=doubleArrayOf(offset+x,y.toDouble(),0.0)
                centres.forEachIndexed { i,c ->
                    val pixel=MappingCameraGeometry.project(rotation,c,point,600.0,503.5,377.5)!!
                    observations.addAll(listOf((cameraOffset+i).toDouble(),pointCount.toDouble(),pixel[0],pixel[1]))
                }
                pointCount++
            }
            cameraOffset+=size
        }
        return MappingSurfaceNative.adjustAttitude(cameras.toDoubleArray(),observations.toDoubleArray(),pointCount,
            1008,756,600.0,lasers.toDoubleArray(),progress)
    }

    @Test fun minorDisconnectedBlockCannotAbortOrContaminateDominantSurface() {
        val rounds=mutableListOf<List<Int>>();var codes=IntArray(0)
        val result=solve(listOf(30,3),object:MappingSurfaceNative.Progress {
            override fun iteration(iteration:Int,cost:Double)=true
            override fun connectivity(round:Int,groupByPhoto:IntArray,observationsByPhoto:IntArray,exclusionByPhoto:IntArray) {
                rounds.add(groupByPhoto.filter{it>=0}.groupingBy{it}.eachCount().values.sortedDescending())
                codes=exclusionByPhoto
            }
        })
        assertEquals(listOf(30,3),rounds.first());assertEquals(listOf(30),rounds.last())
        assertEquals(4,rounds.size) // Includes the connectivity check after the final outlier removal.
        assertArrayEquals(IntArray(30)+IntArray(3){2},codes)
        assertEquals(30.0,result[0],0.0)
        assertTrue(result.copyOfRange(12,12+30*12).all(Double::isFinite))
        assertTrue(result.copyOfRange(12+30*12,12+33*12).all(Double::isNaN))
        val points=result.copyOfRange(12+33*12,result.size-1)
        assertTrue(points.take(625*3).all(Double::isFinite))
        assertTrue(points.drop(625*3).all(Double::isNaN))
        for(i in 0 until 625)assertEquals(0.0,points[i*3+2],.03)
        assertTrue(result[7]<.02);assertTrue(result[9]<.01);assertTrue(result.last()>=6)
    }

    @Test fun substantialSplitReturnsOneQualifiedBlockWithoutInventingConnections() {
        var groups=emptyList<Int>();var codes=IntArray(0)
        val result=solve(listOf(6,6),object:MappingSurfaceNative.Progress {
            override fun iteration(iteration:Int,cost:Double)=true
            override fun connectivity(round:Int,groupByPhoto:IntArray,observationsByPhoto:IntArray,exclusionByPhoto:IntArray) {
                if(round==1)groups=groupByPhoto.filter{it>=0}.groupingBy{it}.eachCount().values.toList()
                codes=exclusionByPhoto
            }
        })
        assertEquals(listOf(6,6),groups);assertEquals(6,codes.count{it==2})
        assertEquals(6.0,result[0],0.0);assertTrue(result[7]<.02);assertTrue(result[9]<.01)
        assertEquals(6,(0 until 12).count{i->result.copyOfRange(12+i*12,24+i*12).all(Double::isFinite)})
        assertEquals(625*3,result.copyOfRange(12+12*12,result.size-1).count(Double::isFinite))
    }
}
