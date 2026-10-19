package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test

class MappingStereoPairsTest {
    private fun camera(x:Double,y:Double,pitch:Double,yaw:Double=0.0)=MappingStereoPairs.Camera(doubleArrayOf(x,y,60.0),MappingCameraGeometry.rotation(yaw,pitch,0.0).copyOfRange(6,9),pitch)
    @Test fun nearbyObliquesRetainEveryNadirNeighborAndAddMixedPairs() {
        val nadir=listOf(camera(0.0,0.0,-90.0),camera(12.0,0.0,-90.0),camera(-12.0,0.0,-90.0),camera(0.0,12.0,-90.0),camera(0.0,-12.0,-90.0))
        val original=MappingStereoPairs.select(nadir)
        val mixed=MappingStereoPairs.select(nadir+listOf(camera(8.0,0.0,-60.0),camera(-8.0,0.0,-60.0),camera(0.0,8.0,-60.0)))
        assertTrue(mixed.containsAll(original))
        assertTrue(mixed.any{it.first<5&&it.second>=5})
        assertTrue(mixed.all{it.first<it.second});assertEquals(mixed.size,mixed.toSet().size)
    }
    @Test fun baselineAndAttitudeGatesStillRejectUnusableViews() {
        val cameras=listOf(camera(0.0,0.0,-60.0),camera(1.0,0.0,-60.0),camera(30.0,0.0,-60.0),camera(12.0,0.0,-60.0,180.0),camera(0.0,12.0,-60.0))
        val pairs=MappingStereoPairs.select(cameras)
        assertFalse(pairs.contains(0 to 1));assertFalse(pairs.contains(0 to 2));assertFalse(pairs.contains(0 to 3));assertTrue(pairs.contains(0 to 4))
    }
    @Test fun aSinglePitchKeepsLegacyNearestThreeAndWorkIsBounded() {
        val cameras=(0 until 30).map{camera((it%6)*8.0,(it/6)*8.0,-90.0)}
        val pairs=MappingStereoPairs.select(cameras)
        val legacy=linkedSetOf<Pair<Int,Int>>()
        cameras.indices.forEach{i->cameras.indices.filter{j->j!=i&&kotlin.math.hypot(cameras[i].centre[0]-cameras[j].centre[0],cameras[i].centre[1]-cameras[j].centre[1]) in 7.0..24.0}.sortedBy{j->kotlin.math.hypot(cameras[i].centre[0]-cameras[j].centre[0],cameras[i].centre[1]-cameras[j].centre[1])}.take(3).forEach{j->legacy.add(minOf(i,j) to maxOf(i,j))}}
        assertEquals(legacy.toList(),pairs)
        val mixed=cameras+(0 until 30).map{camera((it%6)*8.0+1,(it/6)*8.0,-60.0)}
        assertTrue(MappingStereoPairs.select(mixed).size<=mixed.size*6)
    }
}
