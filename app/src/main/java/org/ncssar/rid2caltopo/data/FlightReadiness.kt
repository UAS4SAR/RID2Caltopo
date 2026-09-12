package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class FlightReadiness(
    val aircraft: AircraftReadiness,
    val selectedAccessories: Set<String> = emptySet(),
    val payloadDescription: String = "",
    val payloadWeightGrams: Double? = null,
    val pilotJson: String = "{}",
    val serviceJson: String = "{}",
    val rosterFetchedAt: String = "",
    val confirmedAt: String = "",
    val operatingProfileJson: String? = null,
    val configurationVersion: Long = 0
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        operatingProfileJson?.let { put("operatingProfile", JSONObject(it)) }
        put("configurationVersion", configurationVersion)
        put("aircraft", aircraft.toJSON()); put("selectedAccessories", JSONArray(selectedAccessories.toList()))
        put("payloadDescription", payloadDescription); put("payloadWeightGrams", payloadWeightGrams ?: JSONObject.NULL)
        put("pilot", JSONObject(pilotJson)); put("service", JSONObject(serviceJson))
        put("rosterFetchedAt", rosterFetchedAt); put("confirmedAt", confirmedAt)
        put("pilotAttribution", if (JSONObject(pilotJson).optString("memberId").isBlank()) "unresolved" else "operator_selected")
        put("takeoffWeightGrams", aircraft.totalWeight(selectedAccessories, payloadDescription, payloadWeightGrams) ?: JSONObject.NULL)
    }
    fun resolvingPilot(callsign: String, roster: JSONArray?): FlightReadiness {
        val matches = (0 until (roster?.length() ?: 0)).map { roster!!.getJSONObject(it) }
            .filter { callsign.isNotBlank() && it.optString("callsign").equals(callsign.trim(), true) }
        return copy(pilotJson = matches.singleOrNull()?.toString() ?: "{}").withReportedPilot(callsign, matches.size == 1)
    }
    fun withAircraft(local: AircraftReadiness?, published: AircraftReadiness?): FlightReadiness {
        val selected = local ?: published ?: aircraft
        return copy(aircraft = selected, selectedAccessories = selectedAccessories.intersect(selected.accessories.map { it.id }.toSet()))
    }
    fun withReportedPilot(callsign: String, matched: Boolean): FlightReadiness {
        val reported = if (matched) JSONObject(pilotJson) else JSONObject()
        reported.put("callsign", callsign.trim())
        return copy(pilotJson = reported.toString())
    }
    fun confirmed(): FlightReadiness = copy(confirmedAt = Instant.now().toString())
}
