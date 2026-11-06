package org.ncssar.rid2caltopo.video.surface

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import kotlin.math.*

class SurfacePreparationTest {
    @Test fun oversizedAndPolarRegionsHaveActionableMessages() {
        for (area in listOf(bounds(5000.0), SurfaceBounds(-150.0, 20.0, -80.0, 80.0))) {
            val error = assertThrows(IllegalArgumentException::class.java) { SurfacePreparation.grid(area) }
            assertTrue(error.message!!.contains("4 km across"))
        }
        val error = assertThrows(IllegalArgumentException::class.java) { SurfacePreparation.grid(SurfaceBounds(0.0, 75.0, 0.001, 75.001)) }
        assertTrue(error.message!!.contains("70°S and 70°N"))
    }

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
        assertTrue(sources.all { it.url.contains("://rockyweb.usgs.gov/") })
        assertEquals(sources.size,sources.map { it.url }.distinct().size)
        assertThrows(IllegalArgumentException::class.java) { SurfacePreparation.sources(listOf(JSONObject("{\"items\":[]}"))) }
    }

    @Test fun prefersListedS3UrlForTheSameSurveyFileAndDoesNotBlendSurveys() {
        val survey = "CA_SierraNevada_B22/CA_SierraNevada_2_2022"
        val other = "CA_NoCAL_3DEP_Supp_Funding_2018_D18/CA_NoCAL_Wildfires_B1_2018"
        val rockyOne = lidarUrl("rockyweb.usgs.gov", "/vdelivery/Datasets/Staged/Elevation/LPC", survey, "one.laz")
        val s3One = lidarUrl("prd-tnm.s3.amazonaws.com", "/StagedProducts/Elevation/LPC", survey, "one.laz")
        val rockyTwo = lidarUrl("rockyweb.usgs.gov", "/vdelivery/Datasets/Staged/Elevation/LPC", survey, "two.laz")
        val olderS3 = lidarUrl("prd-tnm.s3.amazonaws.com", "/StagedProducts/Elevation/LPC", other, "old.laz")
        val page = JSONObject().put("items", org.json.JSONArray()
            .put(lidarItem(rockyOne, "2024-05-01"))
            .put(lidarItem(s3One, "2020-01-01", urls = mapOf("LAZ" to s3One)))
            .put(lidarItem(rockyTwo, "2024-05-01", laz = rockyTwo))
            .put(lidarItem(olderS3, "2020-01-14")))
        val sources = SurfacePreparation.sources(listOf(page))
        assertEquals(listOf(s3One, rockyTwo), sources.map { it.url }.sorted())
        assertEquals(1, sources.map { it.survey }.distinct().size)
        assertTrue(sources.all { it.survey.contains("CA_SierraNevada_2_2022") })
        assertTrue(sources.all { it.published == "2024-05-01" })
    }

    @Test fun s3ListedOnlyAsDownloadLazUrlReplacesRockywebForThatFile() {
        val survey = "CA_SierraNevada_B22/CA_SierraNevada_2_2022"
        val rocky = lidarUrl("rockyweb.usgs.gov", "/vdelivery/Datasets/Staged/Elevation/LPC", survey, "one.laz")
        val s3 = lidarUrl("prd-tnm.s3.amazonaws.com", "/StagedProducts/Elevation/LPC", survey, "one.laz")
        val sources = SurfacePreparation.sources(listOf(JSONObject().put("items", org.json.JSONArray()
            .put(lidarItem(rocky, "2024-05-01", laz = s3)))))
        assertEquals(listOf(s3), sources.map { it.url })
        assertEquals("2024-05-01", sources.single().published)
    }

    @Test fun unlistedS3HostAndWrongSurveyAreNotSubstituted() {
        val survey = "Proj/Work"
        val rocky = lidarUrl("rockyweb.usgs.gov", "/vdelivery/Datasets/Staged/Elevation/LPC", survey, "one.laz")
        val notLaz = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/LPC/Projects/$survey/browse/one.jpg"
        val otherFile = lidarUrl("prd-tnm.s3.amazonaws.com", "/StagedProducts/Elevation/LPC", survey, "two.laz")
        val sources = SurfacePreparation.sources(listOf(JSONObject().put("items", org.json.JSONArray()
            .put(lidarItem(rocky, "2024-05-01", urls = mapOf("JPG" to notLaz)))
            .put(lidarItem(otherFile, "2024-05-01")))))
        assertEquals(listOf(otherFile, rocky).sorted(), sources.map { it.url }.sorted())
        assertTrue(sources.single { it.url.endsWith("one.laz") }.url.contains("rockyweb.usgs.gov"))
    }

    private fun lidarUrl(host: String, prefix: String, survey: String, file: String) =
        "https://$host$prefix/Projects/$survey/LAZ/$file"

    private fun lidarItem(url: String, published: String, laz: String? = null, urls: Map<String, String>? = null): JSONObject {
        val item = JSONObject()
            .put("downloadURL", url)
            .put("publicationDate", published)
            .put("sizeInBytes", 1_000_000)
            .put("metaUrl", "https://www.sciencebase.gov/catalog/item/abc")
        if (laz != null) item.put("downloadLazURL", laz)
        if (urls != null) item.put("urls", JSONObject(urls))
        return item
    }
}
