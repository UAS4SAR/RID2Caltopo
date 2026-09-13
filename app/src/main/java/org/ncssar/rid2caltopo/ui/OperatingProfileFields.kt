package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.*
import org.ncssar.rid2caltopo.data.OperatingProfiles.objects

@Composable
fun OperatingProfileFields(remoteId: String, value: FlightReadiness, state: JSONObject, onChange: (FlightReadiness) -> Unit) {
    val mapId = CaltopoMap.GetMapId()
    val org = CaltopoClient.GetTrackerCoordinationUrlPfx()
    OperatingProfiles.setScope(org, mapId)
    val existing = remember(remoteId, org, mapId) { OperatingProfiles.active(JSONObject(CaltopoClient.GetDroneSpec(remoteId)?.flightReadinessJson ?: "{}")) }
    var selected by remember(remoteId, org, mapId) { mutableStateOf(existing?.optJSONObject("profile") ?: OperatingProfiles.initial(state, AircraftOrganizationAccess.rememberedProfileId())) }
    var touched by remember(remoteId, org, mapId) { mutableStateOf(existing != null || OperatingProfiles.assignmentId.isNotEmpty()) }
    var details by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var reuse by remember(remoteId, org, mapId) { mutableStateOf(true) }
    var briefing by remember(remoteId, org, mapId) { mutableStateOf(
        IncidentBriefing.currentFlight(existing, CaltopoClient.GetDroneSpec(remoteId)?.isCurrentFlightConfirmed == true, org, mapId)
            ?: OperatingProfiles.incidentBriefing) }
    var checked by remember(remoteId, org, mapId) { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(state.toString()) { if (!touched) selected = OperatingProfiles.initial(state, AircraftOrganizationAccess.rememberedProfileId()) }
    LaunchedEffect(selected.toString(), checked, reuse, state.toString(), value.pilotJson, value.aircraft.recordId, briefing, org, mapId) {
        onChange(value.copy(operatingProfileJson = OperatingProfiles.snapshot(selected, state,
            JSONObject(value.pilotJson).optString("memberId"), value.aircraft.recordId, mapId,
            AircraftOrganizationAccess.belongsToOrganization(), checked, briefing, org).put("useForAssignment", reuse).toString()))
    }
    TextButton(onClick = { expanded = true }) { Text("Type of flight: " + selected.optString("name")) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        OperatingProfiles.choices(state).forEach { profile ->
            DropdownMenuItem(text = { Text(profile.optString("name")) }, onClick = {
                selected = profile; touched = true; checked = emptySet(); expanded = false
            })
        }
    }
    if (selected.optString("id") == "bvlos-pending") Text("BVLOS authority details not configured", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { details = !details }) { Text("Incident & briefing ${if (details) "▾" else "▸"}") }
    if (details) {
    Text("Incident map: " + mapId.ifBlank { "Standalone — no connected map" })
    OutlinedTextField(value = briefing.incidentName, onValueChange = { briefing = briefing.copy(incidentName = it.take(160)) },
        label = { Text("Incident name / reference (optional)") })
    OutlinedTextField(value = briefing.notes, onValueChange = { briefing = briefing.copy(notes = it.take(4000)) },
        label = { Text("Briefing notes (optional)") }, minLines = 3, maxLines = 6)
    Text("Add incident-specific details here, such as VO, operating area, limits or RTH. The connected map is recorded automatically; no Tracker edit is needed for each search.")
    Row {
        Checkbox(checked = reuse, onCheckedChange = { reuse = it })
        Text("Remember profile and incident details across batteries")
    }
    TextButton(onClick = {
        OperatingProfiles.endAssignment(); briefing = IncidentBriefing(); checked = emptySet(); reuse = true; touched = true
    }) { Text("New incident / clear details") }
    OperatingProfiles.warnings(selected, state, JSONObject(value.pilotJson).optString("memberId"),
        value.aircraft.recordId, mapId, AircraftOrganizationAccess.belongsToOrganization()).forEach {
        Text(it + " Continue remains available.", color = MaterialTheme.colorScheme.error)
    }
    Text("Standard briefing from selected profile", style = MaterialTheme.typography.titleSmall)
    selected.optJSONArray("conditions").objects().forEachIndexed { index, condition ->
        Row {
            Checkbox(checked = index in checked, onCheckedChange = { checked = if (it) checked + index else checked - index })
            Text(listOf(condition.optString("text"), condition.optString("source"), condition.optString("unit"), condition.optString("reference")).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
    Text("RPIC or VO for the RPIC: review applicability and conditions. Selection does not grant authority. Airspace authorization is separate.")
    }
}

@Composable
fun ActiveOperatingProfiles() {
    var names by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        while (true) {
            names = CaltopoClient.GetActiveRemoteIdsSnapshot().mapNotNull { CaltopoClient.GetDroneSpec(it) }.mapNotNull { drone ->
                OperatingProfiles.active(JSONObject(drone.flightReadinessJson))?.optJSONObject("profile")?.let {
                    "${drone.mappedId}: ${it.optString("name")}"
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    if (names.isNotEmpty()) Surface(tonalElevation = androidx.compose.ui.unit.Dp(2f)) {
        Text(names.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
    }
}
