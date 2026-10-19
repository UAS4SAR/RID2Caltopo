package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Pure on-tablet export check. Does not connect to Pilot 2, create a stored flight or start an aircraft. */
@RunWith(AndroidJUnit4::class)
class MappingCaptureExportTest {
    private fun mission(capture:MappingCaptureProfile.Profile):JSONObject {
        val feature=JSONObject("""{"id":"synthetic-small-site","properties":{"class":"Assignment","title":"Synthetic export control"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}""")
        return MappingMission(feature,"synthetic-local-test").json(MappingResolution.profiles.first(),2.0,capture)
    }
    @Test fun installedBuildExportsOnlyApprovedObliqueTrialWithPilotReview() {
        for(capture in MappingCaptureProfile.approved60Trials) {
        val route=DjiMappingRoute(mission(capture))
        val files=mutableMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(route.kmz())).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}}
        val doc=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue("wpmz/waylines.wpml")))
        val ns="http://www.dji.com/wpmz/1.0.6"
        val pitches=doc.getElementsByTagNameNS(ns,"gimbalPitchRotateAngle")
        val values=(0 until pitches.length).map{pitches.item(it).textContent.toDouble()}
        assertEquals(setOf(-90.0,-60.0),values.toSet())
        assertEquals(route.legs.size,pitches.length)
        val triggers=doc.getElementsByTagNameNS(ns,"actionTriggerType")
        assertEquals(route.legs.size,(0 until triggers.length).count{triggers.item(it).textContent=="multipleDistance"})
        assertFalse((0 until triggers.length).any{triggers.item(it).textContent=="multipleTiming"})
        val actions=doc.getElementsByTagNameNS(ns,"actionActuatorFunc")
        assertEquals(route.legs.size,(0 until actions.length).count{actions.item(it).textContent=="takePhoto"})
        val distances=doc.getElementsByTagNameNS(ns,"actionTriggerParam")
        route.legs.forEachIndexed{index,leg->assertEquals(route.passSpacing(route.passes[leg.passIndex]),distances.item(index).textContent.toDouble(),1e-9)}
        assertTrue(route.summary().getBoolean("reviewRequired"))
        val rotations=doc.getElementsByTagNameNS(ns,"aircraftHeading")
        val gimbalYaw=doc.getElementsByTagNameNS(ns,"gimbalYawRotateAngle")
        assertEquals(route.legs.size,rotations.length)
        repeat(rotations.length){assertEquals(rotations.item(it).textContent.toDouble(),gimbalYaw.item(it).textContent.toDouble(),1e-9)}
        val headings=doc.getElementsByTagNameNS(ns,"waypointHeadingMode")
        repeat(headings.length){assertEquals("smoothTransition",headings.item(it).textContent)}
        assertTrue(route.photoEstimate<=200)
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(mission(MappingCaptureProfile.nadir45)).kmz()}
        val summary=route.summary().getJSONArray("capturePasses")
        for(index in 0 until summary.length()){
            assertEquals(capture.passes[index].forwardOverlapPercent,summary.getJSONObject(index).getDouble("forwardOverlapPercent"),0.0)
            assertEquals(capture.passes[index].sideOverlapPercent,summary.getJSONObject(index).getDouble("sideOverlapPercent"),0.0)
        }
        }
    }
    @Test fun approvedTrialStillRejectsExcessivePhotoLoad() {
        for(capture in MappingCaptureProfile.approved60Trials) {
        val record=mission(capture)
        record.getJSONObject("feature").getJSONObject("geometry").put("coordinates",org.json.JSONArray("""[[[-121,39],[-120.998,39],[-120.998,39.002],[-121,39.002],[-121,39]]]"""))
        val route=DjiMappingRoute(record)
        assertTrue(route.photoEstimate>200)
        assertThrows(IllegalArgumentException::class.java){route.kmz()}
        }
    }
}
