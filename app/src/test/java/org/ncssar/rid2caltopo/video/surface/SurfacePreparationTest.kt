package org.ncssar.rid2caltopo.video.surface

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import kotlin.math.*

class SurfacePreparationTest {
    private fun bounds(width:Double): SurfaceBounds {
        val dy=width/2/6371008.8*180/PI;val dx=dy/cos(39.0*PI/180)
        return SurfaceBounds(-121-dx,39-dy,-121+dx,39+dy)
    }
    @Test fun parcelsAndOneMileRadiusAreBoundedAndPadded() {
        for(acres in listOf(40.0,60.0)) {
            val width=sqrt(acres*4046.8564224)
            val p=SurfacePreparation.grid(bounds(width))
            assertEquals(1,p.tiles)
            assertTrue(p.width>=width+123.9)
            assertTrue(p.width<=ceil(width+124)+1)
        }
        assertEquals(16,SurfacePreparation.grid(bounds(2*1609.344)).tiles)
        assertThrows(IllegalArgumentException::class.java) { SurfacePreparation.grid(bounds(5000.0)) }
    }
    @Test fun completedOrFailedRunCanReusePlanAndChangedRegionCanRecheck() {
        val area=bounds(400.0)
        val other=bounds(600.0)
        assertEquals(SurfaceCatalogAction.Preserve,surfaceCatalogAction(true,true,true,area,other))
        assertEquals(SurfaceCatalogAction.Preserve,surfaceCatalogAction(false,true,true,area,area))
        assertEquals(SurfaceCatalogAction.Lookup,surfaceCatalogAction(false,true,true,area,other))
        assertEquals(SurfaceCatalogAction.Lookup,surfaceCatalogAction(false,true,true,null,area))
        assertEquals(SurfaceCatalogAction.Clear,surfaceCatalogAction(false,false,true,area,area))
        assertEquals(SurfaceCatalogAction.Clear,surfaceCatalogAction(false,true,false,area,area))
    }

    @Test fun selectsOnePublishedSurveyAndDoesNotBlendOverlaps() {
        val root=if(File("../test-fixtures").exists()) File("../test-fixtures") else File("test-fixtures")
        val page=JSONObject(File(root,"aol/usgs-lpc-catalog.json").readText())
        val sources=SurfacePreparation.sources(listOf(page))
        assertEquals(6,sources.size)
        assertEquals(1,sources.map { it.survey }.distinct().size)
        assertTrue(sources.all { it.survey.contains("CA_SierraNevada_2_2022") })
        assertEquals(sources.size,sources.map { it.url }.distinct().size)
        assertThrows(IllegalArgumentException::class.java) { SurfacePreparation.sources(listOf(JSONObject("{\"items\":[]}"))) }
    }
}
