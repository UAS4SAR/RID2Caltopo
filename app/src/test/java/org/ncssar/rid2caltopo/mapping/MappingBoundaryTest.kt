package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class MappingBoundaryTest {
    @get:Rule val temporary=TemporaryFolder()
    private val coordinates=listOf(listOf(-121.0,39.0),listOf(-120.999,39.0),listOf(-120.999,39.001),listOf(-121.0,39.001),listOf(-121.0,39.0))
    private fun layer()=MappingLayer(UUID.randomUUID().toString(),"test","Test","http://localhost/{Z}/{X}/{Y}.png",18,21)
    private fun write(file:File,json:JSONObject){file.parentFile.mkdirs();file.writeText(json.toString())}
    private fun mission(id:String=UUID.randomUUID().toString()):File {
        val root=File(temporary.root,"mapping-missions/$id")
        write(File(root,"mission.json"),JSONObject().put("missionId",id).put("feature",JSONObject().put("geometry",JSONObject().put("type","Polygon").put("coordinates",JSONArray(listOf(coordinates))))))
        return root
    }
    @Test fun interiorIsUnchangedAndOnlyTheOutsideFades() {
        val b=MappingBoundary(coordinates);val east=b.polygon.maxOf{it.first};val middle=b.polygon.map{it.second}.average()
        assertEquals(1.0,b.alpha(0.0,0.0),0.0)
        assertEquals(1.0,b.alpha(east,middle),0.0)
        assertEquals(.5,b.alpha(east+2.5,middle),1e-12)
        assertEquals(0.0,b.alpha(east+5,middle),0.0)
        assertEquals(0.0,b.alpha(east+50,middle),0.0)
        assertTrue(b.alpha(east+.5,middle)>b.alpha(east+4.5,middle))
    }
    @Test fun maskIsContinuousAcrossTileEdgesAndUsesPixelCenters() {
        val b=MappingBoundary(coordinates);val east=b.polygon.maxOf{it.first}
        val whole=b.mask(10,1,east-2.5,0.0,.5,0.0)
        assertArrayEquals(whole,b.mask(5,1,east-2.5,0.0,.5,0.0)+b.mask(5,1,east,0.0,.5,0.0))
        assertEquals(255,whole[0].toInt()and 255);assertTrue((whole[9].toInt()and 255) in 1..254)
    }
    @Test fun concaveRegionDoesNotBecomeItsBoundingBox() {
        val b=MappingBoundary(listOf(listOf(0.0,0.0),listOf(.001,0.0),listOf(.001,.0002),listOf(.0002,.0002),listOf(.0002,.001),listOf(0.0,.001)))
        fun alpha(lon:Double,lat:Double)=b.alpha((MappingGeometry.east(lon)-b.originX)*b.scale,(MappingGeometry.north(lat)-b.originY)*b.scale)
        assertEquals(1.0,alpha(.0001,.0007),0.0);assertEquals(0.0,alpha(.0007,.0007),0.0)
    }
    @Test fun metadataRoundTripsAndRejectsMalformedBoundary() {
        val result=layer().copy(assignmentBoundary=coordinates)
        assertEquals(coordinates,MappingLayer.parse(result.json()).assignmentBoundary)
        for(text in listOf("[[0,0],[1,0]]","[[0,0],[1,0],[2,0]]","[[0,0],[1,0],[1,90]]","[[0,0,3],[1,0],[1,1]]"))
            assertThrows(IllegalArgumentException::class.java){MappingBoundary.parse(JSONArray(text))}
    }
    @Test fun legacyResultUsesItsSnapshotWithoutChangingArtifacts() {
        val layer=layer();val root=mission();val file=File(temporary.root,"mapping-layers/${layer.id}/layer.json")
        write(file,layer.json().put("missionId",root.name));val original=file.readText()
        assertEquals(coordinates,MappingBoundary.resolve(temporary.root,layer)!!.coordinates)
        assertEquals(original,file.readText())
    }
    @Test fun previousResultCanRecoverByOriginalDigestsButAmbiguousOwnershipStaysFull() {
        val layer=layer();val digest="a".repeat(64);val root=mission()
        write(File(root,"inventory/$digest.json"),JSONObject())
        write(File(temporary.root,"mapping-layers/${layer.id}/surface/cameras.json"),JSONObject().put("originalSha256",JSONArray(listOf(digest))))
        assertNotNull(MappingBoundary.resolve(temporary.root,layer))
        write(File(mission(),"inventory/$digest.json"),JSONObject())
        assertNull(MappingBoundary.resolve(temporary.root,layer))
    }
    @Test fun explicitMissingMissionDoesNotGuessAnotherBoundary() {
        val layer=layer();mission()
        write(File(temporary.root,"mapping-layers/${layer.id}/layer.json"),layer.json().put("missionId",UUID.randomUUID().toString()))
        assertNull(MappingBoundary.resolve(temporary.root,layer))
    }
}
