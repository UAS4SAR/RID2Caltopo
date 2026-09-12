package org.ncssar.rid2caltopo.data
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class FlightConfirmationRegressionTest {
    @Test fun localAircraftSurvivesOlderRosterAndTypedPilotMatchesWithoutClaimingQualification() {
        val local = AircraftReadiness(baseWeightGrams = 297.0, registrationNumber = "FA-EXAMPLE")
        val roster = JSONArray().put(JSONObject().put("memberId", "pilot").put("callsign", "1sar7").put("eligible", false))
        val confirmed = FlightReadiness(AircraftReadiness()).withAircraft(local, AircraftReadiness()).resolvingPilot("1SAR7", roster).toJSON()
        assertEquals(297.0, confirmed.getDouble("takeoffWeightGrams"), 0.0)
        assertEquals("FA-EXAMPLE", confirmed.getJSONObject("aircraft").getString("registrationNumber"))
        assertEquals("pilot", confirmed.getJSONObject("pilot").getString("memberId"))
        assertFalse(confirmed.getJSONObject("pilot").getBoolean("eligible"))
        assertFalse(FlightReadiness(local).resolvingPilot("UNKNOWN", roster).toJSON().getJSONObject("pilot").has("memberId"))
    }
    @Test fun bvlosChoiceDoesNotInventAuthority() {
        assertEquals("unresolved", OperatingProfiles.choices(JSONObject()).first { it.optString("id") == "bvlos-pending" }.optString("authorityType"))
    }
}
