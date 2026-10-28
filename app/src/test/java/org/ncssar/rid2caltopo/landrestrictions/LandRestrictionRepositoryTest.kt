package org.ncssar.rid2caltopo.landrestrictions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandRestrictionRepositoryTest {
    @Test
    fun buildsArcGisEnvelopeQueryMatchingAppleSources() {
        val source = LandRestrictionRepository.sources.first()
        val url = LandRestrictionRepository.buildQueryUrl(
            source,
            LandCoordinate(39.7392, -104.9903),
            radiusStatuteMiles = 1.0
        )

        assertEquals("geojson", url.queryParameter("f"))
        assertEquals("esriGeometryEnvelope", url.queryParameter("geometryType"))
        assertEquals("4326", url.queryParameter("outSR"))
        assertEquals("1=1", url.queryParameter("where"))
        assertTrue(url.queryParameter("geometry").orEmpty().split(',').size == 4)
        val bounds = url.queryParameter("geometry").orEmpty().split(',').map(String::toDouble)
        val latitudeRadius = bounds[3] - 39.7392
        assertEquals(0.868976 / 60.0, latitudeRadius, 0.0000002)
    }

    @Test
    fun federalAndColoradoProvidersExposeAuthoritativeAgencyLinks() {
        assertEquals(4, LandRestrictionRepository.sources.size)
        assertTrue(LandRestrictionRepository.sources.all { it.agency.rulesUrl.startsWith("https://") })
        assertTrue(LandRestrictionRepository.sources.any { it.detailsUrlFields.contains("CPW_URL") })
        assertEquals("RSL_TYPE='NWR'", LandRestrictionRepository.sources.first { it.id == "fws-refuge" }.whereClause)
    }

    @Test fun npsUsesTheNpsOwnedPolygonLayerAndASeparateCacheIdentity() {
        val nps = LandRestrictionRepository.sources.first { it.id == "nps" }
        assertEquals("services1.arcgis.com", java.net.URI(nps.queryEndpoint).host)
        assertTrue(nps.queryEndpoint.contains("/fBc8EJBxQRMcHlei/"))
        assertTrue(nps.queryEndpoint.endsWith("/NPS_Land_Resources_Division_Boundary_and_Tract_Data_Service/FeatureServer/2/query"))
        assertEquals(listOf("UNIT_CODE", "GlobalID", "OBJECTID"), nps.identifierFields)
        assertEquals("nps-v2.geojson", nps.cacheFileName)
        assertTrue(nps.cacheFileName != "nps.geojson")
        assertTrue(nps.detailsUrlFields.contains("METADATA"))
        assertEquals("fws-refuge.geojson", LandRestrictionRepository.sources.first { it.id == "fws-refuge" }.cacheFileName)
    }
    @Test fun officialNpsResponsePreservesParkNameIdentifierAndPolygon() {
        val payload = javaClass.getResourceAsStream("/nps-yosemite.geojson")!!.bufferedReader().use { it.readText() }
        val source = LandRestrictionRepository.sources.first { it.id == "nps" }
        val areas = LandRestrictionParser.parse(payload, source, LandCoordinate(37.75,-119.55),1.0)
        assertEquals(1, areas.size)
        val park = areas.single()
        assertEquals("Yosemite National Park", park.name)
        assertEquals("nps:YOSE", park.id)
        assertTrue(park.polygons.first().first().size > 100)
        assertTrue(park.containsOperator)
        assertTrue(park.detailsUrl!!.startsWith("https://landsnet.nps.gov/"))
    }
    @Test fun sourceNoticesRemainAvailableWithoutNearbyResults() {
        assertTrue(LandAgency.entries.all { it.dataCredit.isNotBlank() && it.dataNotice.isNotBlank() && it.dataMetadataUrl.startsWith("https://") })
        assertTrue(LandAgency.NationalParkService.dataNotice.contains("ownership"))
        val usfs=LandRestrictionRepository.sources.first { it.id == "usfs-wilderness" }
        assertEquals(LandRule.MappedWildernessAdvisory,usfs.rule)
        assertEquals("Mapped wilderness—verify current rules",usfs.rule.label)
        assertTrue(LandAgency.ForestService.dataNotice.contains("must not be used to determine"))
    }
}
