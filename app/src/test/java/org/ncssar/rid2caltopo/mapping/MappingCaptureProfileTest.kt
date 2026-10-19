package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MappingCaptureProfileTest {
    private fun mission(profile:MappingCaptureProfile.Profile):JSONObject {
        val feature=JSONObject("""{"id":"small-site","properties":{"class":"Assignment","title":"Controlled site"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}""")
        return MappingMission(feature,"local-test",mapName="Local map").json(MappingResolution.profiles.first(),2.0,profile)
    }
    @Test fun resolutionAndCrossingProfilesKeepIdentityAndOnly60TrialCanExport() {
        val baseline=DjiMappingRoute(mission(MappingCaptureProfile.nadir))
        println("nadir: ${baseline.summary()}")
        assertEquals(4032,baseline.summary().getInt("captureWidthPixels"))
        assertEquals(3024,baseline.summary().getInt("captureHeightPixels"))
        assertTrue(baseline.heightMeters in 57.0..59.0)
        for(profile in listOf(MappingCaptureProfile.nadir60Standard,MappingCaptureProfile.nadir60,MappingCaptureProfile.nadir45)) {
            val route=DjiMappingRoute(mission(profile))
            println("${profile.id}: ${route.summary()}")
            assertEquals("small-site",route.mission.getString("assignmentId"));assertEquals("local-test",route.mission.getString("incidentId"))
            assertEquals(listOf(-90.0,profile.passes.last().pitchDegrees),route.passes.map{it.pitchDegrees})
            assertEquals(listOf(0.0,90.0),route.passes.map{it.directionDegrees})
            assertTrue(route.photoEstimate>baseline.photoEstimate);assertTrue(route.photoEstimate<=200)
            assertTrue(route.durationEstimateSeconds>baseline.durationEstimateSeconds)
            assertTrue(route.interval>=1);assertEquals(2,route.summary().getJSONArray("capturePasses").length())
            if(profile in MappingCaptureProfile.approved60Trials)assertTrue(route.kmz().isNotEmpty()) else assertThrows(IllegalArgumentException::class.java){route.kmz()}
        }
        assertTrue(baseline.kmz().isNotEmpty())
    }
    @Test fun tamperedProfileCannotBypassPreviewGate() {
        val record=mission(MappingCaptureProfile.nadir60)
        record.getJSONObject("captureProfile").put("id","nadir")
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(record)}
    }
    @Test fun largerBaselineCanExportForRegionalProcessing() {
        val record=DjiCloudTestFixture.mission()
        record.getJSONObject("parameters").put("desiredGroundResolutionCmPerPixel",.9).put("heightMeters",26.0)
        val route=DjiMappingRoute(record);assertTrue(route.photoEstimate>200)
        assertTrue(route.kmz().isNotEmpty())
    }
    @Test fun alternatingObliqueLegsAimAtAssignmentAndKeepNadirPassIdentical() {
        val a=DjiMappingRoute(mission(MappingCaptureProfile.nadir60));val b=DjiMappingRoute(mission(MappingCaptureProfile.nadir45))
        assertEquals(a.legs.filter{it.passIndex==0},b.legs.filter{it.passIndex==0})
        assertEquals(a.passSpacing(a.passes[0]),b.passSpacing(b.passes[0]),0.0)
        for(route in listOf(a,b))for(leg in route.legs) {
            val dx=leg.end.x-leg.start.x;val dy=leg.end.y-leg.start.y
            val heading=Math.toDegrees(kotlin.math.atan2(dx,dy))
            val r=MappingCameraGeometry.rotation(heading,route.passes[leg.passIndex].pitchDegrees,0.0)
            val t= -route.heightMeters/r[8]
            val target=DjiMappingRoute.Point((leg.start.x+leg.end.x)/2+r[6]*t,(leg.start.y+leg.end.y)/2+r[7]*t)
            val (lon,lat)=route.coordinate(target)
            assertTrue(lon in -121.0..-120.9995);assertTrue(lat in 39.0..39.0005)
        }
    }

    @Test fun trialKmzEncodesBothPassesAbsoluteYawDistanceAndPilotReview() {
        for(capture in MappingCaptureProfile.approved60Trials) {
        val route=DjiMappingRoute(mission(capture))
        val files=mutableMapOf<String,ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(route.kmz())).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}}
        assertEquals(setOf("wpmz/template.kml","wpmz/waylines.wpml"),files.keys)
        val ns="http://www.dji.com/wpmz/1.0.6"
        fun xml(name:String)=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(java.io.ByteArrayInputStream(files.getValue(name)))
        val doc=xml("wpmz/waylines.wpml")
        val pitches=doc.getElementsByTagNameNS(ns,"gimbalPitchRotateAngle")
        val yawEnabled=doc.getElementsByTagNameNS(ns,"gimbalYawRotateEnable");val yaws=doc.getElementsByTagNameNS(ns,"gimbalYawRotateAngle")
        val intervals=doc.getElementsByTagNameNS(ns,"actionTriggerParam")
        assertEquals(route.legs.size,pitches.length)
        val exportedHeadings=mutableSetOf<Double>()
        route.legs.forEachIndexed{index,leg->
            val pass=route.passes[leg.passIndex]
            assertEquals(pass.pitchDegrees,pitches.item(index).textContent.toDouble(),1e-9)
            assertEquals(route.passSpacing(pass),intervals.item(index).textContent.toDouble(),1e-9)
            assertEquals("1",yawEnabled.item(index).textContent)
            if(leg.passIndex>0){val yaw=Math.toDegrees(kotlin.math.atan2(leg.end.x-leg.start.x,leg.end.y-leg.start.y));assertEquals(yaw,yaws.item(index).textContent.toDouble(),1e-9);exportedHeadings.add(yaws.item(index).textContent.toDouble())}
        }
        assertEquals(setOf(-90.0,90.0),exportedHeadings)
        val template=xml("wpmz/template.kml")
        val templatePitch=template.getElementsByTagNameNS(ns,"gimbalPitchAngle")
        assertEquals(route.legs.size*2,templatePitch.length)
        repeat(templatePitch.length){index->assertEquals(route.passes[route.legs[index/2].passIndex].pitchDegrees,templatePitch.item(index).textContent.toDouble(),1e-9)}
        assertEquals("relativeToStartPoint",doc.getElementsByTagNameNS(ns,"executeHeightMode").item(0).textContent)
        assertTrue(route.summary().getBoolean("reviewRequired"))
        val actions=doc.getElementsByTagNameNS(ns,"actionActuatorFunc")
        assertTrue((0 until actions.length).all{actions.item(it).textContent in setOf("rotateYaw","gimbalRotate","zoom","customDirName","takePhoto")})
        val focals=doc.getElementsByTagNameNS(ns,"focalLength")
        assertEquals(route.legs.size,focals.length)
        repeat(focals.length){assertEquals(24.0,focals.item(it).textContent.toDouble(),0.0)}
        }
    }
    @Test fun larger60TrialCanExportForRegionalProcessing() {
        for(capture in MappingCaptureProfile.approved60Trials) {
        val record=DjiCloudTestFixture.mission()
        record.put("captureProfile",capture.json())
        val route=DjiMappingRoute(record)
        assertTrue(route.photoEstimate>200)
        assertTrue(route.kmz().isNotEmpty())
        }
        assertFalse(MappingCaptureProfile.nadir45.exportQualified)
    }

    @Test fun standardOverlapChangesEstimatesAndTimingWhileKeepingGeometryAndIdentity() {
        val mission=mission(MappingCaptureProfile.nadir60).let{MappingMission(it.getJSONObject("feature"),it.getString("incidentId"))}
        val camera=MappingResolution.profiles.first()
        val dense=DjiMappingRoute(mission.json(camera,2.0,MappingCaptureProfile.nadir60))
        val standard=DjiMappingRoute(mission.json(camera,2.0,MappingCaptureProfile.nadir60Standard))
        val baseline=DjiMappingRoute(mission.json(camera,2.0,MappingCaptureProfile.nadir))
        assertEquals(baseline.legs,standard.legs.filter{it.passIndex==0})
        assertEquals(baseline.passSpacing(baseline.passes[0]),standard.passSpacing(standard.passes[0]),0.0)
        assertEquals(dense.id,standard.id)
        assertEquals(dense.heightMeters,standard.heightMeters,0.0)
        assertEquals(dense.returnHomeHeightMeters,standard.returnHomeHeightMeters,0.0)
        assertEquals(dense.passes.map{it.pitchDegrees to it.directionDegrees},standard.passes.map{it.pitchDegrees to it.directionDegrees})
        assertTrue(standard.photoEstimate<dense.photoEstimate)
        assertTrue(standard.durationEstimateSeconds<dense.durationEstimateSeconds)
        standard.passes.forEach {pass->assertEquals(80.0,pass.forwardOverlapPercent,0.0);assertEquals(70.0,pass.sideOverlapPercent,0.0)}
        standard.passes.indices.forEach{index->assertTrue(standard.passSpacing(standard.passes[index])>dense.passSpacing(dense.passes[index]))}
        assertEquals("nadir-plus-60-80-70",standard.mission.getJSONObject("captureProfile").getString("id"))
        assertEquals(85.0,MappingCaptureProfile.nadir60.passes.first().forwardOverlapPercent,0.0)
        assertEquals(80.0,MappingCaptureProfile.nadir60.passes.first().sideOverlapPercent,0.0)
        // Changing the saved 85/80 profile ID to the new 80/70 ID must not relabel its actual parameters.
        val tampered=org.json.JSONObject(dense.mission.toString())
        tampered.getJSONObject("captureProfile").put("id",MappingCaptureProfile.nadir60Standard.id)
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(tampered)}
    }

    @Test fun distanceCaptureHasOnePhotoActionPerLegAndLegacyRoutesKeepTheirPolicy() {
        val record=mission(MappingCaptureProfile.nadir60Standard)
        val fresh=DjiMappingRoute(record)
        fun content(route:DjiMappingRoute):String {
            val files=mutableMapOf<String,String>()
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(route.kmz())).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes().toString(Charsets.UTF_8)}}
            return files.getValue("wpmz/waylines.wpml")
        }
        val xml=content(fresh)
        assertTrue(fresh.distanceCapture)
        assertEquals(fresh.legs.size,Regex("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>").findAll(xml).count())
        assertEquals(fresh.legs.size,Regex("<wpml:actionTriggerType>multipleDistance</wpml:actionTriggerType>").findAll(xml).count())
        assertFalse(xml.contains("multipleTiming"))
        record.getJSONObject("parameters").remove("captureTrigger")
        val legacy=DjiMappingRoute(record);val old=content(legacy)
        assertFalse(legacy.distanceCapture)
        assertEquals(legacy.legs.size*2,Regex("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>").findAll(old).count())
        assertFalse(old.contains("multipleDistance"));assertTrue(old.contains("multipleTiming"))
        assertEquals(fresh.legs,legacy.legs)
        assertEquals(fresh.photoEstimate,legacy.photoEstimate)
    }

}
