package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MappingCanopyRouteTest {
    private fun mission(version:Int=2,reference:Double=20.0):JSONObject {
        val feature=JSONObject("""{"id":"canopy-test","properties":{"class":"Assignment","title":"Canopy test"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}""")
        val capture=if(version==2)MappingCaptureProfile.canopyTargeted60 else MappingCaptureProfile.gapTargeted60
        val record=MappingMission(feature,"local-test").json(MappingResolution.profiles.first(),2.6,capture)
        record.put("gapCapturePlan",JSONObject().put("version",version).put("sourceLayerId","57ec0411-44f3-499f-91b8-a0e21767e75e")
            .put("sourceSurfaceSha256","a".repeat(64)).put("assignmentBoundary",feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0))
            .put("planningSurfaceHeightMeters",reference).put("captureLegs",JSONArray("""[{"targetStart":[-120.99975,39.00015],"targetEnd":[-120.99975,39.00035]},{"targetStart":[-120.99975,39.00035],"targetEnd":[-120.99975,39.00015]}]""")))
        return record
    }
    @Test fun canopyReferenceTightensBothNadirSpacingAndRowsAndOpposingViewsAimAtCanopy() {
        val canopy=DjiMappingRoute(mission());val ground=DjiMappingRoute(mission(reference=0.0))
        assertEquals(ground.passSpacing(ground.passes[0])*(canopy.heightMeters-20)/canopy.heightMeters,canopy.passSpacing(canopy.passes[0]),1e-9)
        val rows=canopy.legs.filter{it.passIndex==0}
        assertTrue(rows.size>ground.legs.count{it.passIndex==0})
        assertTrue(rows.all{abs(it.start.y-it.end.y)<1e-6})
        assertTrue(canopy.photoEstimate>ground.photoEstimate)
        assertTrue(canopy.interval>=1)
        val summary=canopy.summary().getJSONArray("capturePasses")
        repeat(2){assertEquals(20.0,summary.getJSONObject(it).getDouble("planningSurfaceHeightMeters"),0.0)}
        assertTrue(summary.getJSONObject(1).isNull("sideOverlapPercent"))
        for(leg in canopy.legs.filter{it.passIndex==1}) {
            val heading=Math.toDegrees(kotlin.math.atan2(leg.end.x-leg.start.x,leg.end.y-leg.start.y))
            val r=MappingCameraGeometry.rotation(heading,-60.0,0.0)
            val t=(20-canopy.heightMeters)/r[8]
            val centre=DjiMappingRoute.Point((leg.start.x+leg.end.x)/2+r[6]*t,(leg.start.y+leg.end.y)/2+r[7]*t)
            val coordinate=canopy.coordinate(centre)
            assertEquals(-120.99975,coordinate.first,1e-9);assertEquals(39.00025,coordinate.second,1e-9)
        }
        assertTrue(canopy.kmz().isNotEmpty())
    }
    @Test fun retainedVersionOneKeepsGroundBasedAnchorGrids() {
        val high=DjiMappingRoute(mission(1,20.0));val low=DjiMappingRoute(mission(1,0.0))
        assertEquals(high.legs.filter{it.passIndex<2},low.legs.filter{it.passIndex<2})
        assertEquals(high.passSpacing(high.passes[0]),low.passSpacing(low.passes[0]),0.0)
        assertTrue(high.passSpacing(high.passes[2])<low.passSpacing(low.passes[2]))
        assertTrue(high.kmz().isNotEmpty())
    }
    @Test fun incompatibleReferenceVersionBoundaryAndUnpairedLegsCannotExport() {
        for(change in listOf<(JSONObject)->Unit>(
            {it.getJSONObject("gapCapturePlan").put("version",1)},
            {it.getJSONObject("gapCapturePlan").put("planningSurfaceHeightMeters",70)},
            {it.getJSONObject("gapCapturePlan").put("assignmentBoundary",JSONArray("[[0,0],[1,0],[1,1],[0,0]]"))},
            {it.getJSONObject("gapCapturePlan").getJSONArray("captureLegs").getJSONObject(1).put("targetEnd",JSONArray("[-120.99975,39.0003]"))}
        )) {
            val record=mission();change(record)
            assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(record).kmz()}
        }
    }
}
