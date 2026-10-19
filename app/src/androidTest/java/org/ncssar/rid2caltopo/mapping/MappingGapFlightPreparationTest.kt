package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Opt-in local plan preparation. Does not start a Cloud connection or transfer/start a flight. */
@RunWith(AndroidJUnit4::class)
class MappingGapFlightPreparationTest {
    private fun sha(file:File)=MessageDigest.getInstance("SHA-256").let{digest->file.inputStream().buffered().use{input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}};LocalS3.hex(digest.digest())}
    @Test fun validateAndSavePreparedGapFlight() {
        val args=InstrumentationRegistry.getArguments();val input=args.getString("gapManifestPath");assumeTrue(input!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(MappingProcessingManager.state.value?.active!=true)
        val manifest=JSONObject(File(input!!).readText());val id=manifest.getString("missionId")
        require(UUID.fromString(id).toString()==id)
        val gap=manifest.getJSONObject("gapCapturePlan")
        val baseline=LocalMappingTiles.directory(context,gap.getString("sourceLayerId"))
        val retainedSurface=if(gap.has("sourceExperimentId")) {
            val experiment=gap.getString("sourceExperimentId");require(UUID.fromString(experiment).toString()==experiment)
            File(context.filesDir,"mapping-experiments/$experiment/surface/surface.f32")
        } else File(baseline,"surface/surface.f32")
        assertEquals(gap.getString("sourceSurfaceSha256"),sha(retainedSurface))
        val sourceLayer=JSONObject(File(baseline,"layer.json").readText())
        val source=File(context.filesDir,"mapping-missions/${sourceLayer.getString("missionId")}")
        val previous=JSONObject(File(source,"mission.json").readText())
        assertEquals(previous.getJSONObject("feature").getJSONObject("geometry").toString(),manifest.getJSONObject("feature").getJSONObject("geometry").toString())
        assertEquals(previous.getString("incidentId"),manifest.getString("incidentId"));assertEquals(previous.getString("assignmentId"),manifest.getString("assignmentId"))
        assertFalse("A stale launch location must not appear current",manifest.has("launchLocation"))
        val protected=source.walkTopDown().filter{it.isFile}.toList()+baseline.walkTopDown().filter{it.isFile}.toList()
        val before=protected.associate{it to sha(it)}
        val route=DjiMappingRoute(manifest);assertEquals(args.getString("expectedPhotos")?.toInt()?:181,route.photoEstimate);assertEquals(args.getString("expectedLegs")?.toInt()?:18,route.legs.size)
        assertTrue(route.distanceCapture&&route.aircraftFirstHeading);assertTrue(route.interval>=1)
        val kmz=route.kmz();val files=mutableMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(kmz)).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}}
        assertTrue(route.photoEstimate<=190)
        if(gap.getInt("version")==2) {
            assertEquals(2,route.passes.size)
            assertEquals(90.0,route.passes.first().directionDegrees,0.0)
            val reference=gap.getDouble("planningSurfaceHeightMeters")
            val expectedSpacing=manifest.getJSONObject("cameraSpecifications").getInt("heightPixels")*route.groundResolutionCmPerPixel/100*.15*(route.heightMeters-reference)/route.heightMeters
            assertEquals(expectedSpacing,route.passSpacing(route.passes.first()),1e-9)
        }
        val ns="http://www.dji.com/wpmz/1.0.6"
        fun text(e:Element,name:String)=e.getElementsByTagNameNS(ns,name).item(0).textContent
        for((_,bytes) in files){val doc=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            val points=doc.getElementsByTagName("Placemark");assertEquals(route.legs.size*2,points.length)
            repeat(points.length){assertEquals("smoothTransition",text(points.item(it) as Element,"waypointHeadingMode"))}
            route.legs.forEachIndexed{i,leg->val start=points.item(i*2) as Element
                val group=start.getElementsByTagNameNS(ns,"actionGroup").item(0) as Element;assertEquals("sequence",text(group,"actionGroupMode"))
                val actions=group.getElementsByTagNameNS(ns,"action")
                assertEquals("rotateYaw",text(actions.item(0) as Element,"actionActuatorFunc"));assertEquals("gimbalRotate",text(actions.item(1) as Element,"actionActuatorFunc"))
                val heading=text(actions.item(0) as Element,"aircraftHeading").toDouble()
                assertEquals(heading,text(actions.item(1) as Element,"gimbalYawRotateAngle").toDouble(),1e-9)
                assertEquals(heading,text(start,"waypointHeadingAngle").toDouble(),1e-9);assertEquals(heading,text(points.item(i*2+1) as Element,"waypointHeadingAngle").toDouble(),1e-9)
                assertEquals(route.passes[leg.passIndex].pitchDegrees,text(actions.item(1) as Element,"gimbalPitchRotateAngle").toDouble(),1e-9)
                assertEquals(if(i==0)3 else 2,actions.length)
            }
            val lenses=doc.getElementsByTagNameNS(ns,"payloadLensIndex")
            repeat(lenses.length){assertEquals("wide",lenses.item(it).textContent)}
            val functions=doc.getElementsByTagNameNS(ns,"actionActuatorFunc");assertEquals(route.legs.size,(0 until functions.length).count{functions.item(it).textContent=="takePhoto"})
            val triggers=doc.getElementsByTagNameNS(ns,"actionTriggerType");assertEquals(route.legs.size,(0 until triggers.length).count{triggers.item(it).textContent=="multipleDistance"})
            assertFalse((0 until triggers.length).any{triggers.item(it).textContent=="multipleTiming"})
        }
        val root=File(context.filesDir,"mapping-missions/$id");require(!root.exists()){"Prepared mission already exists; do not replace it."};root.mkdirs()
        MappingPhotoStore.writeJson(File(root,"mission.json"),manifest);File(root,"mission.kmz").writeBytes(kmz)
        File(root,"boundary.kml").writeText(MappingMission(manifest.getJSONObject("feature"),manifest.getString("incidentId")).boundaryKml())
        MappingPhotoStore.writeJson(File(root,"route-summary.json"),route.summary())
        MappingPhotoStore.writeJson(File(root,"processing-status.json"),JSONObject().put("active",false).put("stage","Gap-focused route prepared · awaiting Pilot 2 review and flight"))
        before.forEach{(file,digest)->assertEquals(file.path,digest,sha(file))}
        val flight=MappingFlightRepository.list(context.filesDir,manifest.getString("assignmentId"),manifest.getString("incidentId")).single{it.id==id}
        assertTrue(flight.hasRoute);assertEquals(0,flight.photoCount);assertFalse(flight.active);assertEquals(manifest.getString("title"),flight.title)
        android.util.Log.i("MappingGapFlight","PASS prepared $id: ${route.summary()}; parent photos and layer unchanged; no connection or flight start")
    }
}
