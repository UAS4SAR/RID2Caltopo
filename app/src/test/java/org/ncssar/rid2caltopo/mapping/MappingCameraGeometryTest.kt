package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MappingCameraGeometryTest {
    @Test fun nadirRetainsYawRollEquivalentEulerRegression() {
        val a=MappingCameraGeometry.rotation(90.0,-90.0,0.0)
        val b=MappingCameraGeometry.rotation(-90.0,-90.0,180.0)
        assertArrayEquals(a,b,1e-12)
        assertArrayEquals(doubleArrayOf(0.0,-1.0,0.0,-1.0,0.0,0.0,0.0,0.0,-1.0),a,1e-12)
    }
    @Test fun obliqueRayUsesAbsoluteHeadingAndPitchWithCorrectImageAxes() {
        for(pitch in listOf(-90.0,-60.0,-45.0))for(yaw in listOf(0.0,90.0,180.0,-90.0))for(roll in listOf(0.0,12.0,180.0)) {
            val r=MappingCameraGeometry.rotation(yaw,pitch,roll)
            repeat(3){i->repeat(3){j->assertEquals(if(i==j)1.0 else 0.0,(0..2).sumOf{k->r[i*3+k]*r[j*3+k]},1e-12)}}
            val centre=doubleArrayOf(0.0,0.0,60.0);val t=60.0/-r[8]
            val point=doubleArrayOf(r[6]*t,r[7]*t,0.0)
            val px=MappingCameraGeometry.project(r,centre,point,600.0,503.5,377.5)!!
            assertEquals(503.5,px[0],1e-8);assertEquals(377.5,px[1],1e-8)
            assertEquals(60/cos(Math.toRadians(90+pitch)),px[2],1e-8)
            assertNull(MappingCameraGeometry.project(r,centre,doubleArrayOf(-r[6],-r[7],60-r[8]),600.0,503.5,377.5))
        }
        val r=MappingCameraGeometry.rotation(0.0,-60.0,0.0)
        assertTrue(MappingCameraGeometry.project(r,doubleArrayOf(0.0,0.0,60.0),doubleArrayOf(1.0,60*tan(Math.toRadians(30.0)),0.0),600.0,0.0,0.0)!![0]>0)
    }
    @Test fun slantBoundsUseActualSharedStructureAndPreserveHighCanopy() {
        val r=MappingCameraGeometry.rotation(0.0,-60.0,0.0)
        val points=(0..40).map{doubleArrayOf(0.0,30.0,it/2.0)}
        val bounds=MappingCameraGeometry.depthBounds(r,doubleArrayOf(0.0,0.0,60.0),points)!!
        val actual=points.map{MappingCameraGeometry.transform(r,doubleArrayOf(it[0],it[1],it[2]-60))[2]}
        assertTrue(bounds.first<actual.min());assertTrue(bounds.second>actual.max())
        assertTrue(bounds.second>60) // Optical depth differs from nadir height.
        assertNull(MappingCameraGeometry.depthBounds(r,doubleArrayOf(0.0,0.0,60.0),points.take(10)))
    }
    @Test fun laserRejectsWrongRayRangeAndBehindCamera() {
        val r=MappingCameraGeometry.rotation(0.0,-60.0,0.0);val c=doubleArrayOf(0.0,0.0,60.0)
        val target=doubleArrayOf(0.0,60*tan(Math.toRadians(30.0)),0.0);val distance=60/cos(Math.toRadians(30.0))
        assertTrue(MappingCameraGeometry.usableLaser(r,c,target,distance))
        assertFalse(MappingCameraGeometry.usableLaser(r,c,doubleArrayOf(10.0,target[1],0.0),distance))
        assertFalse(MappingCameraGeometry.usableLaser(r,c,target,distance+5))
        assertFalse(MappingCameraGeometry.usableLaser(r,c,doubleArrayOf(0.0,-target[1],120.0),distance))
    }
    @Test fun disparityWindowRejectsImpossibleImageWidthsBeforeNativeAllocation() {
        assertNull(MappingCameraGeometry.disparityWindow(1e8,5.0 to 10.0,true,1008))
        assertNull(MappingCameraGeometry.disparityWindow(Double.NaN,5.0 to 10.0,true,1008))
        assertNull(MappingCameraGeometry.disparityWindow(8000.0,5.0 to 100.0,true,1008))
        val positive=MappingCameraGeometry.disparityWindow(7200.0,37.0 to 43.0,true,1008)!!
        val negative=MappingCameraGeometry.disparityWindow(7200.0,37.0 to 43.0,false,1008)!!
        assertEquals(0,positive.range%16);assertEquals(positive.range,negative.range)
        assertEquals(-positive.minimum-positive.range,negative.minimum)
    }

}
