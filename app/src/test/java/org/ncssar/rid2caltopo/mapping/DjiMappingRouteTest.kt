package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DjiMappingRouteTest {
    @Test fun captureSpeedLimitPersistsAndDoesNotChangeTransitOrResolution() {
        val feature=DjiCloudTestFixture.mission(false).getJSONObject("feature")
        val profile=MappingResolution.profiles.first{it.id=="m4td.visible.wide"}
        for(limit in listOf(2.0,org.ncssar.rid2caltopo.data.DisplayUnits.IMPERIAL.lengthMeters(10.0),10.0)) {
            val plan=MappingMission(feature,"MAP").json(profile,1.27,MappingCaptureProfile.brushTrees,canopyHeightMeters=12.192,maximumObstacleHeightMeters=12.192,maximumCaptureSpeedMetersPerSecond=limit,transitSpeedMetersPerSecond=10.0)
            assertEquals(limit,plan.getJSONObject("parameters").getDouble("maximumCaptureSpeedMetersPerSecond"),1e-9)
            val route=DjiMappingRoute(org.json.JSONObject(plan.toString()))
            assertEquals(if(limit==2.0)2.0 else 3.0,route.speed,1e-9)
            assertTrue(route.speed<=limit);assertTrue(route.interval>=1.0)
            assertEquals(10.0,route.transitSpeed,1e-9)
            assertEquals(1.27,route.groundResolutionCmPerPixel,1e-9)
            ZipInputStream(route.kmz().inputStream()).use { zip->while(zip.nextEntry!=null){
                val xml=zip.readBytes().toString(Charsets.UTF_8)
                assertTrue(xml.contains("<wpml:autoFlightSpeed>${"%.9f".format(java.util.Locale.US,route.speed)}</wpml:autoFlightSpeed>"))
                assertTrue(xml.contains("<wpml:globalTransitionalSpeed>10.000000000</wpml:globalTransitionalSpeed>"))
            }}
        }
        for(limit in listOf(0.0,0.9,15.1,Double.NaN,Double.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){
            MappingMission(feature,"MAP").json(profile,2.0,transitSpeedMetersPerSecond=limit)
        }
        val independent=DjiMappingRoute(MappingMission(feature,"MAP").json(profile,2.0,transitSpeedMetersPerSecond=10.0))
        assertEquals(4.0,independent.speed,1e-9)
        assertEquals(10.0,independent.transitSpeed,1e-9)
        for(limit in listOf(0.0,0.9,15.1,Double.NaN,Double.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){
            MappingMission(feature,"MAP").json(profile,2.0,maximumCaptureSpeedMetersPerSecond=limit,transitSpeedMetersPerSecond=10.0)
        }
    }

    @Test fun halfInchWideBrushRouteRoundsCaptureSpeedDown() {
        val feature=DjiCloudTestFixture.mission(false).getJSONObject("feature")
        val profile=MappingResolution.profiles.first{it.id=="m4td.visible.wide"}
        val plan=MappingMission(feature,"MAP").json(profile,1.27,MappingCaptureProfile.brushTrees,canopyHeightMeters=12.192,maximumObstacleHeightMeters=12.192)
        val route=DjiMappingRoute(plan)
        assertEquals(3.0,route.speed,1e-9)
        assertEquals(4.0,route.transitSpeed,1e-9)
        assertTrue(route.interval>=1.0)
        assertEquals(1.27,route.groundResolutionCmPerPixel,1e-9)
    }

    @Test fun wideOriginalMayHaveZoomCameraLabelOnlyWithWideFocalLength() {
        val wide=MappingResolution.profiles.first{it.id=="m4td.visible.wide"}
        assertTrue(wide.matchesOriginal("visible-zoom",24.0))
        for(focal in listOf(null,Double.NaN,0.0,70.0,168.0))assertFalse(wide.matchesOriginal("visible-zoom",focal))
        assertFalse(wide.matchesOriginal("thermal",24.0))
        assertFalse(wide.matchesOriginal("unknown",24.0))
    }
    @Test fun telephotoRoutesPreserveRequestedDetailAndSelectLensBeforeCapture() {
        val feature=DjiCloudTestFixture.mission(false).getJSONObject("feature")
        for((id,focal) in listOf("m4td.visible.medium-tele" to 70.0,"m4td.visible.tele" to 168.0)) {
            val profile=MappingResolution.profiles.first{it.id==id}
            val mission=MappingMission(feature,"MAP").json(profile,0.254,MappingCaptureProfile.nadir,maximumObstacleHeightMeters=0.0)
            val route=DjiMappingRoute(mission)
            assertEquals(1.0,route.speed,1e-9)
            assertEquals(4.0,route.transitSpeed,1e-9)
            assertTrue(route.interval>=1.5)
            assertEquals(0.254,route.groundResolutionCmPerPixel,1e-10)
            assertEquals(if(focal==70.0)20.300744010966454 else 49.39063154258403,route.heightMeters,1e-9)
            val files=mutableMapOf<String,ByteArray>()
            ZipInputStream(route.kmz().inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}}
            for(xmlBytes in files.values) {
                val xml=xmlBytes.toString(Charsets.UTF_8)
                assertTrue(xml.contains("<wpml:focalLength>"+"%.9f".format(java.util.Locale.US,focal)+"</wpml:focalLength>"))
                assertTrue(xml.indexOf(">zoom<")<xml.indexOf(">takePhoto<"))
                assertTrue(xml.contains("<wpml:globalTransitionalSpeed>4.000000000</wpml:globalTransitionalSpeed>"))
                assertTrue(xml.contains("<wpml:autoFlightSpeed>1.000000000</wpml:autoFlightSpeed>"))
                assertTrue(xml.contains("<wpml:imageFormat>visable</wpml:imageFormat>"))
                assertFalse(xml.contains("<wpml:payloadLensIndex>ir"))
            }
            assertTrue(profile.matchesOriginal("visible-zoom",focal))
            assertFalse(profile.matchesOriginal("visible-zoom",if(focal==70.0)168.0 else 70.0))
            assertFalse(profile.matchesOriginal("thermal",focal))
            assertFalse(profile.matchesOriginal("visible-zoom",null))
            assertThrows(IllegalArgumentException::class.java){MappingMission(feature,"MAP").json(profile,0.254,MappingCaptureProfile.nadir,maximumObstacleHeightMeters=route.heightMeters)}
        }
        val wide=MappingResolution.profiles.first{it.id=="m4td.visible.wide"}
        assertFalse(wide.matchesOriginal("visible-wide",70.0))
        assertThrows(IllegalArgumentException::class.java){MappingMission(feature,"MAP").json(wide,0.254,MappingCaptureProfile.nadir,maximumObstacleHeightMeters=0.0)}
    }
    @Test fun migrationChangesOnlyCameraFieldsAndIsIdempotent() {
        val route=DjiMappingRoute(DjiCloudTestFixture.mission(false))
        fun entries(bytes:ByteArray):Map<String,String> {
            val result=linkedMapOf<String,String>()
            ZipInputStream(bytes.inputStream()).use { zip -> while(true) { val entry=zip.nextEntry?:break;result[entry.name]=zip.readBytes().toString(Charsets.UTF_8) } }
            return result
        }
        val current=entries(route.kmz())
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip -> current.forEach { (name,xml) ->
            zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(xml.replace(">visable<",">wide<").toByteArray());zip.closeEntry()
        } }
        val corrected=route.correctingCameraSelection(bytes.toByteArray())
        assertEquals(current,entries(corrected))
        assertArrayEquals(corrected,route.correctingCameraSelection(corrected))
        val thermal=DjiMappingRoute(DjiCloudTestFixture.mission(true));val infrared=thermal.kmz()
        assertArrayEquals(infrared,thermal.correctingCameraSelection(infrared))
    }
    @Test fun executableGridKmzContainsNadirActionsTimingSensorAndMissionPhotoTag() {
        for(thermal in listOf(false,true)) {
            val plan=DjiMappingRoute(DjiCloudTestFixture.mission(thermal));val files=mutableMapOf<String,ByteArray>()
            ZipInputStream(ByteArrayInputStream(plan.kmz())).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}}
            assertEquals(setOf("wpmz/template.kml","wpmz/waylines.wpml"),files.keys)
            val document=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue("wpmz/waylines.wpml")))
            val namespace="http://www.dji.com/wpmz/1.0.6"
            assertEquals("100",document.getElementsByTagNameNS(namespace,"droneEnumValue").item(0).textContent)
            assertEquals("relativeToStartPoint",document.getElementsByTagNameNS(namespace,"executeHeightMode").item(0).textContent)
            assertEquals(plan.legs.size*2,document.getElementsByTagName("Placemark").length)
            val heights=document.getElementsByTagNameNS(namespace,"executeHeight")
            assertEquals(plan.legs.size*2,heights.length)
            repeat(heights.length){assertEquals(plan.heightMeters,heights.item(it).textContent.toDouble(),0.000000001)}
            val template=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue("wpmz/template.kml")))
            assertEquals("relativeToStartPoint",template.getElementsByTagNameNS(namespace,"heightMode").item(0).textContent)
            assertEquals(plan.heightMeters,template.getElementsByTagNameNS(namespace,"globalHeight").item(0).textContent.toDouble(),0.000000001)
            val summary=plan.summary()
            assertEquals("takeoff",summary.getString("heightReference"))
            assertEquals(plan.heightMeters,summary.getDouble("heightMeters"),0.0)
            assertEquals(summary.getDouble("returnHomeHeightMeters"),document.getElementsByTagNameNS(namespace,"globalRTHHeight").item(0).textContent.toDouble(),0.000000001)
            val pitches=document.getElementsByTagNameNS(namespace,"gimbalPitchRotateAngle");assertEquals(plan.legs.size,pitches.length)
            repeat(pitches.length){assertEquals(-90.0,pitches.item(it).textContent.toDouble(),0.0)}
            val suffixes=document.getElementsByTagNameNS(namespace,"fileSuffix");repeat(suffixes.length){assertEquals(plan.photoTag,suffixes.item(it).textContent)}
            val lenses=document.getElementsByTagNameNS(namespace,"payloadLensIndex");repeat(lenses.length){assertEquals(if(thermal)"ir" else "visable",lenses.item(it).textContent)}
            assertTrue(plan.interval>=1);assertTrue(plan.photoEstimate>0);assertTrue(plan.durationEstimateSeconds>0)
            val actions=document.getElementsByTagNameNS(namespace,"actionActuatorFunc")
            assertFalse((0 until actions.length).any{actions.item(it).textContent=="startRecord"})
        }
    }
    @Test fun geometryAndUnsupportedCameraAreRejectedBeforeRoutesAreIssued() {
        val value=DjiCloudTestFixture.mission()
        value.put("cameraProfileId","unsupported-camera")
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(value)}
        val crossed=DjiCloudTestFixture.mission();crossed.getJSONObject("feature").getJSONObject("geometry").put("coordinates",org.json.JSONArray("""[[[-121,39],[-120.998,39.002],[-120.998,39],[-121,39.002],[-121,39]]]"""))
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(crossed)}
    }
    @Test fun increasingGroundPixelSizeReducesFlightAndPhotoLoadForSameAssignment() {
        val fine=DjiCloudTestFixture.mission(true)
        val coarse=org.json.JSONObject(fine.toString());coarse.getJSONObject("parameters").put("desiredGroundResolutionCmPerPixel",24.0).put("heightMeters",240.0)
        val a=DjiMappingRoute(fine);val b=DjiMappingRoute(coarse)
        assertTrue(b.legs.size<a.legs.size);assertTrue(b.photoEstimate<a.photoEstimate);assertTrue(b.durationEstimateSeconds<a.durationEstimateSeconds)
    }
}
