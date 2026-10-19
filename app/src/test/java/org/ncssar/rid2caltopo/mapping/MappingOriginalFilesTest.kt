package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class MappingOriginalFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val mission = UUID.randomUUID().toString()
    private val digest = "a".repeat(64)
    private fun photo(name: String = "DJI_20261018_001.jpg"): File {
        val root = MappingOriginalFiles.root(temp.root, mission)
        File(root,"inventory").mkdirs(); File(root,"originals").mkdirs()
        val file = File(root,"originals/$digest.jpg").apply { writeBytes(byteArrayOf(-1,-40,1,2,3)) }
        File(root,"inventory/$digest.json").writeText(JSONObject().put("name",name).put("sha256",digest).put("complete",true).put("sizeBytes",file.length()).toString())
        return file
    }
    @Test fun browsingAndSharingPreserveOriginalNameAndBytes() {
        val file = photo()
        val original = MappingOriginalFiles.list(temp.root,mission).single()
        assertEquals("DJI_20261018_001.jpg",original.name)
        assertArrayEquals(file.readBytes(),original.file.readBytes())
        assertEquals(5L,original.sizeBytes)
    }
    @Test fun incompleteFilesAreNotExposedAndUnsafeDisplayNamesNeverBecomePaths() {
        val file = photo("../outside.jpg")
        assertEquals("$digest.jpg",MappingOriginalFiles.photo(temp.root,mission,digest).name)
        assertThrows(IllegalArgumentException::class.java) { MappingOriginalFiles.root(temp.root,"../outside") }
        assertThrows(IllegalArgumentException::class.java) { MappingOriginalFiles.photo(temp.root,mission,"../outside") }
        file.writeBytes(byteArrayOf(1))
        assertTrue(MappingOriginalFiles.list(temp.root,mission).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { MappingOriginalFiles.photo(temp.root,mission,digest) }
    }
    @Test fun inspectorFindsOlderCameraMetadataAndAllRegionalGlbs() {
        photo()
        val missionRoot = MappingOriginalFiles.root(temp.root,mission)
        File(missionRoot,"mission.json").writeText(JSONObject().put("cameraProfileId","m4td.visible.tele").toString())
        val id = UUID.randomUUID().toString()
        val root = File(temp.root,"mapping-layers/$id").apply { mkdirs() }
        File(root,"layer.json").writeText(JSONObject().put("missionId",mission).toString())
        File(root,"surface/regions/0/surface.glb").apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(1)) }
        File(root,"surface/regions/1/surface.glb").apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(2)) }
        File(root,"surface/collection.json").writeText("""{"regions":[{"path":"regions/0/surface.glb"},{"path":"regions/1/surface.glb"},{"path":"../../outside.glb"}]}""")
        val layer = MappingLayer(id,"incident","Result","http://localhost/{Z}/{X}/{Y}.png",14,20)
        val details = MappingInspectorFiles.inspect(temp.root,root,layer)
        assertEquals("Tele (7×), 12 MP",details.cameraLabel)
        assertEquals(mission,details.missionId); assertTrue(details.hasOriginals)
        assertEquals(listOf("regions/0/surface.glb","regions/1/surface.glb"),details.surfaces)
    }
    @Test fun legacyMissionWithoutRouteSummaryRecoversPlanEstimates() {
        val feature=JSONObject("""{"id":"site","properties":{"class":"Assignment","title":"Site"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.9995,39],[-120.9995,39.0005],[-121,39.0005],[-121,39]]]}}""")
        val record=MappingMission(feature,"incident").json(MappingResolution.profiles.first(),2.0,MappingCaptureProfile.nadir)
        val root=File(temp.root,"mapping-missions/${record.getString("missionId")}").apply { mkdirs() }
        File(root,"mission.json").writeText(record.toString())
        val route=DjiMappingRoute(record)
        val status=MappingFlightRepository.snapshot(root,record)
        assertEquals(route.photoEstimate,status.plannedPhotos)
        assertEquals(route.durationEstimateSeconds,status.plannedSeconds)
        assertEquals(route.heightMeters,status.plannedHeightMeters!!,0.0)
        assertEquals("Wide (1×), 12 MP",status.cameraLabel)
        assertTrue(File(root,"route-summary.json").isFile)
    }
}
