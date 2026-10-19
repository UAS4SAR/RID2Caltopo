package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Check generated DJI files against documented model identities, not just chooser labels. */
class MappingAircraftTest {
    private val feature=JSONObject("""{"id":"A1","properties":{"class":"Assignment","title":"Aircraft check"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.999,39],[-120.999,39.001],[-121,39.001],[-121,39]]]}}""")
    private fun xml(route:DjiMappingRoute):List<String> = ZipInputStream(ByteArrayInputStream(route.kmz())).use { zip ->
        buildList { while(zip.nextEntry!=null)add(zip.readBytes().toString(Charsets.UTF_8)) }
    }
    @Test fun newAircraftProduceTheirOwnRouteAndPayloadIdentity() {
        val mission=MappingMission(feature,"MAP")
        for((id,drone,sub,payload) in listOf(
            listOf("m4t.visible.wide",99,1,89),listOf("m30.visible.wide",67,0,52),listOf("m30t.visible.wide",67,1,53))) {
            val camera=MappingResolution.profiles.first{it.id==id}
            val plan=mission.json(camera,3.0,MappingCaptureProfile.brushTrees,20.0,20.0,maximumCaptureSpeedMetersPerSecond=10.0,transitSpeedMetersPerSecond=8.0)
            val route=DjiMappingRoute(plan)
            assertEquals(setOf(0,1),route.legs.map{it.passIndex}.toSet())
            assertTrue(route.speed<=10);assertEquals(8.0,route.transitSpeed,0.0)
            assertTrue(route.interval >= if(drone==67)2.0 else 1.0)
            assertEquals("0-$drone-$sub",route.aircraft.droneModelKey)
            assertEquals("1-$payload-0",route.aircraft.payloadModelKey)
            for(text in xml(route)) {
                assertTrue(text.contains("<wpml:droneEnumValue>$drone</wpml:droneEnumValue>"))
                assertTrue(text.contains("<wpml:droneSubEnumValue>$sub</wpml:droneSubEnumValue>"))
                assertTrue(text.contains("<wpml:payloadEnumValue>$payload</wpml:payloadEnumValue>"))
                val lens=if(drone==67)"wide" else "visable"
                assertTrue(text.contains("<wpml:imageFormat>$lens</wpml:imageFormat>"))
                assertTrue(text.contains("<wpml:payloadLensIndex>$lens</wpml:payloadLensIndex>"))
                assertEquals(drone!=67,text.contains("<wpml:actionActuatorFunc>zoom</wpml:actionActuatorFunc>"))
            }
            plan.put("aircraftModel","DJI Matrice 4TD")
            assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(plan)}
        }
    }
    @Test fun m30GeometryAndM4tTelephotoHaveDifferentCoverageAndLensSelection() {
        val mission=MappingMission(feature,"MAP")
        val m30=MappingResolution.profiles.first{it.id=="m30.visible.wide"}
        val m4t=MappingResolution.profiles.first{it.id=="m4t.visible.wide"}
        assertEquals(4000,m30.sensor.widthPixels);assertEquals(3000,m30.sensor.heightPixels)
        assertEquals(84.0,m30.sensor.diagonalFovDegrees,0.0)
        assertTrue(MappingResolution.heightMetersForGsd(2.0,m30.sensor)!! < MappingResolution.heightMetersForGsd(2.0,m4t.sensor)!!)
        for((id,focal) in listOf("m4t.visible.medium-tele" to 70.0,"m4t.visible.tele" to 168.0)) {
            val profile=MappingResolution.profiles.first{it.id==id}
            val route=DjiMappingRoute(mission.json(profile,1.0,MappingCaptureProfile.nadir))
            assertTrue(route.interval>=1.5)
            for(text in xml(route))assertTrue(text.contains("<wpml:focalLength>${focal.toInt()}.000000000</wpml:focalLength>"))
            assertTrue(profile.matchesOriginal("visible-zoom",focal))
            assertFalse(profile.matchesOriginal("visible-wide",24.0))
        }
        assertEquals(1,MappingResolution.profilesFor("DJI Matrice 30").size)
        assertEquals(2,MappingResolution.profilesFor("DJI Matrice 30T").size)
        assertEquals(4,MappingResolution.profilesFor("DJI Matrice 4T").size)
        val plan=mission.json(m30,3.0)
        plan.getJSONObject("cameraSpecifications").put("diagonalFOVDegrees",82)
        assertThrows(IllegalArgumentException::class.java){DjiMappingRoute(plan)}
        val thermal=MappingResolution.profiles.first{it.id=="m30t.thermal"}
        assertEquals(61.0,thermal.sensor.diagonalFovDegrees,0.0)
        for(text in xml(DjiMappingRoute(mission.json(thermal,15.0))))assertTrue(text.contains("<wpml:payloadLensIndex>ir</wpml:payloadLensIndex>"))
    }
}
