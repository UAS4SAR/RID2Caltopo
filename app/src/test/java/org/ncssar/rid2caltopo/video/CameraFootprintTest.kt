package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CameraFootprintTest {
    private fun input(tilt: Double = -90.0, azimuth: Double = 0.0) =
        CameraFootprintInput(39.0,-121.0,39.0,-121.0,100.0,azimuth,tilt,90.0,60.0)
    @Test fun nadirRectangleUsesBothFieldsOfView() {
        val points=CameraFootprintGeometry.project(input())
        assertEquals(4,points.size)
        assertTrue(points.all { !it.clipped && it.corner })
        val north=Math.toRadians(points[0].latitude-39)*6378137
        val east=Math.toRadians(points[0].longitude+121)*6378137*cos(Math.toRadians(39.0))
        assertEquals(100*tan(PI/6),north,0.001)
        assertEquals(-100.0,east,0.001)
    }
    @Test fun terrainMatchesFlatPlaneRegardlessOfAbsoluteElevation() {
        val flat=CameraFootprintGeometry.project(input())
        val terrain=CameraFootprintGeometry.project(input(),elevation={ _,_ -> 900.0 })
        assertEquals(32,terrain.size)
        for (index in 0..3) {
            assertEquals(flat[index].latitude,terrain[index*8].latitude,1e-7)
            assertEquals(flat[index].longitude,terrain[index*8].longitude,1e-7)
        }
    }
    @Test fun coverageBoundaryClipsBeforeFlatIntersection() {
        val points=CameraFootprintGeometry.project(input(),elevation={ lat,lon ->
            if(abs(lat-39)<0.0002 && abs(lon+121)<0.0002) 900.0 else null
        })
        assertEquals(32,points.size)
        assertTrue(points.all { it.clipped })
        assertTrue(points.all { abs(it.latitude-39)<0.000201 && abs(it.longitude+121)<0.000201 })
    }
    @Test fun horizonMissingCoverageAndCancellation() {
        val points=CameraFootprintGeometry.project(input(tilt=0.0))
        assertTrue(points[0].clipped && points[1].clipped)
        assertTrue(points.all { it.latitude.isFinite() && it.longitude.isFinite() })
        assertTrue(CameraFootprintGeometry.project(input(),elevation={ _,_ -> null }).isEmpty())
        assertTrue(CameraFootprintGeometry.project(input(),cancelled={ true }).isEmpty())
    }
    @Test fun azimuthRotatesNadirRectangle() {
        val p=CameraFootprintGeometry.project(input(azimuth=90.0))[0]
        assertEquals(100.0,Math.toRadians(p.latitude-39)*6378137,0.001)
        assertEquals(100*tan(PI/6),Math.toRadians(p.longitude+121)*6378137*cos(Math.toRadians(39.0)),0.001)
    }
    @Test fun launchReferencedHeightAccountsForRaisedGroundAtAircraft() {
        val i=input().copy(launchLatitude=38.99)
        val points=CameraFootprintGeometry.project(i,elevation={ lat,_ -> if(lat<38.995) 900.0 else 950.0 })
        assertEquals(-50.0,Math.toRadians(points[0].longitude+121)*6378137*cos(Math.toRadians(39.0)),0.01)
        assertTrue(points.all { !it.clipped })
    }
    @Test fun invalidHeightAndFovAreRejected() {
        assertTrue(CameraFootprintGeometry.project(input().copy(height=0.0,horizontalFov=180.0)).isEmpty())
    }
    @Test fun cornersSurviveMissingOrUnfinishedTerrainAndFollowNewPose() {
        val pending=cameraFootprintDrawing(input(),null)
        val missing=cameraFootprintDrawing(input(),emptyList())
        assertEquals(4,pending.corners.size)
        assertEquals(pending.corners,missing.corners)
        assertTrue(pending.boundary.isEmpty() && missing.boundary.isEmpty())
        val moved=cameraFootprintDrawing(input().copy(latitude=39.001),null)
        assertEquals(0.001,moved.corners[0].latitude-pending.corners[0].latitude,1e-9)
    }
    @Test fun fourRayTerrainPassHasBoundedSampleCount() {
        var samples=0
        val points=CameraFootprintGeometry.project(input(),elevation={ _,_ -> samples++; 900.0 },edgeSubdivisions=1)
        assertEquals(4,points.size)
        assertTrue(points.all { it.corner })
        assertTrue("Four rays should need fewer than 200 samples here; got $samples",samples<200)
        val drawing=cameraFootprintDrawing(input(),points)
        assertEquals(points,drawing.boundary)
        assertEquals(points,drawing.corners)
    }
    @Test fun cancelledTerrainBudgetCannotEraseImmediateCorners() {
        var samples=0
        val terrain=CameraFootprintGeometry.project(input(tilt=0.0),elevation={ _,_ -> samples++; 900.0 },
            cancelled={ samples>=12 },edgeSubdivisions=1)
        assertTrue(terrain.isEmpty())
        assertTrue(samples<=12)
        assertEquals(4,cameraFootprintDrawing(input(tilt=0.0),terrain).corners.size)
    }
    @Test fun cornerArmsPointAlongAdjacentSkewedEdges() {
        val points=listOf(CameraFootprintScreenPoint(0.0,0.0),CameraFootprintScreenPoint(100.0,20.0),
            CameraFootprintScreenPoint(80.0,120.0),CameraFootprintScreenPoint(-20.0,80.0))
        val strokes=cameraFootprintCornerStrokes(points,12.0)
        assertEquals(8,strokes.size)
        strokes.forEachIndexed { index, stroke ->
            val corner=index/2
            val neighbor=points[(corner+if(index%2==0) 3 else 1)%4]
            val dx=stroke.end.x-stroke.start.x; val dy=stroke.end.y-stroke.start.y
            assertEquals(points[corner],stroke.start)
            assertEquals(12.0,hypot(dx,dy),1e-8)
            assertEquals(0.0,dx*(neighbor.y-stroke.start.y)-dy*(neighbor.x-stroke.start.x),1e-8)
            assertTrue(dx*(neighbor.x-stroke.start.x)+dy*(neighbor.y-stroke.start.y)>0)
        }
    }
    @Test fun shortOrCollapsedEdgesDoNotProduceOverlappingOrInvalidArms() {
        val points=listOf(CameraFootprintScreenPoint(0.0,0.0),CameraFootprintScreenPoint(6.0,0.0),
            CameraFootprintScreenPoint(6.0,6.0),CameraFootprintScreenPoint(0.0,6.0))
        assertTrue(cameraFootprintCornerStrokes(points,12.0).all {
            abs(hypot(it.end.x-it.start.x,it.end.y-it.start.y)-2.4)<1e-8
        })
        val collapsed=points.toMutableList().apply { this[1]=this[0] }
        assertEquals(6,cameraFootprintCornerStrokes(collapsed,12.0).size)
        assertTrue(cameraFootprintCornerStrokes(points,Double.NaN).isEmpty())
    }
    @Test fun identicalPoseReusesTerrainAndASlowOrbitStaysInsideTolerance() {
        val origin = input()
        assertTrue(cameraFootprintTerrainReusable(origin, origin))
        val moved = origin.copy(latitude = 39.00005, azimuth = 6.0, tilt = -88.0, height = 104.0)
        assertTrue(cameraFootprintDistanceMeters(39.0, -121.0, 39.00005, -121.0) < CAMERA_FOOTPRINT_TERRAIN_POSITION_METERS)
        assertTrue(cameraFootprintTerrainReusable(origin, moved))
        assertEquals(6.0, cameraFootprintAngleDeltaDegrees(359.0, 5.0), 1e-9)
        assertTrue(cameraFootprintTerrainReusable(input(azimuth = 359.0), input(azimuth = 5.0)))
    }
    @Test fun largePoseChangeDropsTheTerrainOutline() {
        val origin = input()
        assertFalse(cameraFootprintTerrainReusable(origin, origin.copy(latitude = 39.01)))
        assertFalse(cameraFootprintTerrainReusable(input(azimuth = 359.0), input(azimuth = 20.0)))
        assertFalse(cameraFootprintTerrainReusable(origin, origin.copy(horizontalFov = 94.0)))
        assertFalse(cameraFootprintTerrainReusable(origin, origin.copy(verticalFov = 64.0)))
        assertFalse(cameraFootprintTerrainReusable(origin, origin.copy(height = 120.0)))
        assertFalse(cameraFootprintTerrainReusable(origin, origin.copy(tilt = -80.0)))
        assertFalse(cameraFootprintTerrainReusable(origin, origin.copy(launchLatitude = 39.01)))
        assertTrue(cameraFootprintDistanceMeters(0.0, 179.9, 0.0, -179.9) < 30_000.0)
    }
    @Test fun cancelledPassKeepsPreviousOutlineUntilThePoseLeavesTolerance() {
        val origin = input()
        val near = origin.copy(longitude = -121.0001)
        val far = origin.copy(latitude = 40.0)
        val terrain = listOf(CameraFootprintVertex(1.0, 2.0, false, true))
        val previous = mapOf("a" to (origin to terrain))
        val kept = mergeCameraFootprintTerrain(previous, mapOf("a" to (near to emptyList())), mapOf("a" to near))
        assertEquals(terrain, kept["a"]?.second)
        assertTrue(mergeCameraFootprintTerrain(previous, emptyMap(), mapOf("a" to far)).isEmpty())
        val fresh = listOf(CameraFootprintVertex(3.0, 4.0, false, true))
        assertEquals(fresh, mergeCameraFootprintTerrain(previous, mapOf("a" to (near to fresh)), mapOf("a" to near))["a"]?.second)
        assertTrue(mergeCameraFootprintTerrain(previous, previous, emptyMap()).isEmpty())
        assertNull(cameraFootprintTerrainForDisplay(origin to emptyList(), origin))
    }
    @Test fun liveCornersTrackTheNewPoseWhileAReusableOutlineStays() {
        val origin = input()
        val moved = origin.copy(latitude = 39.00005)
        val terrain = CameraFootprintGeometry.project(origin, elevation = { _, _ -> 900.0 }, edgeSubdivisions = 1)
        val drawing = cameraFootprintLiveDrawing(moved, cameraFootprintTerrainForDisplay(origin to terrain, moved))
        assertEquals(terrain, drawing.boundary)
        assertEquals(CameraFootprintGeometry.project(moved), drawing.corners)
        assertNull(cameraFootprintTerrainForDisplay(origin to terrain, origin.copy(latitude = 39.01)))
        val hidden = cameraFootprintLiveDrawing(origin.copy(latitude = 39.01), null)
        assertTrue(hidden.boundary.isEmpty())
        assertEquals(CameraFootprintGeometry.project(origin.copy(latitude = 39.01)), hidden.corners)
    }
    @Test fun adSubframeRaysScaleTheTangentAndPreserveAspect() {
        assertNull(cameraFootprintAdScanRect(Double.NaN))
        val rect = checkNotNull(cameraFootprintAdScanRect(0.5))
        assertEquals(0.25, rect.left, 1e-12)
        assertEquals(0.75, rect.bottom, 1e-12)
        val h = tan(Math.toRadians(45.0))
        val v = tan(Math.toRadians(30.0))
        val offsets = checkNotNull(cameraFootprintSubframeRayOffsets(90.0, 60.0, rect))
        assertEquals(-0.5 * h, offsets[0].first, 1e-9)
        assertEquals(0.5 * v, offsets[0].second, 1e-9)
        assertEquals(0.5 * h, offsets[1].first, 1e-9)
        assertEquals(-0.5 * v, offsets[2].second, 1e-9)
        assertEquals(-0.5 * h, offsets[3].first, 1e-9)
        val shifted = checkNotNull(cameraFootprintSubframeRayOffsets(90.0, 60.0, CameraFootprintFrameRect(0.0, 0.0, 0.5, 0.5)))
        assertEquals(-h, shifted[0].first, 1e-9)
        assertEquals(0.0, shifted[1].first, 1e-9)
        assertEquals(v, shifted[0].second, 1e-9)
        assertNull(cameraFootprintSubframeRayOffsets(90.0, 60.0, CameraFootprintFrameRect(-0.1, 0.0, 0.5, 1.0)))
        val full = input()
        assertEquals(90.0, cameraFootprintApplyingAdScanZone(full, null).horizontalFov, 0.0)
        assertEquals(90.0, cameraFootprintApplyingAdScanZone(full, 1.0).horizontalFov, 1e-9)
        val half = cameraFootprintApplyingAdScanZone(full, 0.5)
        assertEquals(half.horizontalFov, cameraFootprintApplyingAdScanZone(full, 0.1).horizontalFov, 1e-9)
        assertEquals(Math.toDegrees(2.0 * atan(0.5 * h)), half.horizontalFov, 1e-6)
        assertEquals(Math.toDegrees(2.0 * atan(0.5 * v)), half.verticalFov, 1e-6)
        assertEquals(
            h / v,
            tan(Math.toRadians(half.horizontalFov / 2.0)) / tan(Math.toRadians(half.verticalFov / 2.0)),
            1e-9,
        )
        val points = CameraFootprintGeometry.project(half)
        val north = Math.toRadians(points[0].latitude - 39.0) * 6378137
        val east = Math.toRadians(points[0].longitude + 121.0) * 6378137 * cos(Math.toRadians(39.0))
        assertEquals(50.0 * v, north, 0.05)
        assertEquals(-50.0, east, 0.05)
        val halfAngle = CameraFootprintGeometry.project(full.copy(horizontalFov = 45.0, verticalFov = 30.0))
        val halfAngleEast = Math.toRadians(halfAngle[0].longitude + 121.0) * 6378137 * cos(Math.toRadians(39.0))
        assertTrue(abs(halfAngleEast - east) > 5.0)
    }
    @Test fun footprintPreferenceKeyIsStableAndBlankStaysUnset() {
        assertEquals("ABC-1", cameraFootprintPreferenceKey(" abc-1 "))
        assertEquals("ABC-1", cameraFootprintPreferenceKey("abc-1"))
        assertNull(cameraFootprintPreferenceKey("  "))
        assertNull(cameraFootprintPreferenceKey(""))
    }
}
