package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspacePolicyTest {
    @Test
    fun fallonClassDRequiresAuthorizationAndShowsFacilityMapLimit() {
        val state = AirspacePolicy.buildUiState(
            records = listOf(
                FaaUasFacilityMapRecord(
                    objectId = 90600L,
                    ceilingFeet = 400,
                    unit = "Feet",
                    primaryAirportFaaId = "NFL",
                    primaryAirportIcao = "KNFL",
                    primaryAirportName = "Fallon NAS (Van Voorhis Fld)",
                    laancAvailable = true,
                    airspaceClasses = listOf("D"),
                    rings = containingRing
                )
            ),
            loading = false,
            errorMessage = null,
            pilotCoordinate = pilotCoordinate
        )

        assertEquals(AirspaceChipSeverity.Danger, state.chipSeverity)
        assertEquals(
            "Airspace: Authorization required - Fallon NAS Class D; FAA grid limit 400 ft AGL",
            state.chipLabel
        )
        assertEquals("Fallon NAS Class D", state.summary)
        assertEquals(
            "The current location is inside an FAA UAS Facility Map grid identified as Class D. " +
                "FAA authorization is required before flight. " +
                "The displayed 400 ft AGL value is the lowest FAA UAS Facility Map limit across the area, " +
                "not the top of the controlled-airspace class. Requests above it require further FAA coordination.",
            state.detail
        )
    }

    @Test
    fun multipleFacilityMapGridsUseLowestLimitRegardlessOfResponseOrder() {
        val oneHundred = record(objectId = 2L, ceilingFeet = 100)
        val twoHundred = record(objectId = 1L, ceilingFeet = 200)

        val forward = AirspacePolicy.buildUiState(
            records = listOf(twoHundred, oneHundred),
            loading = false,
            errorMessage = null,
            pilotCoordinate = pilotCoordinate
        )
        val reverse = AirspacePolicy.buildUiState(
            records = listOf(oneHundred, twoHundred),
            loading = false,
            errorMessage = null,
            pilotCoordinate = pilotCoordinate
        )

        assertEquals(forward.chipLabel, reverse.chipLabel)
        assertEquals(
            "Airspace: Authorization required - Truckee Tahoe Airport Class D; FAA grid limit 100 ft AGL",
            forward.chipLabel
        )
        assertTrue(forward.detail.contains("lowest FAA UAS Facility Map limit"))
    }

    @Test
    fun nearbyGridDoesNotClaimAuthorizationRequiredAtCurrentLocation() {
        val reddingGrid = FaaUasFacilityMapRecord(
            objectId = 145193L,
            ceilingFeet = 400,
            unit = "Feet",
            primaryAirportFaaId = "RDD",
            primaryAirportIcao = "KRDD",
            primaryAirportName = "Redding Rgnl",
            laancAvailable = true,
            airspaceClasses = listOf("D"),
            rings = listOf(
                listOf(
                    AirspaceCoordinate(40.58, -122.3242),
                    AirspaceCoordinate(40.61, -122.3242),
                    AirspaceCoordinate(40.61, -122.30),
                    AirspaceCoordinate(40.58, -122.30),
                    AirspaceCoordinate(40.58, -122.3242)
                )
            )
        )

        val state = AirspacePolicy.buildUiState(
            records = listOf(reddingGrid),
            loading = false,
            errorMessage = null,
            pilotCoordinate = AirspaceCoordinate(40.59122, -122.33465)
        )

        assertEquals(AirspaceChipSeverity.Caution, state.chipSeverity)
        assertEquals("Airspace nearby - Redding Rgnl Class D 0.5 mi", state.chipLabel)
        assertTrue(state.detail.contains("No FAA UAS Facility Map grid covers the current location."))
        assertTrue(state.detail.contains("authorization is required only if"))
    }

    @Test
    fun unavailableAirspaceDataDoesNotClaimClear() {
        val state = AirspacePolicy.buildUiState(
            records = emptyList(),
            loading = false,
            errorMessage = "Controlled-airspace lookup unavailable"
        )

        assertEquals(AirspaceChipSeverity.Neutral, state.chipSeverity)
        assertEquals("Airspace unavailable", state.chipLabel)
    }

    @Test
    fun cachedRestrictionSurvivesFailureWithStaleHeading() {
        val cached = record(1L, 100)
        val state = AirspacePolicy.buildUiState(listOf(cached), false, "Offline", pilotCoordinate)
        assertEquals(AirspaceChipSeverity.Danger, state.chipSeverity)
        assertEquals("Airspace stale • review", state.chipLabel)
        assertEquals(listOf(cached), state.records)
        assertTrue(state.detail.contains("authorization is required"))
    }

    private fun record(objectId: Long, ceilingFeet: Int) = FaaUasFacilityMapRecord(
        objectId = objectId,
        ceilingFeet = ceilingFeet,
        unit = "Feet",
        primaryAirportFaaId = "TRK",
        primaryAirportIcao = "KTRK",
        primaryAirportName = "Truckee Tahoe Airport",
        laancAvailable = true,
        airspaceClasses = listOf("D"),
        rings = containingRing
    )

    private val pilotCoordinate = AirspaceCoordinate(40.0, -120.0)
    private val containingRing = listOf(
        listOf(
            AirspaceCoordinate(39.9, -120.1),
            AirspaceCoordinate(40.1, -120.1),
            AirspaceCoordinate(40.1, -119.9),
            AirspaceCoordinate(39.9, -119.9),
            AirspaceCoordinate(39.9, -120.1)
        )
    )
}
