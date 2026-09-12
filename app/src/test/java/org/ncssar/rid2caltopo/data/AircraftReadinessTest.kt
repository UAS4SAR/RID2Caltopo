package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class AircraftReadinessTest {
    private val aircraft = AircraftReadiness(baseWeightGrams = 1200.0,
        accessories = listOf(AircraftAccessory("std", "Standard battery", 400.0, group = "battery"),
            AircraftAccessory("ext", "Extended battery", 600.0, group = "battery")))

    @Test fun unmatchedCallsignIsRetainedWithoutClaimingPilotQualification() {
        val prior = FlightReadiness(aircraft, pilotJson = """{"memberId":"prior-pilot","callsign":"OLD"}""")
        val reported = prior.withReportedPilot(" Night Pilot ", false).confirmed().toJSON()
        assertEquals("Night Pilot", reported.getJSONObject("pilot").getString("callsign"))
        assertFalse(reported.getJSONObject("pilot").has("memberId"))
        assertEquals("unresolved", reported.getString("pilotAttribution"))
        assertTrue(reported.getString("confirmedAt").isNotBlank())
    }

    @Test fun descriptionOnlyPayloadIsUnknownUntilCompleted() {
        assertNull(aircraft.totalWeight(setOf("std"), "water bottle", null))
        assertEquals(2135.0, aircraft.totalWeight(setOf("std"), "water bottle", 535.0)!!, 0.001)
    }

    @Test fun invalidAndIncompatibleConfigurationsDoNotProduceMisleadingTotals() {
        assertNull(aircraft.totalWeight(setOf("std", "ext"), "", null))
        assertNull(aircraft.totalWeight(setOf("missing"), "", null))
        assertNull(aircraft.totalWeight(setOf("std"), "payload", Double.NaN))
        assertNull(AircraftReadiness().totalWeight(emptySet(), "", null))
    }

    @Test fun savedAircraftConfigurationRoundTripsWithoutDroppingIdentityOrWeights() {
        val configured = aircraft.copy(recordId = "954af89e-fc98-4071-b5a0-a06ea03c9027",
            serialNumber = "SERIAL", registrationNumber = "FA123", monitoringEquipment = "Controller ADS-B")
        assertEquals(configured, AircraftReadiness.fromJSON(configured.toJSON()))
        assertEquals(aircraft, AircraftReadiness.fromJSON(FlightReadiness(aircraft).toJSON().getJSONObject("aircraft")))
        val saved = AppConfig.newBuilder().addRidMappings(AppConfig.RidMapping.newBuilder()
            .setRemoteId("RID").setReadinessJson(configured.toJSON().toString()).build()).build()
        val restored = AppConfig.parseFrom(saved.toByteArray()).ridMappingsList.single()
        assertEquals(configured, AircraftReadiness.fromJSON(org.json.JSONObject(restored.readinessJson)))
    }
}
