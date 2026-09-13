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
    fun equipmentJSON(): JSONObject = JSONObject().apply {
        put("selectedAccessories", JSONArray(selectedAccessories.toList()))
        put("payloadDescription", payloadDescription)
        put("payloadWeightGrams", payloadWeightGrams ?: JSONObject.NULL)
    }
    fun restoringEquipment(saved: JSONObject): FlightReadiness {
        val ids = saved.optJSONArray("selectedAccessories") ?: JSONArray()
        return copy(selectedAccessories = (0 until ids.length()).map { ids.getString(it) }.toSet(),
            payloadDescription = saved.optString("payloadDescription"),
            payloadWeightGrams = if (saved.isNull("payloadWeightGrams")) null else saved.optDouble("payloadWeightGrams").takeIf { it.isFinite() })
    }
    fun pilotQualificationWarning(): String {
        return "The saved roster does not verify current Part 107 qualifications for this callsign. Review the callsign or update the pilot’s qualifications in Tracker. Recording and publishing remain available."
    }
    fun equipmentSummary(): String {
        val names = aircraft.accessories.filter { it.id in selectedAccessories }.map { it.name }.toMutableList()
        if (payloadDescription.isNotBlank()) names.add(payloadDescription)
        else if (payloadWeightGrams != null) names.add("Payload")
        return names.joinToString(", ").ifEmpty { "No equipment or payload selected" }
    }
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
