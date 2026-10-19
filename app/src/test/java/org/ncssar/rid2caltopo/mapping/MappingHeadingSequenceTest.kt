package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class MappingHeadingSequenceTest {
    private fun mission(profile:MappingCaptureProfile.Profile):JSONObject {
        val f=JSONObject("""{"id":"heading-test","properties":{"class":"Assignment","title":"Heading control"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}""")
        return MappingMission(f,"local-test").json(MappingResolution.profiles.first(),2.0,profile)
    }
    private fun files(route:DjiMappingRoute):Map<String,ByteArray> = mutableMapOf<String,ByteArray>().also{files->ZipInputStream(ByteArrayInputStream(route.kmz())).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}}}
    private fun text(e:Element,name:String)=e.getElementsByTagNameNS("http://www.dji.com/wpmz/1.0.6",name).item(0).textContent
    @Test fun bothKmzDocumentsRotateAircraftBeforeGimbalAndHoldHeadingThroughoutObliqueLeg() {
        for(profile in MappingCaptureProfile.approved60Trials) {
            val route=DjiMappingRoute(mission(profile))
            files(route).forEach{(file,bytes)->
                val doc=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
                val points=doc.getElementsByTagName("Placemark")
                route.legs.forEachIndexed{index,leg->
                    val start=points.item(index*2) as Element;val end=points.item(index*2+1) as Element
                    run {
                        for(point in listOf(start,end)){assertEquals("smoothTransition",text(point,"waypointHeadingMode"));assertEquals("followBadArc",text(point,"waypointHeadingPathMode"));if(file.endsWith("kml"))assertEquals("0",text(point,"useGlobalHeadingParam"))}
                        val group=start.getElementsByTagNameNS("http://www.dji.com/wpmz/1.0.6","actionGroup").item(0) as Element
                        assertEquals("sequence",text(group,"actionGroupMode"))
                        val actions=group.getElementsByTagNameNS("http://www.dji.com/wpmz/1.0.6","action")
                        assertEquals("rotateYaw",text(actions.item(0) as Element,"actionActuatorFunc"))
                        assertEquals("gimbalRotate",text(actions.item(1) as Element,"actionActuatorFunc"))
                        val aircraft=text(actions.item(0) as Element,"aircraftHeading").toDouble()
                        val camera=text(actions.item(1) as Element,"gimbalYawRotateAngle").toDouble()
                        val bearing=Math.toDegrees(kotlin.math.atan2(leg.end.x-leg.start.x,leg.end.y-leg.start.y))
                        assertEquals(bearing,aircraft,1e-9);assertEquals(0.0,camera-aircraft,1e-9)
                        assertEquals(aircraft,text(start,"waypointHeadingAngle").toDouble(),1e-9);assertEquals(aircraft,text(end,"waypointHeadingAngle").toDouble(),1e-9)
                        assertEquals("zoom",text(actions.item(2) as Element,"actionActuatorFunc"))
                        assertEquals(24.0,text(actions.item(2) as Element,"focalLength").toDouble(),0.0)
                        assertEquals("0",text(actions.item(2) as Element,"payloadPositionIndex"))
                        assertEquals(if(index==0)4 else 3,actions.length) // Camera selection on every leg; directory naming only on the first.
                        if(index==0)assertEquals("customDirName",text(actions.item(3) as Element,"actionActuatorFunc"))
                        repeat(actions.length){actionIndex->assertEquals(actionIndex.toString(),text(actions.item(actionIndex) as Element,"actionId"))}
                    }
                }
            }
        }
    }
    @Test fun savedLegacyHeadingPolicyAndUnknownPolicyAreHandledExplicitly() {
        val manifest=mission(MappingCaptureProfile.nadir60Standard)
        manifest.getJSONObject("parameters").remove("cameraHeadingPolicy")
        val route=DjiMappingRoute(manifest)
        val xml=files(route).getValue("wpmz/waylines.wpml").toString(Charsets.UTF_8)
        assertFalse(route.aircraftFirstHeading);assertFalse(xml.contains("rotateYaw"));assertFalse(xml.contains("smoothTransition"))
        manifest.getJSONObject("parameters").put("cameraHeadingPolicy","unknown")
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(manifest)}
    }
    private fun targeted():JSONObject=mission(MappingCaptureProfile.gapTargeted60).also{m->m.put("gapCapturePlan",JSONObject()
        .put("version",1).put("sourceLayerId","5e3e37f4-e883-4443-a2ee-b5cb78ccdd53").put("sourceSurfaceSha256","0".repeat(64))
        .put("assignmentBoundary",m.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0))
        .put("planningSurfaceHeightMeters",22.0).put("captureLegs",JSONArray("""[{"targetStart":[-120.99975,39.0001],"targetEnd":[-120.99975,39.0004]},{"targetStart":[-120.99975,39.0004],"targetEnd":[-120.99975,39.0001]}]""")))}
    @Test fun targetedOpposingViewsUseCanopySpacingAndRetainBothNadirGrids() {
        val m=targeted();val route=DjiMappingRoute(m);assertTrue(route.kmz().isNotEmpty())
        assertEquals(3,route.passes.size);assertTrue(route.legs.any{it.passIndex==0});assertTrue(route.legs.any{it.passIndex==1});assertEquals(2,route.legs.count{it.passIndex==2})
        val plane=route.summary().getJSONArray("capturePasses").getJSONObject(2)
        assertEquals(22.0,plane.getDouble("planningSurfaceHeightMeters"),0.0)
        assertEquals((route.heightMeters-22)*kotlin.math.tan(Math.toRadians(30.0)),plane.getDouble("opticalCentreOffsetMeters"),1e-9)
        val normal=DjiMappingRoute(mission(MappingCaptureProfile.nadir60))
        assertEquals(normal.passSpacing(normal.passes[1])*(route.heightMeters-22)/route.heightMeters,route.passSpacing(route.passes[2]),1e-9)
        assertTrue(route.photoEstimate<=200)
    }
    @Test fun targetedPlanRejectsWrongBoundaryUnpairedViewsAndLegacyHeading() {
        val a=targeted();a.getJSONObject("gapCapturePlan").put("assignmentBoundary",JSONArray("[[0,0],[1,0],[1,1]]"))
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(a)}
        val b=targeted();b.getJSONObject("gapCapturePlan").getJSONArray("captureLegs").getJSONObject(1).put("targetEnd",JSONArray("[-120.9998,39.0001]"))
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(b)}
        val c=targeted();c.getJSONObject("parameters").remove("cameraHeadingPolicy")
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(c)}
    }
}
