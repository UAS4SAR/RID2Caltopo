package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipInputStream
import kotlin.math.*

class MappingBrushRouteTest {
    private fun mission(canopy:Double):JSONObject {
        val feature=JSONObject("""{"id":"generic-brush","properties":{"class":"Assignment","title":"Generic brush"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.998,39],[-120.998,39.002],[-121,39.002],[-121,39]]]}}""")
        return MappingMission(feature,"test").json(MappingResolution.profiles.first(),2.0,MappingCaptureProfile.brushTrees,canopy)
    }
    @Test fun genericPlanDoesNotNeedPriorSurfaceAndExportsAbove200() {
        val route=DjiMappingRoute(mission(20.0))
        assertEquals(MappingCaptureProfile.brushTrees,MappingCaptureProfile.defaultVisible)
        assertFalse(route.mission.has("gapCapturePlan"))
        assertTrue(route.photoEstimate>200);assertTrue(route.photoEstimate<10000)
        assertEquals(listOf(-90.0,-70.0),route.passes.map{it.pitchDegrees})
        assertTrue(route.passes.all{it.forwardOverlapPercent==85.0&&it.sideOverlapPercent==85.0})
        ZipInputStream(route.kmz().inputStream()).use { zip->
            while(zip.nextEntry!=null){val xml=zip.readBytes().toString(Charsets.UTF_8)
                assertTrue(xml.contains("<wpml:payloadLensIndex>visable</wpml:payloadLensIndex>"))
                assertFalse(xml.contains("<wpml:payloadLensIndex>ir</wpml:payloadLensIndex>"))
                assertTrue(xml.contains("multipleDistance"));assertFalse(xml.contains("multipleTiming"))
                assertTrue(xml.contains("rotateYaw"))
            }
        }
    }
    @Test fun canopyHeightTightensOverlapAndOpticalRaysStayOnTarget() {
        val ground=DjiMappingRoute(mission(0.0));val canopy=DjiMappingRoute(mission(20.0))
        assertTrue(canopy.photoEstimate>ground.photoEstimate)
        canopy.passes.indices.forEach{assertTrue(canopy.passSpacing(canopy.passes[it])<ground.passSpacing(ground.passes[it]))}
        for(leg in canopy.legs){
            val yaw=Math.toDegrees(atan2(leg.end.x-leg.start.x,leg.end.y-leg.start.y))
            val rotation=MappingCameraGeometry.rotation(yaw,canopy.passes[leg.passIndex].pitchDegrees,0.0)
            val t=(20-canopy.heightMeters)/rotation[8]
            val point=DjiMappingRoute.Point((leg.start.x+leg.end.x)/2+rotation[6]*t,(leg.start.y+leg.end.y)/2+rotation[7]*t)
            val (lon,lat)=canopy.coordinate(point)
            assertTrue(lon in -121.0..-120.998);assertTrue(lat in 39.0..39.002)
        }
    }
    @Test fun canopyMustLeavePositivePlanningClearance() {
        assertThrows(IllegalArgumentException::class.java){mission(60.0)}
        assertThrows(IllegalArgumentException::class.java){mission(Double.NaN)}
    }
    @Test fun mapTestOneCentimeterFailsButTwoCentimetersClearsThirtyMeterCanopy() {
        val plan=mission(30.0)
        assertTrue(DjiMappingRoute(plan).heightMeters>=45.0)
        plan.getJSONObject("parameters").put("heightMeters",MappingResolution.heightMetersForGsd(1.0,MappingResolution.profiles.first().sensor))
        plan.getJSONObject("parameters").put("desiredGroundResolutionCmPerPixel",1.0)
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(plan)}
    }

}
