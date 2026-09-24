package org.ncssar.rid2caltopo.landrestrictions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandRestrictionParserTest {
    @Test
    fun emptyLandQueryIsNotClearanceAndUpdatingStateSurvivesCompactLabel() {
        val label = LandRestrictionPolicy.chipLabel(emptyList(), false, false)
        assertEquals("No mapped restrictions", label)
        assertEquals(label, conciseLandStatusLabel(LandRestrictionUiState(
            severity = LandRestrictionSeverity.Normal, chipLabel = label)))
        assertEquals("Land rules updating…", conciseLandStatusLabel(LandRestrictionUiState(
            severity = LandRestrictionSeverity.Normal, chipLabel = "Land rules updating…")))
    }

    @Test
    fun disabledLandRestrictionState_keepsOffChipVisible() {
        val state = disabledLandRestrictionUiState()

        assertEquals(true, state.visible)
        assertEquals(false, state.enabled)
        assertEquals("Land rules off", conciseLandStatusLabel(state))
    }

    @Test
    fun toolbarUsesBriefLandLabelWhileStateRetainsAreaDetails() {
        assertEquals(
            "Land rules nearby",
            conciseLandStatusLabel(
                LandRestrictionUiState(
                    severity = LandRestrictionSeverity.Caution,
                    chipLabel = "Land rules: 17 nearby properties with specific restrictions"
                )
            )
        )
    }

    private val source = LandRestrictionSource(
        id = "test-park",
        queryEndpoint = "https://example.gov/query",
        agency = LandAgency.NationalParkService,
        rule = LandRule.LaunchLandOperateRestricted,
        nameFields = listOf("UNIT_NAME"),
        identifierFields = listOf("UNIT_CODE")
    )

    @Test
    fun parsesAgencyBoundaryAndRecognizesOperatorInside() {
        val areas = LandRestrictionParser.parse(
            polygonFixture,
            source,
            center = LandCoordinate(39.7392, -104.9903),
            operatingRadiusNm = 1.0
        )

        assertEquals(1, areas.size)
        assertEquals("Test National Park", areas.single().name)
        assertTrue(areas.single().containsOperator)
        assertEquals(0.0, areas.single().distanceNm, 0.0001)
        assertEquals("www.nps.gov", java.net.URI(areas.single().agency.rulesUrl).host)
        assertEquals(LandRestrictionSeverity.Danger, LandRestrictionPolicy.severity(areas, false))
        assertEquals("Land rules: RESTRICTED", LandRestrictionPolicy.chipLabel(areas, false, false))
    }

    @Test
    fun measuresNearestBoundaryEdgeRatherThanOnlyVertices() {
        val areas = LandRestrictionParser.parse(
            longEdgeFixture,
            source.copy(agency = LandAgency.ForestService),
            center = LandCoordinate(39.74, -105.0),
            operatingRadiusNm = 1.0
        )

        assertFalse(areas.single().containsOperator)
        assertTrue(areas.single().distanceNm < 0.7)
        assertTrue(areas.single().intersectsOperatingArea)
    }

    @Test
    fun stateParkInsideBoundaryRemainsAdvisory() {
        val area = LandRestrictionArea(
            id = "cpw:1",
            name = "Example State Park",
            agency = LandAgency.ColoradoParksAndWildlife,
            rule = LandRule.PropertySpecificRules,
            polygons = emptyList(),
            intersectsOperatingArea = true,
            containsOperator = true,
            distanceNm = 0.0,
            detailsUrl = "https://cpw.state.co.us/example"
        )

        assertEquals(LandRestrictionSeverity.Caution, LandRestrictionPolicy.severity(listOf(area), false))
        assertEquals("Land rules: 1 nearby", LandRestrictionPolicy.chipLabel(listOf(area), false, false))
    }

    @Test
    fun waitingForLocationDoesNotClaimLandRulesAreClear() {
        assertEquals(
            LandRestrictionSeverity.Neutral,
            LandRestrictionPolicy.severity(
                areas = emptyList(),
                hasError = false,
                waitingForLocation = true
            )
        )
        assertEquals(
            "Land rules pending",
            LandRestrictionPolicy.chipLabel(
                areas = emptyList(),
                loading = false,
                hasError = false,
                waitingForLocation = true
            )
        )
    }

    private val polygonFixture = """
        {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"UNIT_NAME":"Test National Park","UNIT_CODE":"TEST"},"geometry":{"type":"Polygon","coordinates":[[[-105.01,39.72],[-104.97,39.72],[-104.97,39.76],[-105.01,39.76],[-105.01,39.72]]]}}]}
    """.trimIndent()

    private val longEdgeFixture = """
        {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"UNIT_NAME":"Long Boundary","UNIT_CODE":"LONG"},"geometry":{"type":"Polygon","coordinates":[[[-105.2,39.75],[-104.8,39.75],[-104.8,39.80],[-105.2,39.80],[-105.2,39.75]]]}}]}
    """.trimIndent()
}
