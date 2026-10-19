package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test

class MappingSurfacePairsTest {
    @Test fun obliqueFindsSharedGroundBeyondNearbyAircraftAndIncludesRepeatedViews() {
        val nadir=MappingCameraGeometry.rotation(0.0,-90.0,0.0)
        val oblique=MappingCameraGeometry.rotation(0.0,-60.0,0.0)
        val cameras=mutableListOf(MappingSurfacePairs.Camera(doubleArrayOf(0.0,-35.0,60.0),oblique))
        // The closest aircraft look away from the first camera's ground view.
        repeat(15){cameras.add(MappingSurfacePairs.Camera(doubleArrayOf(0.0,-40.0-it,60.0),MappingCameraGeometry.rotation(180.0,-60.0,0.0)))}
        cameras.add(MappingSurfacePairs.Camera(doubleArrayOf(0.0,0.0,60.0),nadir))
        cameras.add(MappingSurfacePairs.Camera(doubleArrayOf(0.0,-35.01,60.0),oblique))
        val pairs=MappingSurfacePairs.select(cameras,1008,756,600.0)
        assertTrue(pairs.contains(0 to 16))
        assertTrue(pairs.contains(0 to 17))
        assertTrue(pairs.all{it.first<it.second})
        assertTrue(pairs.size<=cameras.size*24)
    }
}
